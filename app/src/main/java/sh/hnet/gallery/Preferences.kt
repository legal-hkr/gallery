package sh.hnet.gallery

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.hnet.gallery.backup.BackupSettings
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.SourceRules
import sh.hnet.gallery.model.SourceState

private val Context.settings by preferencesDataStore("settings")

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). */
class Preferences(private val context: Context) {
    /** The grid's step, one for every grid (SPEC §4.4). */
    val gridStep: Flow<GridStep> = context.settings.data.map { GridStep.of(it[GRID_STEP]) }.distinctUntilChanged()

    suspend fun setGridStep(step: GridStep) = context.settings.edit { it[GRID_STEP] = step.name }

    /**
     * The timeline's choice of S1–S4a (folders switched on or off), taken over once by the sources' states
     * (LOGIKA §15.2) and removed: null when there is none left.
     */
    suspend fun takeOldTimelineChoice(): Map<String, SourceState>? {
        var taken: Map<String, SourceState>? = null
        context.settings.edit {
            val on = it[TIMELINE_ON].orEmpty()
            val off = it[TIMELINE_OFF].orEmpty()
            if (on.isNotEmpty() || off.isNotEmpty()) taken = SourceRules.fromTimelineChoice(on, off)
            it.remove(TIMELINE_ON)
            it.remove(TIMELINE_OFF)
            it.remove(NEW_ALBUMS_IN_PHOTOS)
        }
        return taken
    }

    /** Unknown new folders go In Photos instead of Album only (Settings › Photos, SPEC-albums §20.3; off by default). */
    val newFoldersInPhotos: Flow<Boolean> = context.settings.data.map { it[NEW_FOLDERS_IN_PHOTOS] ?: false }.distinctUntilChanged()

    suspend fun setNewFoldersInPhotos(on: Boolean) = context.settings.edit { it[NEW_FOLDERS_IN_PHOTOS] = on }

    /** A motion photo plays once when shown (Settings › Viewer, SPEC §20.4; on by default). */
    val autoplayMotion: Flow<Boolean> = context.settings.data.map { it[AUTOPLAY_MOTION] ?: true }.distinctUntilChanged()

    suspend fun setAutoplayMotion(on: Boolean) = context.settings.edit { it[AUTOPLAY_MOTION] = on }

    /** The collections shown oldest first (their sort chip, remembered per collection; LOGIKA §15.11), by [CollectionKind] name. */
    val collectionsOldestFirst: Flow<Set<String>> = context.settings.data.map { it[COLLECTIONS_OLDEST_FIRST].orEmpty() }.distinctUntilChanged()

    suspend fun setCollectionOldestFirst(kind: String, oldest: Boolean) = context.settings.edit {
        val now = it[COLLECTIONS_OLDEST_FIRST].orEmpty()
        it[COLLECTIONS_OLDEST_FIRST] = if (oldest) now + kind else now - kind
    }

    /** The Albums tab's order (SPEC-albums §9.4): `RECENT` (default), `NAME`, `COUNT`. */
    val albumSort: Flow<String> = context.settings.data.map { it[ALBUM_SORT] ?: "RECENT" }.distinctUntilChanged()

    suspend fun setAlbumSort(sort: String) = context.settings.edit { it[ALBUM_SORT] = sort }

    /** How many of the two offers of Media management were shown (SPEC §10.6): the wizard's, then one after the first system dialog. */
    val mediaManagementOffers: Flow<Int> = context.settings.data.map { it[MEDIA_MANAGEMENT_OFFERS] ?: 0 }.distinctUntilChanged()

    suspend fun markMediaManagementOffered() = context.settings.edit { it[MEDIA_MANAGEMENT_OFFERS] = ((it[MEDIA_MANAGEMENT_OFFERS] ?: 0) + 1).coerceAtMost(2) }

    /** The last offer was answered (SPEC §10.6): none ever again, also when the wizard's was skipped. */
    suspend fun endMediaManagementOffers() = context.settings.edit { it[MEDIA_MANAGEMENT_OFFERS] = 2 }

    /** Every setting as the backup holds it (SPEC §20.2). */
    suspend fun snapshot(): BackupSettings {
        val p = context.settings.data.first()
        return BackupSettings(
            gridStep = GridStep.of(p[GRID_STEP]).name,
            newFoldersInPhotos = p[NEW_FOLDERS_IN_PHOTOS] ?: false,
            albumSort = p[ALBUM_SORT] ?: "RECENT",
            collectionsOldestFirst = p[COLLECTIONS_OLDEST_FIRST].orEmpty().sorted(),
            mediaManagementOffers = p[MEDIA_MANAGEMENT_OFFERS] ?: 0,
            autoplayMotion = p[AUTOPLAY_MOTION] ?: true,
        )
    }

    /** A backup's settings: each one the file holds replaces the current value; one it lacks stays as it is (family rule). */
    suspend fun restore(backup: BackupSettings) = context.settings.edit { p ->
        backup.gridStep?.let { p[GRID_STEP] = GridStep.of(it).name }
        // The timeline's choice of older backups goes to the sources' states (BackupManager); the old switch meant something else
        backup.newFoldersInPhotos?.let { p[NEW_FOLDERS_IN_PHOTOS] = it }
        backup.albumSort?.takeIf { it in setOf("RECENT", "NAME", "COUNT") }?.let { p[ALBUM_SORT] = it }
        backup.collectionsOldestFirst?.let { p[COLLECTIONS_OLDEST_FIRST] = it.toSet() }
        backup.mediaManagementOffers?.let { p[MEDIA_MANAGEMENT_OFFERS] = it.coerceIn(0, 2) }
        backup.autoplayMotion?.let { p[AUTOPLAY_MOTION] = it }
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }

    private companion object {
        val GRID_STEP = stringPreferencesKey("grid_step")
        val TIMELINE_ON = stringSetPreferencesKey("timeline_on")
        val TIMELINE_OFF = stringSetPreferencesKey("timeline_off")
        val NEW_ALBUMS_IN_PHOTOS = booleanPreferencesKey("new_albums_in_photos")
        val NEW_FOLDERS_IN_PHOTOS = booleanPreferencesKey("new_folders_in_photos")
        val ALBUM_SORT = stringPreferencesKey("album_sort")
        val MEDIA_MANAGEMENT_OFFERS = intPreferencesKey("media_management_offers")
        val AUTOPLAY_MOTION = booleanPreferencesKey("autoplay_motion")
        val COLLECTIONS_OLDEST_FIRST = stringSetPreferencesKey("collections_oldest_first")
    }
}
