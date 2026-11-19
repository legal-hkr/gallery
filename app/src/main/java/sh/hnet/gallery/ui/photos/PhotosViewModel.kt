package sh.hnet.gallery.ui.photos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.Timeline

/** What the Photos tab shows (SPEC §4.7). */
sealed interface PhotosState {
    data object Loading : PhotosState

    data object NoAccess : PhotosState

    /**
     * The timeline or one of its empty states.
     *
     * @param partial With partial access: how many items the app may see (the card's count); null with full access
     * @param otherAlbums Folders with photos outside the timeline (Album only), for the empty timeline's line
     * @param days The timeline by day, @param months by month: both ready, so the pinch moves between them while the fingers move
     */
    data class Shown(val days: Timeline, val months: Timeline, val partial: Int?, val anyMedia: Boolean, val otherAlbums: Int) : PhotosState {
        fun timeline(step: GridStep): Timeline = if (step.months) months else days
    }
}

/** The timeline (SPEC §4): the items of the folders shown in Photos, by day or by month, kept up to date. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val step: StateFlow<GridStep> = app.preferences.gridStep.stateIn(viewModelScope, SharingStarted.Eagerly, GridStep.DEFAULT)

    /** Every item's facts (the tiles' motion mark); empty until loaded. */
    val facts: StateFlow<Map<Long, Fact>> = app.facts.facts.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Only the offsets move items between days: the timeline isn't built again for every other fact read. */
    private val offsets = app.facts.facts.map { facts -> facts?.mapValues { it.value.offsetMinutes } ?: emptyMap() }.distinctUntilChanged()

    val state: StateFlow<PhotosState> = combine(app.index.access, app.library, offsets) { access, library, offsets ->
        Input(access, library, offsets)
    }
        .conflate()
        .mapLatest { input ->
            when {
                input.access == Access.NONE -> PhotosState.NoAccess
                input.library == null -> PhotosState.Loading
                else -> {
                    // The timeline: the sources In Photos (SPEC-albums §4.2)
                    val all = input.library.visible.filter { !it.trashed }
                    val shown = input.library.timeline
                    val zone = ZoneId.systemDefault()
                    val days = Timeline.build(shown, months = false, zone) { input.offsets[it] }
                    val months = Timeline.build(shown, months = true, zone) { input.offsets[it] }
                    val otherAlbums = if (shown.isEmpty()) all.mapTo(HashSet()) { it.folder }.size else 0
                    PhotosState.Shown(days, months, if (input.access == Access.PARTIAL) all.size else null, all.isNotEmpty(), otherAlbums)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotosState.Loading)

    fun setStep(step: GridStep) {
        viewModelScope.launch { app.preferences.setGridStep(step) }
    }

    /** Access given or changed in the system's dialog. */
    fun recheck() = app.index.recheck()

    private data class Input(
        val access: Access,
        val library: Library?,
        val offsets: Map<Long, Int?>,
    )
}
