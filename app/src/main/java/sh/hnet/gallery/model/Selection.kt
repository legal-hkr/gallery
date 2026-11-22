package sh.hnet.gallery.model

import kotlin.math.max
import kotlin.math.min

/** Selecting several items (SPEC §5.3), as pure rules. */
object Selection {
    /**
     * A drag after a long press (SPEC §5.3): every item from the first one ([anchor]) to the one under the finger
     * ([current]), in reading order ([order]), takes the first one's new state ([select]); the others keep the state
     * they had when the drag started ([base]), so moving back shrinks the run.
     */
    fun run(base: Set<Long>, order: List<Long>, anchor: Int, current: Int, select: Boolean): Set<Long> {
        if (order.isEmpty() || anchor !in order.indices) return base
        val to = current.coerceIn(0, order.lastIndex)
        val range = order.subList(min(anchor, to), max(anchor, to) + 1)
        return if (select) base + range else base - range.toSet()
    }

    /** A header's circle (SPEC §5.3): the whole group selected, or cleared when it all is already. */
    fun toggleGroup(selected: Set<Long>, group: List<Long>): Set<Long> =
        if (group.isNotEmpty() && selected.containsAll(group)) selected - group.toSet() else selected + group

    /** The items under each header of a grid's entries: the tiles after it until the next header, by the header's key. */
    fun groups(entries: List<TimelineEntry>): Map<String, List<Long>> {
        val groups = HashMap<String, MutableList<Long>>()
        var header: String? = null
        for (entry in entries) {
            if (entry is TimelineEntry.Tile) header?.let { groups.getOrPut(it) { ArrayList() }.add(entry.item.id) } else header = entry.key
        }
        return groups
    }
}

/** Trash's pill (SPEC §11): whole days until the system deletes the item for good; 0 under a day. */
object TrashDays {
    private const val DAY_MS = 86_400_000L

    /** [expiresMs] as [MediaItem.expires] holds it: MediaStore's `DATE_EXPIRES` already in milliseconds. */
    fun left(expiresMs: Long, nowMs: Long): Int = ((expiresMs - nowMs).coerceAtLeast(0) / DAY_MS).toInt()
}

/** A file's new name (SPEC §10.3): the editable part and the kept extension, and what's wrong with a name. */
object FileNames {
    /**
     * The type [name]'s extension calls for when MediaStore's [mime] disagrees with it but is of the same kind (a
     * QuickTime ".mov" its scanner typed video/mp4 from the container) – else null. [byExtension] looks an extension up
     * (Android's MimeTypeMap). Such a file can't go to Trash on Android 17 until its type matches its name (owner's
     * test before v0.1.1).
     */
    fun mistyped(name: String, mime: String, byExtension: (String) -> String?): String? {
        val extension = split(name).second.removePrefix(".").lowercase()
        if (extension.isEmpty()) return null
        val wanted = byExtension(extension) ?: return null
        if (wanted.equals(mime, ignoreCase = true)) return null
        return wanted.takeIf { it.substringBefore('/') == mime.substringBefore('/') }
    }

    /** Characters a FAT-formatted SD card refuses (and the path separator), so a name works on every storage. */
    const val FORBIDDEN = "/\\:*?\"<>|"

    /** The name without its extension, and the extension with its dot ("" when none). */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    sealed interface Problem {
        data object Empty : Problem

        /** The characters not allowed, each once, in the order typed. */
        data class Characters(val characters: String) : Problem

        data object Exists : Problem
    }

    /** What's wrong with [base] + [extension] in a folder whose other names are [taken] (compared without case), or null. */
    fun problem(base: String, extension: String, taken: Collection<String>): Problem? {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return Problem.Empty
        val bad = trimmed.filter { it in FORBIDDEN || it.code < 32 }.toSet().joinToString("")
        if (bad.isNotEmpty()) return Problem.Characters(bad)
        val name = trimmed + extension
        if (taken.any { it.equals(name, ignoreCase = true) }) return Problem.Exists
        return null
    }
}
