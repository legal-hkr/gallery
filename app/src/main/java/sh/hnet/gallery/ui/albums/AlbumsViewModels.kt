package sh.hnet.gallery.ui.albums

import android.app.Application
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.Collator
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.facts.AlbumItemRow
import sh.hnet.gallery.facts.AlbumRow
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.facts.SourceRow
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PlaceGroups
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
    val albums: List<AlbumEntry> = emptyList(),
    val sort: String = "RECENT",
    /** Every screenshot folder hidden: the collection goes too (SPEC-albums §9.11). */
    val screenshotsHidden: Boolean = false,
    /** Places' towns (SPEC §9.2); null while place names are off – the tile goes (SPEC §20.5). */
    val places: Int? = null,
)



@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    private val users = combine(app.albums.albums, app.albums.members) { albums, members -> albums to members }

    val state: StateFlow<AlbumsState> = combine(app.index.access, app.library, app.sources.rows, combine(app.preferences.albumSort, app.preferences.newFoldersInPhotos, ::Pair), users) { access, library, rows, (sort, newFolders), (albums, members) ->
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
                    AlbumEntry.Source(folder, list.size, list.take(4), list.maxOf { it.added }, rows[folder.key]?.pinnedAt, folder.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } + userAlbums(albums.orEmpty(), members.orEmpty().groupBy { it.albumId }, items.filter { !it.trashed }.associateBy { it.id }, sort, Collator.getInstance()),
                sort = sort,
                screenshotsHidden = app.index.items.value.orEmpty().asSequence().map { it.folder }.filter { TimelineFolders.isScreenshots(it.path) }.distinct().let { folders ->
                    val list = folders.toList()
                    list.isNotEmpty() && list.all { SourceStore.stateOf(it, rows, newFolders) == SourceState.HIDDEN }
                },
            )
        }
    }
        .combine(app.preferences.placeNames.flatMapLatest { on -> if (on) app.placeGroups.map { it?.townCount ?: 0 } else flowOf(null) }) { state, towns -> state.copy(places = towns) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumsState())

    fun recheck() = app.index.recheck()

    fun setSort(sort: String) {
        viewModelScope.launch { app.preferences.setAlbumSort(sort) }
    }

    /** A new, empty user album (SPEC-albums §9.6, D13: the picker comes with S5); its id. */
    suspend fun create(name: String): Long = app.albums.create(name, emptyList(), System.currentTimeMillis())

    /** Pin or unpin these albums (SPEC-albums §9.5): pinned in the order selected. */
    fun pin(entries: List<AlbumEntry>, pinned: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            entries.forEachIndexed { i, entry ->
                val at = if (pinned) entry.pinnedAt ?: (now + i) else null
                when (entry) {
                    is AlbumEntry.Source -> app.sources.setPinned(entry.folder, at)
                    is AlbumEntry.User -> app.albums.setPinned(entry.id, at)
                }
            }
        }
    }

    /** Delete user albums (the sets only): what they were, for Undo. */
    suspend fun delete(ids: List<Long>) = app.albums.delete(ids)

    fun putBack(deleted: Pair<List<AlbumRow>, List<AlbumItemRow>>) {
        app.appScope.launch { app.albums.putBack(deleted.first, deleted.second) }
    }

    /** Hide sources' folders (SPEC-albums §9.5): the rows before, for Undo. */
    suspend fun hide(folders: List<FolderKey>) = app.sources.choose(folders, SourceState.HIDDEN, System.currentTimeMillis())

    fun undoHide(before: Map<String, SourceRow?>) {
        app.appScope.launch { app.sources.undo(before) }
    }
}

/** What a grid page shows (SPEC §9.2, SPEC-albums §9.10): a collection, or a source's album. */
sealed interface PageKey {
    data class Collection(val kind: CollectionKind) : PageKey

    data class Folder(val folder: FolderKey) : PageKey

    data class User(val id: Long) : PageKey

    /** A town's items (SPEC §13.1): an album-like page without the album's actions. */
    data class Town(val settlement: Int) : PageKey

    /** The items without a location (SPEC §13.1). */
    data object NoLocation : PageKey
}

/** A page's order (SPEC-albums §9.7, §9.10). */
enum class PageSort {
    NEWEST,
    OLDEST,

    /** A plain grid in name order, without day headers. */
    FILE_NAME,

