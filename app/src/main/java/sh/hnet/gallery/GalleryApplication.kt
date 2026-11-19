package sh.hnet.gallery

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import sh.hnet.gallery.data.MediaIndex
import sh.hnet.gallery.data.MediaSource
import sh.hnet.gallery.facts.FactReader
import sh.hnet.gallery.facts.FactScanner
import sh.hnet.gallery.facts.GalleryDatabase

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

    override fun onCreate() {
        super.onCreate()
        // Every new list of items is checked for files not read yet
        appScope.launch { index.items.filterNotNull().collect { facts.update(it) } }
    }
}
