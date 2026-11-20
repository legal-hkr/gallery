package sh.hnet.gallery.data.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import sh.hnet.gallery.model.VaultEntry

/**
 * A file picked for import (SPEC §18.6): what the vault keeps of it, read from the file and its provider – nothing
 * of it enters MediaStore. Photos: the size upright, the date from EXIF (with its offset), a gain map; videos: the
 * size, length and date the file itself holds; else the provider's last change, else now.
 */
class ImportedFile(context: Context, private val uri: Uri) {
    private val resolver = context.contentResolver
    private val appContext = context.applicationContext

    private val name: String
    private val modified: Long?

    init {
        var found: String? = null
        var changed: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    found = cursor.getString(0)
                    changed = cursor.getLong(1).takeIf { !cursor.isNull(1) && it > 0 }
                }
            }
        } catch (_: Exception) {
            // A provider without these columns: a name from the address, no date
        }
        name = found ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        modified = changed
    }

    private val mime: String = resolver.getType(uri)?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        ?: "application/octet-stream"

    fun incoming(): Vault.Incoming {
        var meta: Meta? = null
        fun meta(): Meta = meta ?: read().also { meta = it }
        return Vault.Incoming(
            label = name,
            open = { resolver.openInputStream(uri) },
            thumbnail = { meta().thumbnail },
            entry = { file, size ->
                val m = meta()
                VaultEntry(
                    file = file, name = name, mime = mime, size = size, width = m.width, height = m.height, durationMs = m.durationMs,
                    taken = m.taken ?: modified ?: System.currentTimeMillis(), offsetMinutes = m.offsetMinutes,
                    movedAt = System.currentTimeMillis(), gainmap = m.gainmap,
                )
            },
        )
    }

    private class Meta(val width: Int, val height: Int, val durationMs: Long, val taken: Long?, val offsetMinutes: Int?, val gainmap: Boolean, val thumbnail: ByteArray?)

    private fun read(): Meta = if (mime.startsWith("video/")) video() else photo()

    private fun photo(): Meta {
        var width = 0
        var height = 0
        var gainmap = false
        val thumbnail = try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val scale = THUMB_PX.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                width = info.size.width
                height = info.size.height
            }
            // The decoder turns the photo upright: its shape tells the upright size
            if ((bitmap.width > bitmap.height) != (width > height)) width = height.also { height = width }
            gainmap = bitmap.hasGainmap()
            jpeg(bitmap)
        } catch (_: Exception) {
            null
        }
        var taken: Long? = null
        var offset: Int? = null
        try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                offset = (exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))?.let(::offsetMinutes)
                taken = (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.let { exifDate(it, offset) }
            }
        } catch (_: Exception) {
            // No EXIF: the provider's date
        }
        return Meta(width, height, 0, taken, offset, gainmap, thumbnail)
    }

    private fun video(): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) width = height.also { height = width }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val taken = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::videoDate)
            val frame = try {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX, THUMB_PX)
            } catch (_: Exception) {
                null
            }
            Meta(width, height, duration, taken, null, false, frame?.let(::jpeg))
        } catch (_: Exception) {
            Meta(0, 0, 0, null, null, false, null)
        } finally {
            retriever.release()
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().also { bitmap.recycle() }

    private companion object {
        const val THUMB_PX = 512

        /** EXIF's "+02:00" as minutes. */
        fun offsetMinutes(text: String): Int? {
            val match = Regex("""([+-])(\d{2}):(\d{2})""").matchEntire(text.trim()) ?: return null
            val (sign, hours, minutes) = match.destructured
            return (hours.toInt() * 60 + minutes.toInt()) * if (sign == "-") -1 else 1
        }

        /** EXIF's "2023:11:05 12:34:56" at [offset] minutes from UTC, or in the phone's zone without one. */
        fun exifDate(text: String, offset: Int?): Long? = try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply {
                timeZone = offset?.let { TimeZone.getTimeZone(ZoneOffset.ofTotalSeconds(it * 60)) } ?: TimeZone.getDefault()
            }.parse(text.trim())?.time
        } catch (_: Exception) {
            null
        }

        /** A video's own date, e.g. "20231105T123456.000Z" (UTC); the epoch (no date written) is none. */
        fun videoDate(text: String): Long? = try {
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)?.time?.takeIf { it > 86_400_000L }
        } catch (_: Exception) {
            null
        }
    }
}
