package sh.hnet.gallery.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.VaultEntry

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

    /** Deletes the items for good (SPEC §11.2): the app's own files at once, others' after the system's dialog. */
    suspend fun deleteForever(items: List<MediaItem>): Change = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Change.Done
        try {
            if (items.all { it.ours }) {
                for (item in items) resolver.delete(item.uri(), null)
                Change.Done
            } else {
                Change.Ask(MediaStore.createDeleteRequest(resolver, items.map { it.uri() }))
            }
        } catch (e: Exception) {
            Change.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * The other files' names in the item's folder (SPEC §10.3): the index's items of that folder ([known]) and
     * whatever else MediaStore shows the app there. Renaming over a name taken would replace that file on disk.
     */
    suspend fun namesBeside(item: MediaItem, known: List<MediaItem>): List<String> = withContext(Dispatchers.IO) {
        val names = ArrayList<String>()
        for (other in known) if (other.id != item.id && other.volume == item.volume && other.path == item.path) names.add(other.name)
        try {
            resolver.query(
                MediaStore.Files.getContentUri(item.volume),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns._ID} != ?",
                arrayOf(item.path, item.id.toString()),
                null,
            )?.use { cursor -> while (cursor.moveToNext()) cursor.getString(0)?.let(names::add) }
        } catch (_: Exception) {
            // Unknown: the rename itself fails if the name is taken after all
        }
        names
    }

    /** Gives the file a new name (SPEC §10.3); for another app's file only after [writeRequest]'s consent. True when done. */
    suspend fun rename(item: MediaItem, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.update(item.uri(), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A file from outside saved into `Download/` (SPEC-albums §17.2): a new item of the app's own, written while
     * pending and shown once whole; MediaStore numbers a name already there. The new item's id, or null.
     */
    suspend fun saveToDownloads(item: MediaItem): Long? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name.ifBlank { "file" })
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = try {
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        try {
            resolver.openInputStream(item.uri())?.use { from -> resolver.openOutputStream(target)?.use { to -> from.copyTo(to, 256 * 1024) } } ?: error("No stream")
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null)
            ContentUris.parseId(target)
        } catch (_: Exception) {
            // Half a copy is no file: it goes
            try { resolver.delete(target, null) } catch (_: Exception) { }
            null
        }
    }

    /**
     * A vault item back in the gallery (SPEC-albums §18.3): in the folder it came from, or in `Download/` when it was
     * imported, its folder is gone or MediaStore takes no such file there (another app's `Android/media` folder);
     * written while pending, its date taken kept, a name already there numbered by MediaStore. The new item's id, or null.
     */
    suspend fun saveFromVault(entry: VaultEntry, content: () -> InputStream): Long? = withContext(Dispatchers.IO) {
        fun values(path: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name.ifBlank { "file" })
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (entry.taken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, entry.taken)
        }
        fun insert(collection: Uri, path: String): Uri? = try {
            resolver.insert(collection, values(path))
        } catch (_: Exception) {
            null
        }
        val volume = entry.fromVolume
        val path = entry.fromPath
        val home = if (volume != null && path != null && folderExists(volume, path)) {
            val collection = when {
                entry.mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
                entry.mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume)
                else -> MediaStore.Files.getContentUri(volume)
            }
            insert(collection, path)
        } else {
            null
        }
        val target = home ?: insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_DOWNLOADS + "/") ?: return@withContext null
        try {
            content().use { from -> resolver.openOutputStream(target)?.use { to -> from.copyTo(to, 256 * 1024) } } ?: error("No stream")
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null)
            ContentUris.parseId(target)
        } catch (_: Exception) {
            // Half a copy is no file: it goes
            try { resolver.delete(target, null) } catch (_: Exception) { }
            null
        }
    }

    /** Whether a folder is still there on its volume (the volume mounted, the folder not deleted meanwhile). */
    private fun folderExists(volume: String, path: String): Boolean = try {
        val root = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull { it.mediaStoreVolumeName == volume }?.directory
        root != null && File(root, path).isDirectory
    } catch (_: Exception) {
        false
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