    /** A user album's order the items were added, newest added last; no day headers (SPEC-albums §9.7). */
    ORDER_ADDED;

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
     * @param state Their state, for the chip (null for Favorites, Videos and user albums)
     * @param name A user album's name (null for the others)
     */
    data class Shown(
        val days: Timeline,
        val months: Timeline,
        val sort: PageSort,
        val folders: List<FolderKey>,
        val state: SourceState?,
        val pinned: Boolean,
        val count: Int,
        val name: String? = null,
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

    private val choices = combine(app.sources.rows, app.preferences.newFoldersInPhotos, app.preferences.collectionsOldestFirst, app.albums.albums, app.albums.members) { rows, newFolders, oldest, albums, members ->
        Choices(rows, newFolders, oldest, albums, members)
    }

    // Only a place's page follows the places, so the others aren't built again for every fact read
    private val groups = key.flatMapLatest { page -> if (page is PageKey.Town || page is PageKey.NoLocation) app.placeGroups.map { Placed(it) } else flowOf(Placed(null)) }

    val state: StateFlow<PageState> = combine(key.filterNotNull(), app.library, app.index.items, choices, combine(offsets, groups, ::Pair)) { key, library, all, choices, (offsets, groups) ->
        Input(key, library, all, choices, offsets, groups.groups)
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
        var name: String? = null
        when (val key = input.key) {
            is PageKey.User -> {
                val album = input.choices.albums?.firstOrNull { it.id == key.id } ?: return if (input.choices.albums == null) PageState.Loading else PageState.Gone
                val visible = library.visible.filter { !it.trashed }.associateBy { it.id }
                val rowsOf = input.choices.members.orEmpty().filter { it.albumId == key.id }
                sort = PageSort.of(album.sort)
                // In the order added: newest added last (SPEC-albums §9.7)
                shown = if (sort == PageSort.ORDER_ADDED) rowsOf.sortedBy { it.addedAt }.mapNotNull { visible[it.mediaId] } else rowsOf.mapNotNull { visible[it.mediaId] }
                folders = emptyList()
                state = null
                pinned = album.pinnedAt != null
                name = album.name
            }
            is PageKey.Collection -> {
                shown = library.visible.filter(key.kind::holds)
                sort = if (key.kind.name in input.choices.oldest) PageSort.OLDEST else PageSort.NEWEST
                // Every screenshot folder on the phone, hidden ones too: the chip sets them all (SPEC-albums §9.11)
                folders = if (key.kind == CollectionKind.SCREENSHOTS) input.all.orEmpty().mapTo(LinkedHashSet()) { it.folder }.filter { TimelineFolders.isScreenshots(it.path) } else emptyList()
                state = folders.firstOrNull()?.let { SourceStore.stateOf(it, rows, newFolders) }
            }
            is PageKey.Town -> {
                val groups = input.groups ?: return PageState.Loading
                shown = groups.town(key.settlement)?.items ?: return PageState.Gone
                folders = emptyList()
                state = null
                sort = PageSort.NEWEST
            }
            PageKey.NoLocation -> {
                val groups = input.groups ?: return PageState.Loading
                shown = groups.noLocation
                if (shown.isEmpty()) return PageState.Gone
                folders = emptyList()
                state = null
                sort = PageSort.NEWEST
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
        val (days, months) = if (sort == PageSort.FILE_NAME || sort == PageSort.ORDER_ADDED) {
            val ordered = if (sort == PageSort.FILE_NAME) shown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) else shown
            val plain = Timeline.plain(ordered, zone)
            plain to plain
        } else {
            val oldest = sort == PageSort.OLDEST
            Timeline.build(shown, months = false, zone, oldest) { input.offsets[it] } to Timeline.build(shown, months = true, zone, oldest) { input.offsets[it] }
        }
        return PageState.Shown(days, months, sort, folders, state, pinned, shown.size, name)
    }

    fun setStep(step: GridStep) {
        viewModelScope.launch { app.preferences.setGridStep(step) }
    }

    fun setSort(sort: PageSort) {
        when (val page = key.value) {
            is PageKey.Collection -> viewModelScope.launch { app.preferences.setCollectionOldestFirst(page.kind.name, sort == PageSort.OLDEST) }
            is PageKey.Folder -> viewModelScope.launch { app.sources.setSort(page.folder, sort.name.takeIf { sort != PageSort.NEWEST }) }
            is PageKey.User -> viewModelScope.launch { app.albums.setSort(page.id, sort.name.takeIf { sort != PageSort.NEWEST }) }
            is PageKey.Town, PageKey.NoLocation, null -> {}
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

    fun setPinned(album: Long, pinned: Boolean) {
        viewModelScope.launch { app.albums.setPinned(album, if (pinned) System.currentTimeMillis() else null) }
    }

    fun rename(album: Long, name: String) {
        viewModelScope.launch { app.albums.rename(album, name) }
    }

    /** Delete album (SPEC-albums §9.7): the set only; what it was, for Undo. */
    suspend fun delete(album: Long) = app.albums.delete(listOf(album))

    /** Remove from album (SPEC-albums §9.8): the rows taken away, for Undo. */
    suspend fun remove(album: Long, ids: Collection<Long>) = app.albums.remove(album, ids)

    fun putBack(albums: List<AlbumRow>, items: List<AlbumItemRow>) {
        app.appScope.launch { app.albums.putBack(albums, items) }
    }

    private data class Choices(val rows: Map<String, SourceRow>?, val newFolders: Boolean, val oldest: Set<String>, val albums: List<AlbumRow>?, val members: List<AlbumItemRow>?)

    private data class Input(val key: PageKey, val library: Library?, val all: List<MediaItem>?, val choices: Choices, val offsets: Map<Long, Int?>, val groups: PlaceGroups?)

    /** Place groups in a box: a page of no place gets an empty one at once. */
    private class Placed(val groups: PlaceGroups?)
}

/** Trash (SPEC §11): its items, the newest trashed first; null while the index loads. */
class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val items: StateFlow<List<MediaItem>?> = app.library
        .map { library -> library?.visible?.let(TrashOrder::of) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

