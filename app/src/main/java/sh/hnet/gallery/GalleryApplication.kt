package sh.hnet.gallery

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.gallery.backup.BackupManager
import sh.hnet.gallery.data.MediaIndex
import sh.hnet.gallery.data.MediaSource
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.facts.FactReader
import sh.hnet.gallery.facts.FactScanner
import sh.hnet.gallery.facts.GalleryDatabase
import sh.hnet.gallery.model.Library

class GalleryApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val media: MediaSource by lazy { MediaSource(this) }

    /** Every photo and video, kept up to date while the process lives (LOGIKA §2.1). */
    val index: MediaIndex by lazy { MediaIndex(media, appScope) }

    private val database: GalleryDatabase by lazy { GalleryDatabase.create(this) }

    /** What the files say beyond MediaStore, read while the app runs (LOGIKA §2.3). */
    val facts: FactScanner by lazy { FactScanner(database.factDao(), FactReader(contentResolver), appScope, media::canLocate) }

    /** The sources' states (SPEC-albums §9.1). */
    val sources: SourceStore by lazy { SourceStore(database.sourceDao(), appScope) }

    /**
     * What the app shows of the index (SPEC-albums §9.1): hidden sources and album covers left out, the
     * timeline the sources In Photos; null until the index and the states are read.
     */
    val library: StateFlow<Library?> by lazy {
        combine(index.items, sources.rows, preferences.newFoldersInPhotos) { items, rows, newFolders ->
            if (items == null || rows == null) null else Library.of(items) { SourceStore.stateOf(it, rows, newFolders) }
        }.flowOn(Dispatchers.Default).stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /** Backup and restore of the settings (SPEC §20.2). */
    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("stamps", MODE_PRIVATE)) }

    override fun onCreate() {
        super.onCreate()
        // Every new list of items is checked for files not read yet
        appScope.launch { index.items.filterNotNull().collect { facts.update(it) } }
        // Every folder the index shows has a state: new ones are classified once (SPEC-albums §22.2)
        appScope.launch {
            index.items.filterNotNull().map { items -> items.mapTo(HashSet()) { it.folder } }.distinctUntilChanged().collect { folders ->
                val old = preferences.takeOldTimelineChoice().orEmpty()
                sources.sync(folders, preferences.newFoldersInPhotos.first(), System.currentTimeMillis(), old)
            }
        }
    }
}
