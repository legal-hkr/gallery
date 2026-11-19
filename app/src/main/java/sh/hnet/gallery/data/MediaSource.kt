package sh.hnet.gallery.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import sh.hnet.gallery.model.MediaItem

/** How much of the media the app may see (SPEC §4.7): all, only what the user selected (Android 14+), or nothing. */
enum class Access { FULL, PARTIAL, NONE }

/** A volume's identity and how far its changes have got, for refreshing by generation (LOGIKA §2.2). */
data class VolumeState(val version: String, val generation: Long)

/** MediaStore as the app reads it (LOGIKA §2.1): one query of photos and videos on every volume, trashed included. */
class MediaSource(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun access(): Access = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }

    /** Whether locations may be read from the files (SPEC §19.1); without it the system cuts them out. */
    fun canLocate(): Boolean = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Every external volume (the phone's storage, SD cards, USB drives) with its version and generation now. */
    fun volumes(): Map<String, VolumeState> = MediaStore.getExternalVolumeNames(context).associateWith { volume ->
        VolumeState(MediaStore.getVersion(context, volume), MediaStore.getGeneration(context, volume))
    }

    /**
     * Photos and videos, trashed included (Trash and Undo read the same list).
     *
     * @param limit The newest ones only, for the first screen (LOGIKA §2.1)
     * @param volume One volume, or all of them
     * @param since Only rows changed after this generation of [volume]
     */
    fun items(limit: Int? = null, volume: String = MediaStore.VOLUME_EXTERNAL, since: Long? = null): List<MediaItem> {
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
            if (since != null) append(" AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?")
        }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (since != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            if (limit != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
        }
        val own = context.packageName
        return resolver.query(MediaStore.Files.getContentUri(volume), PROJECTION, args, null)?.use { cursor ->
            val list = ArrayList<MediaItem>(cursor.count)
            val c = Columns(cursor)
            while (cursor.moveToNext()) list.add(c.item(cursor, own))
            list
        }.orEmpty()
    }

    /** Every id MediaStore still has (one column only, cheap): what is missing from it was deleted (LOGIKA §2.2). */
    fun ids(): Set<Long> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { cursor ->
            val ids = HashSet<Long>(cursor.count * 2)
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            ids
        }.orEmpty()
    }

    /** A signal for every change MediaStore announces, on any volume (SPEC §22.3). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.AUTHORITY_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /** The cursor's column indexes, looked up once per query. */
    private class Columns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val type = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val bucket = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val volume = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
        val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val favorite = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
        val trashed = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        val expires = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
        val owner = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val generation = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)

        fun item(cursor: Cursor, own: String): MediaItem {
            val takenMs = if (cursor.isNull(taken)) 0L else cursor.getLong(taken)
            val modifiedS = if (cursor.isNull(modified)) 0L else cursor.getLong(modified)
            return MediaItem(
                id = cursor.getLong(id),
                video = cursor.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                mime = cursor.getString(mime).orEmpty(),
                // No date taken (screenshots and other images without EXIF): the file's own time, which a copy from a computer or a download keeps; only then when MediaStore learned of it (owner's test of S1a, 19 Nov 2026: copied screenshots stood under Today)
                taken = when {
                    takenMs > 0 -> takenMs
                    modifiedS > 0 -> modifiedS * 1000
                    else -> cursor.getLong(added) * 1000
                },
                name = cursor.getString(name).orEmpty(),
                bucketId = cursor.getLong(bucket),
                path = cursor.getString(path).orEmpty(),
                volume = cursor.getString(volume).orEmpty(),
                size = cursor.getLong(size),
                width = cursor.getInt(width),
                height = cursor.getInt(height),
                duration = if (cursor.isNull(duration)) 0L else cursor.getLong(duration),
                favorite = cursor.getInt(favorite) != 0,
                trashed = cursor.getInt(trashed) != 0,
                expires = if (cursor.isNull(expires)) 0L else cursor.getLong(expires) * 1000,
                ours = cursor.getString(owner) == own,
                generation = cursor.getLong(generation),
                added = cursor.getLong(added) * 1000,
            )
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
    }
}
