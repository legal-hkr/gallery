package sh.hnet.gallery.ui.albums

import android.app.Application
import android.provider.MediaStore
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
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.facts.SourceRow
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.SourceState
import sh.hnet.gallery.model.Timeline
import sh.hnet.gallery.model.TimelineFolders
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
    val albums: List<SourceAlbum> = emptyList(),
    val sort: String = "RECENT",
    /** Every screenshot folder hidden: the collection goes too (SPEC-albums §9.11). */
    val screenshotsHidden: Boolean = false,
)

/**
 * A source's album in the Albums tab (SPEC-albums §9.4): its four newest items for the mosaic, the count,
 * the newest file's `DATE_ADDED` ("recent"), when pinned, and whether it is on another storage.
 */
data class SourceAlbum(val folder: FolderKey, val count: Int, val newest: List<MediaItem>, val recent: Long, val pinnedAt: Long?, val otherStorage: Boolean)

class AlbumsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val state: StateFlow<AlbumsState> = combine(app.index.access, app.library, app.sources.rows, app.preferences.albumSort, app.preferences.newFoldersInPhotos) { access, library, rows, sort, newFolders ->
        // Hidden sources and album covers are in no collection, Trash included (SPEC-albums §9.3)
        val items = library?.visible
        if (items == null || rows == null) {
            AlbumsState(access = access, sort = sort)
        } else {
            // Items come newest first, so each folder's first items are its newest
            val byFolder = LinkedHashMap<FolderKey, MutableList<MediaItem>>()
            for (item in items) if (!item.trashed && !TimelineFolders.isScreenshots(item.path)) byFolder.getOrPut(item.folder) { ArrayList() }.add(item)
            AlbumsState(
                loaded = true,
                access = access,
                partial = if (access == Access.PARTIAL) items.count { !it.trashed } else null,
                counts = CollectionKind.entries.associateWith { kind -> items.count(kind::holds) },
                trash = items.count { it.trashed },
                albums = byFolder.map { (folder, list) ->
                    SourceAlbum(folder, list.size, list.take(4), list.maxOf { it.added }, rows[folder.key]?.pinnedAt, folder.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY)
                },
                sort = sort,
                screenshotsHidden = app.index.items.value.orEmpty().asSequence().map { it.folder }.filter { TimelineFolders.isScreenshots(it.path) }.distinct().let { folders ->
                    val list = folders.toList()
                    list.isNotEmpty() && list.all { SourceStore.stateOf(it, rows, newFolders) == SourceState.HIDDEN }
                },
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumsState())

    fun recheck() = app.index.recheck()

    fun setSort(sort: String) {
        viewModelScope.launch { app.preferences.setAlbumSort(sort) }
    }
}

/** What a grid page shows (SPEC §9.2, SPEC-albums §9.10): a collection, or a source's album. */
sealed interface PageKey {
    data class Collection(val kind: CollectionKind) : PageKey

    data class Folder(val folder: FolderKey) : PageKey
}

/** A page's order (SPEC-albums §9.7, §9.10). */
enum class PageSort {
    NEWEST,
    OLDEST,

    /** A plain grid in name order, without day headers. */
    FILE_NAME;

    companion object {
        fun of(name: String?): PageSort = entries.firstOrNull { it.name == name } ?: NEWEST
    }
}

/** A grid page's content. */
sealed interface PageState {
    data object Loading : PageState

    /** The source was hidden or emptied meanwhile: the page goes (its album is no more). */
    data object Gone : PageState

    /**
     * @param folders The folders it shows (one for a source; every screenshot folder for Screenshots)
     * @param state Their state, for the chip (null for Favorites and Videos)
     */
    data class Shown(
        val days: Timeline,
        val months: Timeline,
        val sort: PageSort,
        val folders: List<FolderKey>,
        val state: SourceState?,
        val pinned: Boolean,
        val count: Int,
    ) : PageState {
        fun timeline(step: GridStep): Timeline = if (step.months) months else days
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication
    private val key = MutableStateFlow<PageKey?>(null)

    fun show(page: PageKey) {
        key.value = page
    }

    val step: StateFlow<GridStep> = app.preferences.gridStep.stateIn(viewModelScope, SharingStarted.Eagerly, GridStep.DEFAULT)

    val facts: StateFlow<Map<Long, Fact>> = app.facts.facts.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val offsets = app.facts.facts.map { facts -> facts?.mapValues { it.value.offsetMinutes } ?: emptyMap() }.distinctUntilChanged()

    private val choices = combine(app.sources.rows, app.preferences.newFoldersInPhotos, app.preferences.collectionsOldestFirst) { rows, newFolders, oldest -> Choices(rows, newFolders, oldest) }

    val state: StateFlow<PageState> = combine(key.filterNotNull(), app.library, app.index.items, choices, offsets) { key, library, all, choices, offsets ->
        Input(key, library, all, choices, offsets)
    }
        .conflate()
        .mapLatest(::build)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageState.Loading)

