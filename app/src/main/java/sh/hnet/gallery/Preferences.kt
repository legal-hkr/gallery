package sh.hnet.gallery

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }

    private companion object {
        val GRID_STEP = stringPreferencesKey("grid_step")
        val TIMELINE_ON = stringSetPreferencesKey("timeline_on")
        val TIMELINE_OFF = stringSetPreferencesKey("timeline_off")
        val NEW_ALBUMS_IN_PHOTOS = booleanPreferencesKey("new_albums_in_photos")
    }
}
