package sh.hnet.gallery.widget

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.FrameState
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.MediaItem

/**
 * Each Frame widget's album, interval and round (S11, LOGIKA §22.5), by widget id, in a store of its own as Contacts'
 * widget choices: not a setting, so Reset and the backup leave it alone, and gone with its widget.
 */
object Frames {
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(context: Context, id: Int): FrameState? = prefs(context).getString("$KEY$id", null)?.let {
        runCatching { json.decodeFromString(FrameState.serializer(), it) }.getOrNull()
    }

    @Synchronized
    fun set(context: Context, id: Int, state: FrameState) = prefs(context).edit { putString("$KEY$id", json.encodeToString(FrameState.serializer(), state)) }

    /**
     * A redraw's step kept only while the widget still has the album and interval it was made for: a choice saved in
     * the configuration meanwhile wins (review of S11: the redraw wrote the old album back).
     */
    @Synchronized
    fun advance(context: Context, id: Int, from: FrameState, to: FrameState) {
        val now = get(context, id) ?: return
        if (now.album == from.album && now.interval == from.interval) set(context, id, to)
    }

    /** The launcher's restore gave the widgets new ids: their choices follow. */
    @Synchronized
    fun move(context: Context, from: IntArray, to: IntArray) {
        val prefs = prefs(context)
        val states = from.map { prefs.getString("$KEY$it", null) }
        prefs.edit {
            from.forEach { remove("$KEY$it") }
            to.forEachIndexed { i, id -> states.getOrNull(i)?.let { putString("$KEY$id", it) } }
        }
    }

    @Synchronized
    fun forget(context: Context, ids: IntArray) = prefs(context).edit { ids.forEach { remove("$KEY$it") } }

    /** What an album holds now for a Frame: its photos and videos, or that it is gone. */
    sealed interface Content {
        data class Items(val items: List<MediaItem>) : Content

        data object Gone : Content
    }

    /**
     * The album's items, asked of the media alone (the app may be closed: never the whole index, D4) – a folder by its
     * path, a user album by its members, Favorites and Videos by their columns –, newest first, as the app shows them:
     * not in Trash, hidden sources and album covers left out. Null when the sources' states can't be read in time or
     * the media's ask fails – the widget stays as it is then, not "empty".
     */
    suspend fun content(app: GalleryApplication, album: String): Content? {
        val rows = withTimeoutOrNull(10_000) { app.sources.rows.filterNotNull().first() } ?: return null
        val newFolders = app.preferences.newFoldersInPhotos.first()
        fun shown(items: List<MediaItem>) = Library.of(items.filter { !it.trashed }) { SourceStore.stateOf(it, rows, newFolders) }.visible.sortedWith(MediaItem.NEWEST_FIRST)
        return when {
            album.startsWith("s:") -> {
                val folder = FolderKey.of(album.removePrefix("s:")) ?: return Content.Gone
                val items = shown(app.media.folderOrNull(folder.volume, folder.path) ?: return null)
                // A folder's album is there while it has something shown: empty, it's gone from the Albums tab
                if (items.isEmpty()) Content.Gone else Content.Items(items)
            }
            album.startsWith("u:") -> {
                val id = album.removePrefix("u:").toLongOrNull() ?: return Content.Gone
                val albums = withTimeoutOrNull(10_000) { app.albums.albums.filterNotNull().first() } ?: return null
                if (albums.none { it.id == id }) return Content.Gone
                val members = withTimeoutOrNull(10_000) { app.albums.members.filterNotNull().first() } ?: return null
                Content.Items(shown(app.media.existing(members.filter { it.albumId == id }.map { it.mediaId }) ?: return null))
            }
            album == FAVORITES -> Content.Items(shown(app.media.collection(favorites = true) ?: return null))
            album == VIDEOS -> Content.Items(shown(app.media.collection(favorites = false) ?: return null))
            else -> Content.Gone
        }
    }

    const val FAVORITES = "c:FAVORITES"
    const val VIDEOS = "c:VIDEOS"
    private const val KEY = "frame:"
}
