package sh.hnet.gallery.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Timeline
import sh.hnet.gallery.model.TrashOrder

/**
 * The Albums tab (SPEC §9.2): in S4a the collections only. Counts of the whole library; Trash's of
 * what is in it.
 *
 * @param partial With partial access: how many items the app may see (the card's count)
 */
data class AlbumsState(
    val loaded: Boolean = false,
    val access: Access = Access.FULL,
    val partial: Int? = null,
    val counts: Map<CollectionKind, Int> = emptyMap(),
    val trash: Int = 0,
)

class AlbumsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val state: StateFlow<AlbumsState> = combine(app.index.access, app.index.items) { access, items ->
        if (items == null) {
            AlbumsState(access = access)
        } else {
            AlbumsState(
                loaded = true,
                access = access,
                partial = if (access == Access.PARTIAL) items.count { !it.trashed } else null,
                counts = CollectionKind.entries.associateWith { kind -> items.count(kind::holds) },
                trash = items.count { it.trashed },
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumsState())

    fun recheck() = app.index.recheck()
}

/** A collection's page (SPEC §9.2): its items by day or month in the shared grid, newest or oldest first. */
sealed interface CollectionState {
    data object Loading : CollectionState

    /** @param folders The screenshot folders the collection gathers (its subtitle, SPEC-albums §3.2) */
    data class Shown(val days: Timeline, val months: Timeline, val oldestFirst: Boolean, val folders: Int) : CollectionState {
        fun timeline(step: GridStep): Timeline = if (step.months) months else days
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication
    private val kind = MutableStateFlow<CollectionKind?>(null)

    fun show(collection: CollectionKind) {
        kind.value = collection
    }

    val step: StateFlow<GridStep> = app.preferences.gridStep.stateIn(viewModelScope, SharingStarted.Eagerly, GridStep.DEFAULT)

    val facts: StateFlow<Map<Long, Fact>> = app.facts.facts.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val offsets = app.facts.facts.map { facts -> facts?.mapValues { it.value.offsetMinutes } ?: emptyMap() }.distinctUntilChanged()

    val state: StateFlow<CollectionState> = combine(kind.filterNotNull(), app.index.items, app.preferences.collectionsOldestFirst, offsets) { kind, items, oldest, offsets ->
        Input(kind, items, kind.name in oldest, offsets)
    }
        .conflate()
        .mapLatest { input ->
            val items = input.items ?: return@mapLatest CollectionState.Loading
            val shown = items.filter(input.kind::holds)
            val zone = ZoneId.systemDefault()
            CollectionState.Shown(
                Timeline.build(shown, months = false, zone, input.oldestFirst) { input.offsets[it] },
                Timeline.build(shown, months = true, zone, input.oldestFirst) { input.offsets[it] },
                input.oldestFirst,
                folders = if (input.kind == CollectionKind.SCREENSHOTS) shown.map { it.folder }.distinct().size else 0,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionState.Loading)

    fun setStep(step: GridStep) {
        viewModelScope.launch { app.preferences.setGridStep(step) }
    }

    fun setOldestFirst(oldest: Boolean) {
        val current = kind.value ?: return
        viewModelScope.launch { app.preferences.setCollectionOldestFirst(current.name, oldest) }
    }

    private data class Input(val kind: CollectionKind, val items: List<MediaItem>?, val oldestFirst: Boolean, val offsets: Map<Long, Int?>)
}

/** Trash (SPEC §11): its items, the newest trashed first; null while the index loads. */
class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val items: StateFlow<List<MediaItem>?> = app.index.items
        .map { items -> items?.let(TrashOrder::of) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

