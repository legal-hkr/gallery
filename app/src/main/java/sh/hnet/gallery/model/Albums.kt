package sh.hnet.gallery.model

/**
 * A folder with at least one item that isn't in Trash (SPEC §9.3): empty folders aren't albums.
 *
 * @param newest Its newest item, the cover until the mosaic of S4
 */
data class Album(val folder: FolderKey, val count: Int, val newest: MediaItem)

object Albums {
    /** Every album, the one with the newest item first. */
    fun of(items: List<MediaItem>): List<Album> {
        val byFolder = LinkedHashMap<FolderKey, MutableList<MediaItem>>()
        // Items come newest first, so each folder's first item is its newest
        for (item in items) if (!item.trashed) byFolder.getOrPut(item.folder) { ArrayList() }.add(item)
        return byFolder.map { (folder, list) -> Album(folder, list.size, list.first()) }
    }
}
