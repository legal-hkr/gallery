package sh.hnet.gallery

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import sh.hnet.gallery.data.AlbumStore
import sh.hnet.gallery.data.MediaIndex
import sh.hnet.gallery.data.MediaSource
import sh.hnet.gallery.data.Places
import sh.hnet.gallery.data.ShareMaker
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.facts.FactReader
import sh.hnet.gallery.facts.FactScanner
import sh.hnet.gallery.facts.GalleryDatabase
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.Place
import sh.hnet.gallery.model.PlaceData
import sh.hnet.gallery.model.PlaceGroups
import sh.hnet.gallery.ui.components.Thumbnails
import sh.hnet.gallery.ui.viewer.TileCache
import sh.hnet.gallery.widget.Widgets

class GalleryApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val media: MediaSource by lazy { MediaSource(this) }

    /** Every photo and video, kept up to date while the process lives (LOGIKA §2.1). */
    val index: MediaIndex by lazy { MediaIndex(media, appScope) }

    private val database: GalleryDatabase by lazy { GalleryDatabase.create(this) }

    /** What the files say beyond MediaStore, read while the app runs (LOGIKA §2.3). */
    // The scanner takes the place data only once it is known: a null before the file is mapped would clear every place at each start
    val facts: FactScanner by lazy { FactScanner(database.factDao(), FactReader(contentResolver), appScope, media::canLocate, placesKnown) }

    /** The place data in the app, unpacked and mapped once (LOGIKA §17.3); null in a build without it. */
    private val placeData: PlaceData? by lazy { Places.load(this) }

    /** The place data while place names are on, null while off – each value once it is known. */
    private val placesKnown: Flow<PlaceData?> by lazy { preferences.placeNames.map { on -> if (on) placeData else null }.flowOn(Dispatchers.IO) }

    /** The place data while place names are on (SPEC §20.5); null while off, and until the file is mapped. */
    val places: StateFlow<PlaceData?> by lazy { placesKnown.stateIn(appScope, SharingStarted.Eagerly, null) }

    /**
     * Places (SPEC §13.1): the library's items by country, region and town, and those without a location; null while
     * place names are off or the data, the library or the facts aren't there yet. Built only while something shows it.
     */
    val placeGroups: StateFlow<PlaceGroups?> by lazy {
        combine(library, facts.facts, places) { library, facts, data ->
            if (library == null || facts == null || data == null) null
            else PlaceGroups.of(library.visible) { id ->
                val fact = facts[id]
                when {
                    fact?.latitude == null -> null
                    // Located, but placed by other data or not yet: in no group until it is
                    fact.places != data.version -> Place.NONE
                    else -> Place(fact.settlement ?: -1, fact.region ?: -1, fact.country ?: -1)
                }
            }
        }.flowOn(Dispatchers.Default).stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)
    }

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

    /** User albums (SPEC-albums §9.12). */
    val albums: AlbumStore by lazy { AlbumStore(database.albumDao(), appScope) }

    /** The share sheet's location action (SPEC §14.2), for the shell to share again; kept for a shell not composed yet. */
    val shareAgain = MutableStateFlow<ShareAgain?>(null)

    /**
     * The same, for a share from an outside viewer (a task of its own): that viewer shares again, not the main activity –
     * the one whose token it carries (review after v0.1.1: any viewer, a later one too, took it).
     */
    val shareAgainOutside = MutableStateFlow<Pair<String, ShareAgain>?>(null)

    /** A folder's album to open (from the outside viewer), as a [sh.hnet.gallery.model.FolderKey.key]. */
    val openFolder = MutableStateFlow<String?>(null)

    /** Photos to open in the viewer (a widget's tap, S11): their ids and the one to start at. */
    val openPhotos = MutableStateFlow<Pair<List<Long>, Long>?>(null)

    /** Some items' offsets from their facts (the widgets' few photos), without loading every fact. */
    suspend fun offsetsOf(ids: List<Long>): Map<Long, Int?> =
        if (ids.isEmpty()) emptyMap() else ids.chunked(500).flatMap { database.factDao().of(it) }.associate { it.id to it.offsetMinutes }

    /** Shares without location (SPEC §14, LOGIKA §16). */
    val sharing: ShareMaker by lazy { ShareMaker(this) }

    /** Backup and restore of the settings (SPEC §20.2). */
    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("stamps", MODE_PRIVATE)) }

    /** The vault (S8b): locked by default, open after the prompt until it locks. */
    val vault: Vault by lazy { Vault(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        Thumbnails.budget(getSystemService(ActivityManager::class.java).memoryClass)
        // Locked: what the viewer decrypted for the screen goes from memory too (SPEC §18.4)
        appScope.launch {
            vault.state.collect {
                if (it !is Vault.State.Open) {
                    Thumbnails.forgetOutside()
                    TileCache.forgetOutside()
                }
            }
        }
        appScope.launch { preferences.vaultLockDelay.collect { vault.lockDelayMs = it.ms } }
        // The screen turning off always locks the vault, whatever the delay (D31)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = vault.lock()
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        // The copies made for shares of earlier starts go (SPEC §14.1)
        appScope.launch(Dispatchers.IO) { sharing.clearOld() }
        // A widget placed before an update of the app, or access given or taken while it was closed (as Contacts')
        Widgets.update(this)
    }

    private val libraryStarted = AtomicBoolean(false)

    /**
     * The index and what follows it – facts read from new files, album members relinked, new folders classified –,
     * started by the app's screens (each activity's `onCreate`), never by a widget's job or receiver waking the process:
     * with the app closed nothing is read (D4; review of S11: every widget redraw read the whole index and started the
     * facts). Once per process.
     */
    fun startLibrary() {
        if (!libraryStarted.compareAndSet(false, true)) return
        // Every new list of items is checked for files not read yet
        appScope.launch { index.complete.filterNotNull().collect { facts.update(it) } }
        // Album members whose ids a rescan changed are found again by fingerprint (SPEC-albums §9.12)
        appScope.launch { index.complete.filterNotNull().collect { albums.relink(it) } }
        // Every folder the index shows has a state: new ones are classified once (SPEC-albums §22.2)
        appScope.launch {
            index.items.filterNotNull().map { items -> items.mapTo(HashSet()) { it.folder } }.distinctUntilChanged().collect { folders ->
                val old = preferences.takeOldTimelineChoice().orEmpty()
                sources.sync(folders, preferences.newFoldersInPhotos.first(), System.currentTimeMillis(), old)
            }
        }
    }
}

/** Share these items again, with or without their location (SPEC §14.2). */
data class ShareAgain(val ids: List<Long>, val removeLocation: Boolean)

