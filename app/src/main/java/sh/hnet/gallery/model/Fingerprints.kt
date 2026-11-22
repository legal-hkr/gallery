package sh.hnet.gallery.model

/**
 * What identifies an item beyond its MediaStore id (SPEC-albums §9.12): name, date taken and size – so an
 * album keeps it through a rescan that gave it a new id, and finds it on another phone after a restore.
 */
object Fingerprints {
    fun of(item: MediaItem): String = "${item.name}|${item.taken}|${item.size}"

    /**
     * An album member whose id the index no longer has: its fingerprint, the volume it was on ("" when not known – rows
     * from before database version 4), and the albums it is in.
     */
    data class Orphan(val print: String, val volume: String = "", val albums: Set<Long> = emptySet())

    /**
     * Album members whose id the index no longer has, matched again by fingerprint: the new id of each one found
     * (review before v0.1.0, fixed after v0.1.1, its review after):
     * - each takes an item of its own – two identical files (same name, date, size) in an album find both copies, not
     *   one between them –, never one already in one of its albums ([members]: album → ids);
     * - on its own volume only: one not mounted (a card taken out, so no item is on it) waits for its return, and a card
     *   being scanned again can't hand its members to the phone's copies before its own files are in; a file moved to
     *   another volume isn't followed. Members of a known volume are matched first, those without one (older rows) after.
     */
    fun relink(orphans: Map<Long, Orphan>, items: List<MediaItem>, members: Map<Long, Set<Long>> = emptyMap()): Map<Long, Long> {
        if (orphans.isEmpty()) return emptyMap()
        val byPrint = HashMap<String, MutableList<MediaItem>>(items.size * 2)
        for (item in items) byPrint.getOrPut(of(item)) { ArrayList(1) }.add(item)
        val mounted = items.mapTo(HashSet()) { it.volume }
        val assigned = HashSet<Long>()
        val moves = HashMap<Long, Long>()
        for ((old, orphan) in orphans.entries.sortedBy { if (it.value.volume.isEmpty()) 1 else 0 }) {
            if (orphan.volume.isNotEmpty() && orphan.volume !in mounted) continue
            val new = byPrint[orphan.print].orEmpty().firstOrNull { candidate ->
                candidate.id !in assigned &&
                    (orphan.volume.isEmpty() || candidate.volume == orphan.volume) &&
                    orphan.albums.none { album -> candidate.id in members[album].orEmpty() }
            } ?: continue
            assigned += new.id
            moves[old] = new.id
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