    private fun build(input: Input): PageState {
        val library = input.library ?: return PageState.Loading
        val rows = input.choices.rows ?: return PageState.Loading
        val newFolders = input.choices.newFolders
        val zone = ZoneId.systemDefault()
        val shown: List<MediaItem>
        val folders: List<FolderKey>
        val sort: PageSort
        val state: SourceState?
        var pinned = false
        when (val key = input.key) {
            is PageKey.Collection -> {
                shown = library.visible.filter(key.kind::holds)
                sort = if (key.kind.name in input.choices.oldest) PageSort.OLDEST else PageSort.NEWEST
                // Every screenshot folder on the phone, hidden ones too: the chip sets them all (SPEC-albums §9.11)
                folders = if (key.kind == CollectionKind.SCREENSHOTS) input.all.orEmpty().mapTo(LinkedHashSet()) { it.folder }.filter { TimelineFolders.isScreenshots(it.path) } else emptyList()
                state = folders.firstOrNull()?.let { SourceStore.stateOf(it, rows, newFolders) }
            }
            is PageKey.Folder -> {
                val folder = key.folder
                state = SourceStore.stateOf(folder, rows, newFolders)
                shown = library.visible.filter { it.folder == folder && !it.trashed }
                if (state == SourceState.HIDDEN || shown.isEmpty()) return PageState.Gone
                folders = listOf(folder)
                sort = PageSort.of(rows[folder.key]?.sort)
                pinned = rows[folder.key]?.pinnedAt != null
            }
        }
        val (days, months) = if (sort == PageSort.FILE_NAME) {
            val plain = Timeline.plain(shown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), zone)
            plain to plain
        } else {
            val oldest = sort == PageSort.OLDEST
            Timeline.build(shown, months = false, zone, oldest) { input.offsets[it] } to Timeline.build(shown, months = true, zone, oldest) { input.offsets[it] }
        }
        return PageState.Shown(days, months, sort, folders, state, pinned, shown.size)
    }

    fun setStep(step: GridStep) {
        viewModelScope.launch { app.preferences.setGridStep(step) }
    }

    fun setSort(sort: PageSort) {
        when (val page = key.value) {
            is PageKey.Collection -> viewModelScope.launch { app.preferences.setCollectionOldestFirst(page.kind.name, sort == PageSort.OLDEST) }
            is PageKey.Folder -> viewModelScope.launch { app.sources.setSort(page.folder, sort.name.takeIf { sort != PageSort.NEWEST }) }
            null -> {}
        }
    }

    /** The page's folders to [chosen] (SPEC-albums §9.10, §9.11); the rows before, for Undo. */
    suspend fun choose(folders: List<FolderKey>, chosen: SourceState): Map<String, SourceRow?> = app.sources.choose(folders, chosen, System.currentTimeMillis())

    fun undo(before: Map<String, SourceRow?>) {
        app.appScope.launch { app.sources.undo(before) }
    }

    fun setPinned(folder: FolderKey, pinned: Boolean) {
        viewModelScope.launch { app.sources.setPinned(folder, if (pinned) System.currentTimeMillis() else null) }
    }

    private data class Choices(val rows: Map<String, SourceRow>?, val newFolders: Boolean, val oldest: Set<String>)

    private data class Input(val key: PageKey, val library: Library?, val all: List<MediaItem>?, val choices: Choices, val offsets: Map<Long, Int?>)
}

/** Trash (SPEC §11): its items, the newest trashed first; null while the index loads. */
class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val items: StateFlow<List<MediaItem>?> = app.library
        .map { library -> library?.visible?.let(TrashOrder::of) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

