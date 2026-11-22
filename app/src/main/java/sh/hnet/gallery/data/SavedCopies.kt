package sh.hnet.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import sh.hnet.gallery.model.MediaItem

/**
 * An edited copy into the media beside its original (SPEC §15.6): written while pending and shown once whole, with the
 * original's date taken. Where MediaStore doesn't take the type in the original's folder – Download, another app's
 * Android/media folder – into [fallback] (Pictures, Movies) instead (review before v0.1.0: every such copy failed).
 * Half a copy is no file: it goes. The new item's id, or null.
 *
 * @param collection The media's table for a volume (images, videos)
 */
internal fun ContentResolver.insertBeside(item: MediaItem, file: File, name: String, mime: String, collection: (String) -> Uri, fallback: String): Long? {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, item.path)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        put(MediaStore.MediaColumns.DATE_TAKEN, item.taken)
    }
    val target = try {
        insert(collection(item.volume), values)
    } catch (_: Exception) {
        null
    } ?: insert(collection(MediaStore.VOLUME_EXTERNAL_PRIMARY), values.apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$fallback/") }) ?: return null
    try {
        openOutputStream(target)?.use { to -> file.inputStream().use { it.copyTo(to, 256 * 1024) } } ?: error("No stream")
        update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null)
        return ContentUris.parseId(target)
    } catch (e: Exception) {
        try { delete(target, null) } catch (_: Exception) { }
        throw e
    }
}

/** An edited result written over its original, after the system's consent the caller asked for; its name stays. */
internal fun ContentResolver.writeOver(item: MediaItem, file: File) {
    openOutputStream(item.uri(), "wt")?.use { to -> file.inputStream().use { it.copyTo(to, 256 * 1024) } } ?: error("No stream")
}
