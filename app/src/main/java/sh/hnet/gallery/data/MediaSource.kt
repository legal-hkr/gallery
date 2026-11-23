package sh.hnet.gallery.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.StoragePaths

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
    fun items(limit: Int? = null, volume: String = MediaStore.VOLUME_EXTERNAL, since: Long? = null, path: String? = null): List<MediaItem> {
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
            if (since != null) append(" AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?")
            if (path != null) append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
        }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            listOfNotNull(since?.toString(), path).takeIf { it.isNotEmpty() }?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it.toTypedArray()) }
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

    /**
     * Photos (not videos, not trashed) taken within any of [windows] (epoch milliseconds), on every volume: the On this
     * day widget's ask (S11), a few days a year rather than the whole index – the app closed, nothing else is read (D4).
     */
    fun photosTakenIn(windows: List<LongRange>): List<MediaItem> {
        if (windows.isEmpty()) return emptyList()
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND (" +
            windows.joinToString(" OR ") { "${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ?" } + ")"
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, windows.flatMap { listOf(it.first.toString(), it.last.toString()) }.toTypedArray())
        }
        return query(args)
    }

    /** The newest [limit] photos (not trashed): the widget's last fallback, any photo of the timeline. */
    fun newestPhotos(limit: Int): List<MediaItem> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return query(args)
    }

    private fun query(args: Bundle): List<MediaItem> = try {
        val own = context.packageName
        resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), PROJECTION, args, null)?.use { cursor ->
            val list = ArrayList<MediaItem>(cursor.count)
            val c = Columns(cursor)
            while (cursor.moveToNext()) list.add(c.item(cursor, own))
            list
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The MediaStore item an address from another app names (SPEC §17.1, §17.3), tried in turn: `content://media/…`
     * itself; a document – Downloads' `msf:<MediaStore id>` or `raw:<path>`, Files' and the media's own through
     * [MediaStore.getMediaUri]; a path in the address (a `file://` one, Files by Google's); last, the one item of the
     * same name and size. Null when none fits – an attachment, another app's file – or the app may not read it.
     * (Owner's test of S5b: a photo opened from Files came as "Outside Gallery".)
     */
    fun resolve(uri: Uri): MediaItem? {
        if (uri.authority == MediaStore.AUTHORITY) return mediaItem(uri)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val document = DocumentsContract.getDocumentId(uri)
            when {
                document.startsWith("msf:") -> document.removePrefix("msf:").toLongOrNull()?.let { item(it) }?.let { return it }
                document.startsWith("raw:") -> byPath(document.removePrefix("raw:"))?.let { return it }
            }
            try {
                MediaStore.getMediaUri(context, uri)?.let(::mediaItem)?.let { return it }
            } catch (_: Exception) {
                // Not one of the providers it knows
            }
        }
        val path = uri.path.orEmpty()
        val storage = path.indexOf("/storage/")
        if (uri.scheme == "file" || storage >= 0) byPath(if (storage >= 0) path.substring(storage) else path)?.let { return it }
        return bySameFile(uri)
    }

    private fun mediaItem(media: Uri): MediaItem? {
        val id = try {
            ContentUris.parseId(media)
        } catch (_: Exception) {
            return null
        }
        val volume = try {
            MediaStore.getVolumeName(media)
        } catch (_: Exception) {
            MediaStore.VOLUME_EXTERNAL
        }
        return item(id, volume)
    }

    /** A path on shared storage (`/storage/emulated/0/Download/a.jpg`, `/storage/1234-5678/…`) as its volume, folder and name. */
    private fun byPath(path: String): MediaItem? {
        val (volume, folder, name) = StoragePaths.split(path, MediaStore.VOLUME_EXTERNAL_PRIMARY) ?: return null
        return one("${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(folder, name), volume)
    }

    /** The one item whose name and size are those the address's provider gives; none when several fit. */
    private fun bySameFile(uri: Uri): MediaItem? {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        } catch (_: Exception) {
            return null
        }
        val named = name ?: return null
        if (size < 0) return null
        return one("${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?", arrayOf(named, size.toString()), MediaStore.VOLUME_EXTERNAL)
    }

    /** The item a selection finds, when it finds exactly one. */
    private fun one(selection: String, values: Array<String>, volume: String): MediaItem? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, values)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return try {
            resolver.query(MediaStore.Files.getContentUri(volume), PROJECTION, args, null)?.use { cursor ->
                if (cursor.count == 1 && cursor.moveToFirst()) Columns(cursor).item(cursor, context.packageName) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** One item by its id, trashed included; null when MediaStore has none the app may see. */
    fun item(id: Long, volume: String = MediaStore.VOLUME_EXTERNAL): MediaItem? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return try {
            resolver.query(MediaStore.Files.getContentUri(volume), PROJECTION, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) Columns(cursor).item(cursor, context.packageName) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A file from outside MediaStore as an item (SPEC §17.2): its name and size from its provider, its type from the
     * provider or the intent; a negative id of its own, no folder; "taken" is when it was opened.
     */
    fun outside(uri: Uri, type: String?): MediaItem {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        } catch (_: Exception) {
            // A provider that doesn't say: the address's last part and an unknown size
        }
        val mime = (try { resolver.getType(uri) } catch (_: Exception) { null }) ?: type ?: "image/*"
        return MediaItem(
            id = -(uri.toString().hashCode().toLong() and 0x7FFFFFFFL) - 1,
            video = mime.startsWith("video/"),
            mime = mime,
            taken = System.currentTimeMillis(),
            name = name,
            bucketId = 0,
            path = "",
            volume = "",
            size = size,
            width = 0,
            height = 0,
            duration = 0,
            favorite = false,
            trashed = false,
            expires = 0,
            ours = false,
            generation = 0,
            source = uri.toString(),
        )
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
