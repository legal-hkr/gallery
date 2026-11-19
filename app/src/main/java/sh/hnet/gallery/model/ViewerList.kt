package sh.hnet.gallery.model

/**
 * The viewer's pages (LOGIKA §13.1): the items of the list it was opened from, in that list's
 * order, kept by id. Items deleted or trashed meanwhile drop out; one put back (Undo) returns to its place.
 */
object ViewerList {
    /** The pages now: the opening order, only what still exists and isn't trashed. */
    fun pages(opened: List<Long>, items: Map<Long, MediaItem>): List<Long> = opened.filter { id -> items[id]?.trashed == false }

    /**
     * The item to show after the pages changed: [current] while it is still there; else the next one
     * of the opening order that is, else the previous one; null when nothing is left.
     */
    fun follow(current: Long, opened: List<Long>, pages: List<Long>): Long? {
        if (current in pages) return current
        val present = pages.toHashSet()
        val at = opened.indexOf(current)
        if (at < 0) return pages.firstOrNull()
        for (i in at + 1 until opened.size) if (opened[i] in present) return opened[i]
        for (i in at - 1 downTo 0) if (opened[i] in present) return opened[i]
        return null
    }
}
