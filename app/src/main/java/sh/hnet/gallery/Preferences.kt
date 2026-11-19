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
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.TimelineFolders

private val Context.settings by preferencesDataStore("settings")

/** The folders the timeline shows besides or instead of its defaults (LOGIKA §2.5), as [FolderKey.key]s. */
data class TimelineChoice(val on: Set<String> = emptySet(), val off: Set<String> = emptySet()) {
    fun shown(folder: FolderKey): Boolean = TimelineFolders.shown(folder, on, off)
}

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). */
class Preferences(private val context: Context) {
    /** The grid's step, one for every grid (SPEC §4.4). */
    val gridStep: Flow<GridStep> = context.settings.data.map { GridStep.of(it[GRID_STEP]) }.distinctUntilChanged()

    suspend fun setGridStep(step: GridStep) = context.settings.edit { it[GRID_STEP] = step.name }

    /** The albums in Photos (Settings › Photos, SPEC §20.3). */
    val timeline: Flow<TimelineChoice> = context.settings.data.map { TimelineChoice(it[TIMELINE_ON].orEmpty(), it[TIMELINE_OFF].orEmpty()) }.distinctUntilChanged()

    /** Shows or hides a folder's items in the timeline; a default folder is remembered only when switched off, any other only when on. */
    suspend fun setInTimeline(folder: FolderKey, shown: Boolean) = context.settings.edit {
        val key = folder.key
        val on = it[TIMELINE_ON].orEmpty()
        val off = it[TIMELINE_OFF].orEmpty()
        val default = TimelineFolders.isDefault(folder.path)
        it[TIMELINE_ON] = if (shown && !default) on + key else on - key
        it[TIMELINE_OFF] = if (!shown && default) off + key else off - key
    }

    /** Albums created in the app start in Photos (SPEC §20.3, on by default). */
    val newAlbumsInPhotos: Flow<Boolean> = context.settings.data.map { it[NEW_ALBUMS_IN_PHOTOS] ?: true }.distinctUntilChanged()

    suspend fun setNewAlbumsInPhotos(on: Boolean) = context.settings.edit { it[NEW_ALBUMS_IN_PHOTOS] = on }

    /** A motion photo plays once when shown (Settings › Viewer, SPEC §20.4; on by default). */
    val autoplayMotion: Flow<Boolean> = context.settings.data.map { it[AUTOPLAY_MOTION] ?: true }.distinctUntilChanged()

    suspend fun setAutoplayMotion(on: Boolean) = context.settings.edit { it[AUTOPLAY_MOTION] = on }

    /** How many of the two offers of Media management were shown (SPEC §10.6): the wizard's, then one after the first system dialog. */
    val mediaManagementOffers: Flow<Int> = context.settings.data.map { it[MEDIA_MANAGEMENT_OFFERS] ?: 0 }.distinctUntilChanged()

    suspend fun markMediaManagementOffered() = context.settings.edit { it[MEDIA_MANAGEMENT_OFFERS] = ((it[MEDIA_MANAGEMENT_OFFERS] ?: 0) + 1).coerceAtMost(2) }

    /** Every setting as the backup holds it (SPEC §20.2). */
    suspend fun snapshot(): BackupSettings {
        val p = context.settings.data.first()
        return BackupSettings(
            gridStep = GridStep.of(p[GRID_STEP]).name,
            timelineOn = p[TIMELINE_ON].orEmpty().sorted(),
            timelineOff = p[TIMELINE_OFF].orEmpty().sorted(),
            newAlbumsInPhotos = p[NEW_ALBUMS_IN_PHOTOS] ?: true,
            mediaManagementOffers = p[MEDIA_MANAGEMENT_OFFERS] ?: 0,
            autoplayMotion = p[AUTOPLAY_MOTION] ?: true,
        )
    }

    /** A backup's settings: each one the file holds replaces the current value; one it lacks stays as it is (family rule). */
    suspend fun restore(backup: BackupSettings) = context.settings.edit { p ->
        backup.gridStep?.let { p[GRID_STEP] = GridStep.of(it).name }
        backup.timelineOn?.let { p[TIMELINE_ON] = it.filter { key -> FolderKey.of(key) != null }.toSet() }
        backup.timelineOff?.let { p[TIMELINE_OFF] = it.filter { key -> FolderKey.of(key) != null }.toSet() }
        backup.newAlbumsInPhotos?.let { p[NEW_ALBUMS_IN_PHOTOS] = it }
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
        val MEDIA_MANAGEMENT_OFFERS = intPreferencesKey("media_management_offers")
        val AUTOPLAY_MOTION = booleanPreferencesKey("autoplay_motion")
    }
}
