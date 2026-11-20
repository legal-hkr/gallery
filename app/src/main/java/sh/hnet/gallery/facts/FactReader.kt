package sh.hnet.gallery.facts

import android.content.ContentResolver
import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Playback
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
        val empty = Fact(item.id, item.generation, versionFor(item), located = location, offsetMinutes = null, latitude = null, longitude = null, motion = false, motionLength = null, gainmap = false)
        if (item.video) return video(item, location, empty)
        if (item.mime !in READABLE) return empty
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

    /** A video's location (ISO 6709 in its metadata, LOGIKA §17.4); read only with the permission, as a photo's. */
    private fun video(item: MediaItem, location: Boolean, empty: Fact): Fact {
        if (!location) return empty
        return try {
            MediaMetadataRetriever().use { retriever ->
                resolver.openFileDescriptor(MediaStore.setRequireOriginal(item.uri()), "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: return empty
                val point = Playback.iso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                empty.copy(latitude = point?.first, longitude = point?.second)
            }
        } catch (_: Exception) {
            empty
        }
    }

    companion object {
        /** Raised when the reader learns something new: every photo is read again (as Music's parser version). */
        const val VERSION = 1

        /** Raised for videos alone: 2 reads their location (S6a), without reading every photo again. */
        const val VIDEO_VERSION = 2

        fun versionFor(item: MediaItem): Int = if (item.video) VIDEO_VERSION else VERSION

        /** Types `ExifInterface` reads. */
        private val READABLE = setOf("image/jpeg", "image/heic", "image/heif", "image/png", "image/webp", "image/x-adobe-dng", "image/avif")
    }
}
