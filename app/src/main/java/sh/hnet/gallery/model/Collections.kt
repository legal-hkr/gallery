package sh.hnet.gallery.model

/**
 * The collections of the Albums tab built in S4a (SPEC §9.2): what each holds, from every folder – a
 * collection of the whole library, not a view of the timeline. Trashed items are in none of them.
 */
enum class CollectionKind {
    FAVORITES,
    VIDEOS,
    SCREENSHOTS;

    fun holds(item: MediaItem): Boolean = !item.trashed && when (this) {
        FAVORITES -> item.favorite
        VIDEOS -> item.video
        SCREENSHOTS -> TimelineFolders.isScreenshots(item.path)
    }

    companion object {
        fun of(name: String): CollectionKind = entries.firstOrNull { it.name == name } ?: FAVORITES
    }
}

/** Trash's items (SPEC §11, LOGIKA §0 p. 10): the soonest to be deleted for good last, the newest trashed first. */
object TrashOrder {
    fun of(items: List<MediaItem>): List<MediaItem> = items.filter { it.trashed }.sortedWith(compareByDescending<MediaItem> { it.expires }.thenByDescending { it.id })
}
