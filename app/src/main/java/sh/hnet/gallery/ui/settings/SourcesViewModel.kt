package sh.hnet.gallery.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.facts.SourceRow
import sh.hnet.gallery.model.CoverNames
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.SourceState
import sh.hnet.gallery.model.TimelineFolders

/**
 * One row of Settings › Photos (SPEC-albums §20.3): a folder, or the screenshot folders as one.
 *
 * @param newest Its four newest items, for the mosaic
 */
data class SourceEntry(val folders: List<FolderKey>, val screenshots: Boolean, val count: Int, val newest: List<MediaItem>, val state: SourceState)

/** Every folder with photos or videos, in its state's group, the most items first. */
data class SourcesState(val loaded: Boolean = false, val entries: List<SourceEntry> = emptyList(), val newFoldersInPhotos: Boolean = false)

class SourcesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val state: StateFlow<SourcesState> = combine(app.index.items, app.sources.rows, app.preferences.newFoldersInPhotos) { items, rows, newFolders ->
        if (items == null || rows == null) SourcesState(newFoldersInPhotos = newFolders) else SourcesState(true, entries(items, rows, newFolders), newFolders)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesState())

    fun setNewFoldersInPhotos(on: Boolean) {
        viewModelScope.launch { app.preferences.setNewFoldersInPhotos(on) }
    }

    /** The user's state for [entry]'s folders; the rows before, for Undo. */
    suspend fun choose(entry: SourceEntry, state: SourceState): Map<String, SourceRow?> = app.sources.choose(entry.folders, state, System.currentTimeMillis())

    fun undo(before: Map<String, SourceRow?>) {
        app.appScope.launch { app.sources.undo(before) }
    }

    private fun entries(items: List<MediaItem>, rows: Map<String, SourceRow>, newFolders: Boolean): List<SourceEntry> {
        // Items come newest first, so each folder's first items are its newest
        val byFolder = LinkedHashMap<FolderKey, MutableList<MediaItem>>()
        for (item in items) if (!item.trashed && !CoverNames.isCover(item.name)) byFolder.getOrPut(item.folder) { ArrayList() }.add(item)
        val entries = ArrayList<SourceEntry>()
        val screenshots = byFolder.filterKeys { TimelineFolders.isScreenshots(it.path) }
        if (screenshots.isNotEmpty()) {
            val all = screenshots.values.flatten().sortedWith(MediaItem.NEWEST_FIRST)
            entries += SourceEntry(screenshots.keys.toList(), true, all.size, all.take(4), SourceStore.stateOf(screenshots.keys.first(), rows, newFolders))
        }
        for ((folder, list) in byFolder) {
            if (TimelineFolders.isScreenshots(folder.path)) continue
            entries += SourceEntry(listOf(folder), false, list.size, list.take(4), SourceStore.stateOf(folder, rows, newFolders))
        }
        return entries.sortedByDescending { it.count }
    }
}
