package sh.hnet.gallery.data

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.gallery.model.MediaItem

/**
 * Changes to items in MediaStore (LOGIKA §4, SPEC §22.4). The app's own files are changed at once;
 * any other app's go through the system's request, which shows its dialog after the user's action
 * – or answers at once when the app has Media management. One request covers the whole action.
 */
class MediaWriter(private val context: Context) {
    private val resolver = context.contentResolver

    /** What a change needs before it is done. */
    sealed interface Change {
        /** Done already (only the app's own files). */
        data object Done : Change

        /** The system's request: launch it; the change is done when it returns OK, nothing changes otherwise. */
        class Ask(val request: PendingIntent) : Change

        data class Failed(val details: String) : Change
    }

    /** Moves the items to Trash, or back from it (SPEC §5.5, §6.4). */
    suspend fun trash(items: List<MediaItem>, trashed: Boolean): Change = change(items, { MediaStore.createTrashRequest(resolver, it, trashed) }) {
        put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
    }

    /** Marks the items favorite or not (SPEC §6.5). */
    suspend fun favorite(items: List<MediaItem>, favorite: Boolean): Change = change(items, { MediaStore.createFavoriteRequest(resolver, it, favorite) }) {
        put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
    }

    /** The right to write the items' files (the date taken, SPEC §7.2): null when the app may already. */
    fun writeRequest(items: List<MediaItem>): PendingIntent? =
        if (items.all { it.ours }) null else MediaStore.createWriteRequest(resolver, items.map { it.uri() })

    private suspend fun change(items: List<MediaItem>, request: (List<Uri>) -> PendingIntent, values: ContentValues.() -> Unit): Change =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext Change.Done
            if (items.all { it.ours }) {
                try {
                    val content = ContentValues().apply(values)
                    for (item in items) resolver.update(item.uri(), content, null)
                    Change.Done
                } catch (e: Exception) {
                    Change.Failed(e.javaClass.simpleName)
                }
            } else {
                try {
                    Change.Ask(request(items.map { it.uri() }))
                } catch (e: Exception) {
                    Change.Failed(e.javaClass.simpleName)
                }
            }
        }
}
