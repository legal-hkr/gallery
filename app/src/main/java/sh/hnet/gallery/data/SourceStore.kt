package sh.hnet.gallery.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.hnet.gallery.facts.SourceDao
import sh.hnet.gallery.facts.SourceRow
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.SourceRules
import sh.hnet.gallery.model.SourceState

/**
 * The sources' states (SPEC-albums §9.1, §22.2; LOGIKA §15): every folder the index shows gets its state by
 * the rules the first time; the user's choice replaces it for good. The timeline's old choice (S1–S4a)
 * becomes the user's states once.
 */
class SourceStore(private val dao: SourceDao, scope: CoroutineScope) {
    /** Every known source by key; null until read. */
    val rows: StateFlow<Map<String, SourceRow>?> = dao.watch().map { list -> list.associateBy { it.key } }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * After the index changed (LOGIKA §15.2): folders not known yet are classified and stored; known ones are
     * marked seen (at most once a day); those unseen for 90 days are forgotten. [old] is the timeline's
     * choice of S1–S4a, when it is still to be taken over.
     */
    suspend fun sync(folders: Set<FolderKey>, newFoldersInPhotos: Boolean, now: Long, old: Map<String, SourceState>) {
        val known = dao.all().associateBy { it.key }
        val writes = ArrayList<SourceRow>()
        for ((key, state) in old) writes += (known[key]?.copy(state = state.name, chosenByUser = true) ?: SourceRow(key, state.name, chosenByUser = true, lastSeenAt = now))
        val present = HashSet<String>(folders.size * 2)
        for (folder in folders) {
            val key = folder.key
            present += key
            if (key in old) continue
            val row = known[key]
            if (row == null) {
                writes += SourceRow(key, SourceRules.classify(folder.path, newFoldersInPhotos).name, chosenByUser = false, lastSeenAt = now)
            } else if (now - row.lastSeenAt > DAY_MS) {
                writes += row.copy(lastSeenAt = now)
            }
        }
        if (writes.isNotEmpty()) dao.upsert(writes)
        val forgotten = known.values.filter { it.key !in present && it.key !in old && now - it.lastSeenAt > FORGET_MS }.map { it.key }
        if (forgotten.isNotEmpty()) dao.delete(forgotten)
    }

    /** The user's state for these sources; the rows before, for Undo. */
    suspend fun choose(folders: Collection<FolderKey>, state: SourceState, now: Long): Map<String, SourceRow?> {
        val known = dao.all().associateBy { it.key }
        val before = folders.associate { it.key to known[it.key] }
        dao.upsert(folders.map { folder -> known[folder.key]?.copy(state = state.name, chosenByUser = true) ?: SourceRow(folder.key, state.name, chosenByUser = true, lastSeenAt = now) })
        return before
    }

    /** Undo of [choose]: the rows as they were (a source unknown before is forgotten again). */
    suspend fun undo(before: Map<String, SourceRow?>) {
        val back = before.values.filterNotNull()
        if (back.isNotEmpty()) dao.upsert(back)
        val forget = before.filterValues { it == null }.keys.toList()
        if (forget.isNotEmpty()) dao.delete(forget)
    }

    /** A source's album's sort (SPEC-albums §9.10), remembered per source. */
    suspend fun setSort(folder: FolderKey, sort: String?) = dao.setSort(folder.key, sort)

    /** Pinned on top of the albums (SPEC-albums §9.4), in pinning order; null unpins. */
    suspend fun setPinned(folder: FolderKey, pinnedAt: Long?) = dao.setPinned(folder.key, pinnedAt)

    /** The states the user chose, for the backup (SPEC-albums §20.2). */
    suspend fun chosen(): Map<String, String> = dao.all().filter { it.chosenByUser }.associate { it.key to it.state }

    /** A backup's chosen states: each replaces what this phone has for that source; the others stay (family rule). */
    suspend fun restore(chosen: Map<String, SourceState>, now: Long) {
        if (chosen.isEmpty()) return
        val known = dao.all().associateBy { it.key }
        dao.upsert(chosen.mapNotNull { (key, state) ->
            if (FolderKey.of(key) == null) null else known[key]?.copy(state = state.name, chosenByUser = true) ?: SourceRow(key, state.name, chosenByUser = true, lastSeenAt = now)
        })
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val FORGET_MS = 90 * DAY_MS

        /** A folder's state: stored, else what the rules give it (a folder the store hasn't taken in yet). */
        fun stateOf(folder: FolderKey, rows: Map<String, SourceRow>, newFoldersInPhotos: Boolean): SourceState =
            SourceState.of(rows[folder.key]?.state) ?: SourceRules.classify(folder.path, newFoldersInPhotos)
    }
}
