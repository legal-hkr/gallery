package sh.hnet.gallery.facts

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.XmpFacts

/** Reads one file's facts (LOGIKA §2.3): only the headers, through `ExifInterface`. */
class FactReader(private val resolver: ContentResolver) {
    /**
     * The item's facts. Videos and formats without EXIF give an empty row, so they aren't tried
     * again; a file that can't be read gives one too (it is tried again when it changes).
     *
     * @param location Whether the app may read the location (`ACCESS_MEDIA_LOCATION`)
     */
    fun read(item: MediaItem, location: Boolean): Fact {
        val empty = Fact(item.id, item.generation, VERSION, located = location, offsetMinutes = null, latitude = null, longitude = null, motion = false, motionLength = null, gainmap = false)
        if (item.video || item.mime !in READABLE) return empty
        val base = ContentUris.withAppendedId(MediaStore.Files.getContentUri(item.volume), item.id)
        // The original, with its location, only with the permission; without it the system cuts the GPS tags out (SPEC §22.5)
        val uri = if (location) MediaStore.setRequireOriginal(base) else base
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val xmp = XmpFacts.parse(exif.getAttribute(ExifInterface.TAG_XMP))
                val latLong = if (location) exif.latLong else null
                empty.copy(
                    offsetMinutes = XmpFacts.offsetMinutes(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)),
                    latitude = latLong?.get(0),
                    longitude = latLong?.get(1),
                    motion = xmp.motion,
                    motionLength = xmp.motionLength,
                    gainmap = xmp.gainmap,
                )
            } ?: empty
        } catch (_: Exception) {
            // Gone meanwhile, not readable, or not what its type says: nothing more to know
            empty
        }
    }

    companion object {
        /** Raised when the reader learns something new: every file is read again (as Music's parser version). */
        const val VERSION = 1

        /** Types `ExifInterface` reads. */
        private val READABLE = setOf("image/jpeg", "image/heic", "image/heif", "image/png", "image/webp", "image/x-adobe-dng", "image/avif")
    }
}
