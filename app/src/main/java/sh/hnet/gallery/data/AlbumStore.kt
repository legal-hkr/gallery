package sh.hnet.gallery.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import sh.hnet.gallery.facts.AlbumDao
import sh.hnet.gallery.facts.AlbumItemRow
import sh.hnet.gallery.facts.AlbumRow
import sh.hnet.gallery.model.Fingerprints
import sh.hnet.gallery.model.MediaItem

/**
 * User albums (SPEC-albums §9.6–§9.9, §9.12): sets kept by the app – adding and removing never touch a file.
 * Members are kept by MediaStore id and found again by fingerprint after a rescan; an item deleted for good
 * by the app leaves them. Every change that can be undone returns what it took away.
 */
class AlbumStore(private val dao: AlbumDao, scope: CoroutineScope) {
    val albums: StateFlow<List<AlbumRow>?> = dao.watchAlbums().stateIn(scope, SharingStarted.Eagerly, null)

    val members: StateFlow<List<AlbumItemRow>?> = dao.watchItems().stateIn(scope, SharingStarted.Eagerly, null)

    /** A new album with [items] in it (none: an empty album, SPEC-albums §9.9); its id. */
    suspend fun create(name: String, items: List<MediaItem>, now: Long): Long {
        val id = dao.insert(AlbumRow(name = name.trim(), createdAt = now, lastAddedAt = now))
        if (items.isNotEmpty()) dao.upsertItems(items.map { AlbumItemRow(id, it.id, Fingerprints.of(it), now) })
        return id
    }

    /** Adds what isn't there yet (SPEC-albums §10.1): how many were added and how many were there already. */
    suspend fun add(album: Long, items: List<MediaItem>, now: Long): Pair<Int, Int> {
        val there = dao.itemsOf(album).mapTo(HashSet()) { it.mediaId }
        val new = items.filter { it.id !in there }
        if (new.isNotEmpty()) {
            dao.upsertItems(new.map { AlbumItemRow(album, it.id, Fingerprints.of(it), now) })
            dao.touched(album, now)
        }
        return new.size to items.size - new.size
    }

    /** Remove from album (SPEC-albums §9.8): the rows taken away, for Undo. */
    suspend fun remove(album: Long, ids: Collection<Long>): List<AlbumItemRow> {
        val gone = dao.itemsOf(album).filter { it.mediaId in ids }
        if (gone.isNotEmpty()) dao.removeItems(album, gone.map { it.mediaId })
        return gone
    }

    suspend fun putBack(items: List<AlbumItemRow>) {
        if (items.isNotEmpty()) dao.upsertItems(items)
    }

    /** Delete albums (SPEC-albums §9.5, §9.7): the sets only; what they were, for Undo. */
    suspend fun delete(ids: Collection<Long>): Pair<List<AlbumRow>, List<AlbumItemRow>> {
        val albums = dao.albums().filter { it.id in ids }
        val items = dao.items().filter { it.albumId in ids }
        dao.deleteItemsOfAlbums(ids.toList())
        dao.deleteAlbums(ids.toList())
        return albums to items
    }

    suspend fun putBack(albums: List<AlbumRow>, items: List<AlbumItemRow>) {
        if (albums.isNotEmpty()) dao.upsertAlbums(albums)
        putBack(items)
    }

    suspend fun rename(album: Long, name: String) = dao.rename(album, name.trim())

    suspend fun setSort(album: Long, sort: String?) = dao.setSort(album, sort)

    suspend fun setPinned(album: Long, pinnedAt: Long?) = dao.setPinned(album, pinnedAt)

    /** Every album with its members' fingerprints, for the backup (SPEC-albums §20.2). */
    suspend fun snapshot(): List<Pair<AlbumRow, List<String>>> {
        val items = dao.items().groupBy { it.albumId }
        return dao.albums().map { album -> album to items[album.id].orEmpty().sortedBy { it.addedAt }.map { it.fingerprint } }
    }

    /**
     * A backup's album: merged into the album of the same name (case ignored) when there is one, else made; its
     * items found here by fingerprint are in it afterwards. How many weren't found.
     */
    suspend fun restore(name: String, createdAt: Long, pinnedAt: Long?, sort: String?, prints: List<String>, items: List<MediaItem>, now: Long): Int {
        val (found, missing) = Fingerprints.match(prints, items)
        val existing = dao.albums().firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val id = if (existing != null) {
            dao.upsertAlbums(listOf(existing.copy(pinnedAt = pinnedAt ?: existing.pinnedAt, sort = sort ?: existing.sort)))
            existing.id
        } else {
            dao.insert(AlbumRow(name = name.trim(), createdAt = createdAt, lastAddedAt = now, pinnedAt = pinnedAt, sort = sort))
        }
        val byId = items.associateBy { it.id }
        dao.upsertItems(found.mapNotNull { byId[it] }.map { AlbumItemRow(id, it.id, Fingerprints.of(it), now) })
        return missing
    }

    /** Items deleted for good by the app (SPEC-albums §9.12): out of every album. */
    suspend fun forget(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.forget(ids.toList())
    }

    /**
     * After the index changed: members whose id is gone are looked for by fingerprint and moved to the id found
     * (a rescan gives new ids). Those not found stay as they are – a folder on a card taken out comes back.
     */
    suspend fun relink(items: List<MediaItem>) {
        val rows = dao.items()
        if (rows.isEmpty()) return
        val present = items.mapTo(HashSet(items.size * 2)) { it.id }
        val orphans = rows.filter { it.mediaId !in present }.associate { it.mediaId to it.fingerprint }
        val moves = Fingerprints.relink(orphans, items)
        if (moves.isEmpty()) return
        val byId = items.associateBy { it.id }
        val moved = rows.filter { it.mediaId in moves }
        dao.forget(moves.keys.toList())
        dao.upsertItems(moved.map { row -> val to = moves.getValue(row.mediaId); row.copy(mediaId = to, fingerprint = Fingerprints.of(byId.getValue(to))) })
    }
}
