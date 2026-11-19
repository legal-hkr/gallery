package sh.hnet.gallery.ui.albums

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.Collator
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.facts.AlbumItemRow
import sh.hnet.gallery.facts.AlbumRow
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.ui.components.albumName

/**
 * An album of the Albums tab (SPEC-albums §9.4): a source's or a user's, told apart only by the folder mark.
 *
 * @param key Unique across both kinds, for the grid and the album selection
 * @param recent A source's newest `DATE_ADDED`, a user album's last addition (or its creation)
 */
sealed interface AlbumEntry {
    val key: String
    val count: Int
    val newest: List<MediaItem>
    val recent: Long
    val pinnedAt: Long?

    data class Source(val folder: FolderKey, override val count: Int, override val newest: List<MediaItem>, override val recent: Long, override val pinnedAt: Long?, val otherStorage: Boolean) : AlbumEntry {
        override val key: String get() = "s:" + folder.key
    }

    /** @param memberIds Its items shown (not in Trash, not of a hidden source), newest first */
    data class User(val id: Long, val name: String, override val count: Int, override val newest: List<MediaItem>, override val recent: Long, override val pinnedAt: Long?, val memberIds: List<Long>) : AlbumEntry {
        override val key: String get() = "u:$id"
    }
}

/** The user albums with what of them is shown ([visible] by id), in the Albums tab's order. */
fun userAlbums(albums: List<AlbumRow>, members: Map<Long, List<AlbumItemRow>>, visible: Map<Long, MediaItem>, sort: String, collator: Collator): List<AlbumEntry.User> {
    val entries = albums.map { album ->
        val shown = members[album.id].orEmpty().mapNotNull { visible[it.mediaId] }.sortedWith(MediaItem.NEWEST_FIRST)
        AlbumEntry.User(album.id, album.name, shown.size, shown.take(4), album.lastAddedAt, album.pinnedAt, shown.map { it.id })
    }
    return sortAlbums(entries, sort, collator) { (it as AlbumEntry.User).name }.map { it as AlbumEntry.User }
}

/** Pinned first in pinning order, then by [sort]: `RECENT` (default), `NAME` (by [name], the language's collation), `COUNT` (SPEC-albums §9.4). */
fun sortAlbums(albums: List<AlbumEntry>, sort: String, collator: Collator? = null, name: (AlbumEntry) -> String): List<AlbumEntry> {
    val pinned = albums.filter { it.pinnedAt != null }.sortedBy { it.pinnedAt }
    val rest = albums.filter { it.pinnedAt == null }
    val sorted = when (sort) {
        "NAME" -> if (collator != null) rest.sortedWith { a, b -> collator.compare(name(a), name(b)) } else rest.sortedBy { name(it).lowercase() }
        "COUNT" -> rest.sortedByDescending { it.count }
        else -> rest.sortedByDescending { it.recent }
    }
    return pinned + sorted
}

/** Every album's name as shown (SPEC-albums §9.6: unique across both kinds): the user albums' and the sources'. */
@Composable
fun albumNamesTaken(): List<String> {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    val library by app.library.collectAsStateWithLifecycle()
    val folders = library?.visible.orEmpty().asSequence().map { it.folder }.distinct().filterNot { TimelineFolders.isScreenshots(it.path) }.toList()
    return albums.orEmpty().map { it.name } + folders.map { albumName(it) }
}
