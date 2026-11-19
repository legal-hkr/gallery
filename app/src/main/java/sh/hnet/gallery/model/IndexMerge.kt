package sh.hnet.gallery.model

/** How a refresh by generation updates the list in memory (LOGIKA §2.2). */
object IndexMerge {
    /**
     * The list after a refresh: [changed] rows (new or changed since the last generation seen) replace
     * or join [current]; rows whose id isn't in [present] any more are gone (deleted, or their volume
     * was removed). The result is sorted [MediaItem.NEWEST_FIRST].
     *
     * @param present Every id MediaStore still has; null when not asked (nothing deleted assumed)
     */
    fun merge(current: List<MediaItem>, changed: List<MediaItem>, present: Set<Long>?): List<MediaItem> {
        if (changed.isEmpty() && (present == null || current.all { it.id in present })) return current
        val byId = LinkedHashMap<Long, MediaItem>(current.size + changed.size)
        for (item in current) if (present == null || item.id in present) byId[item.id] = item
        for (item in changed) if (present == null || item.id in present) byId[item.id] = item
        return byId.values.sortedWith(MediaItem.NEWEST_FIRST)
    }
}
