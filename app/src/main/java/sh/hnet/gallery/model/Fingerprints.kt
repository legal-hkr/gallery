package sh.hnet.gallery.model

/**
 * What identifies an item beyond its MediaStore id (SPEC-albums §9.12): name, date taken and size – so an
 * album keeps it through a rescan that gave it a new id, and finds it on another phone after a restore.
 */
object Fingerprints {
    fun of(item: MediaItem): String = "${item.name}|${item.taken}|${item.size}"

    /**
     * Album members whose id the index no longer has, matched again by fingerprint ([orphans] id → fingerprint):
     * the new id of each one found. An item that more than one orphan points to goes to one of them only.
     */
    fun relink(orphans: Map<Long, String>, items: List<MediaItem>): Map<Long, Long> {
        if (orphans.isEmpty()) return emptyMap()
        val byPrint = HashMap<String, Long>(items.size * 2)
        for (item in items) byPrint.putIfAbsent(of(item), item.id)
        val taken = HashSet<Long>()
        val moves = HashMap<Long, Long>()
        for ((old, print) in orphans) {
            val new = byPrint[print] ?: continue
            if (taken.add(new)) moves[old] = new
        }
        return moves
    }

    /** What the backup's [prints] find among [items]: the ids found, and how many weren't. */
    fun match(prints: List<String>, items: List<MediaItem>): Pair<List<Long>, Int> {
        val byPrint = HashMap<String, Long>(items.size * 2)
        for (item in items) byPrint.putIfAbsent(of(item), item.id)
        val found = prints.mapNotNull { byPrint[it] }.distinct()
        return found to prints.distinct().size - found.size
    }
}

/** A user album's name (SPEC-albums §9.6): trimmed, any character, unique across both kinds of album, case ignored. */
object AlbumNames {
    sealed interface Problem {
        data object Empty : Problem

        data object Exists : Problem
    }

    fun problem(name: String, taken: Collection<String>): Problem? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Problem.Empty
        if (taken.any { it.trim().equals(trimmed, ignoreCase = true) }) return Problem.Exists
        return null
    }
}
