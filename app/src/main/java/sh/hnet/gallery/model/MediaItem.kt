package sh.hnet.gallery.model

/**
 * One photo or video as MediaStore lists it (LOGIKA §2.1): the light row every view of the app is
 * made from. Nothing here is read from the file itself; that is [Fact]'s.
 *
 * @param taken When it was taken, epoch milliseconds: `DATE_TAKEN`, else the file's `DATE_MODIFIED`, else `DATE_ADDED` (LOGIKA §2.1)
 * @param path The folder relative to its volume's root, with a trailing slash (`DCIM/Camera/`)
 * @param volume The volume's name (`external_primary`, an SD card's id)
 * @param duration A video's length in milliseconds, 0 for photos
 * @param expires When the system deletes a trashed item for good, epoch milliseconds; 0 otherwise
 * @param ours Whether the app owns the file, so it may change it without asking (LOGIKA §4)
 */
data class MediaItem(
    val id: Long,
    val video: Boolean,
    val mime: String,
    val taken: Long,
    val name: String,
    val bucketId: Long,
    val path: String,
    val volume: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val favorite: Boolean,
    val trashed: Boolean,
    val expires: Long,
    val ours: Boolean,
    val generation: Long,
) {
    /** The folder it is in, the key of an album and of the timeline's choice (LOGIKA §2.5). */
    val folder: FolderKey get() = FolderKey(volume, path)

    /** RAW photos (DNG and the makers' RAW types) carry the RAW tile mark (SPEC §4.5). */
    val raw: Boolean get() = !video && (mime == "image/x-adobe-dng" || mime.startsWith("image/x-") && RAW_TYPES.any { mime.endsWith(it) })

    companion object {
        /** Newest first; equal times by id, newest first, so the order never changes between reads. */
        val NEWEST_FIRST: Comparator<MediaItem> = compareByDescending<MediaItem> { it.taken }.thenByDescending { it.id }

        private val RAW_TYPES = listOf("canon-cr2", "canon-cr3", "nikon-nef", "sony-arw", "fuji-raf", "olympus-orf", "panasonic-rw2", "pentax-pef", "samsung-srw")
    }
}

/**
 * A folder on a volume, as albums and the timeline's choice name it (LOGIKA §2.5). Settings keep it
 * as [key], so a backup restored on another phone finds the same folder.
 */
data class FolderKey(val volume: String, val path: String) {
    val key: String get() = "$volume:$path"

    /** The folder's own name: the last part of the path. */
    val name: String get() = path.trimEnd('/').substringAfterLast('/')

    companion object {
        fun of(key: String): FolderKey? {
            val colon = key.indexOf(':')
            if (colon <= 0) return null
            return FolderKey(key.substring(0, colon), key.substring(colon + 1))
        }
    }
}
