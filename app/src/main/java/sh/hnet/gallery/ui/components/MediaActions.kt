package sh.hnet.gallery.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.ShareMaker
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.ui.share.ShareSheet

/**
 * The actions on items shared by every grid and the viewer (SPEC §5.5, §6.4, §6.5, §11): each goes
 * through the system's dialog when it must (one for the whole action), and says what happened in the
 * family's short snackbar – confirmations without a period – with Undo where it can be undone.
 * Messages run in the shell's scope, so they outlive the screen that started them.
 */
class MediaActions(
    private val context: Context,
    private val writer: MediaWriter,
    private val consent: Consent,
    private val snackbars: SnackbarHostState,
    private val messageScope: CoroutineScope,
) {
    /** Starts an activity from the shell's activity (the share sheet); set by the shell. */
    var start: (Intent) -> Unit = {}

    /** Shows the family's error details (Show of a failure's snackbar); set by the shell. */
    var showError: (ErrorDetails) -> Unit = {}

    private var sharing: Job? = null

    /**
     * Share (SPEC §14): prepared – copies without location by Settings › Sharing, or the originals – then the system's
     * sheet; a failure says how many files couldn't be prepared, with Show.
     */
    fun share(items: List<MediaItem>, removeLocation: Boolean? = null, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        val app = context.applicationContext as GalleryApplication
        sharing?.cancel()
        sharing = messageScope.launch {
            // The setting, or the share sheet's choice for this share
            val remove = removeLocation ?: app.preferences.shareWithoutLocation.first()
            when (val prepared = app.sharing.prepare(items, app.facts.facts.value.orEmpty(), remove, app.media.canLocate())) {
                is ShareMaker.Prepared.Ready -> {
                    onDone()
                    start(ShareSheet.chooser(context, app.sharing, prepared, items.map { it.id }, remove))
                }
                is ShareMaker.Prepared.Failed -> messageScope.reportFailure(
                    snackbars, context.getString(R.string.error_prepare_share, prepared.count), prepared.details, context.getString(R.string.button_show), showError,
                )
            }
        }
    }

    /** The status card's Cancel (SPEC §14.3): what is done stays in the cache, nothing is shared. */
    fun cancelShare() {
        sharing?.cancel()
    }

    /** Runs [then] once the change is done: at once, or after the system's dialog was accepted; a refusal or a failure changes nothing. */
    fun apply(change: MediaWriter.Change, then: () -> Unit) {
        when (change) {
            MediaWriter.Change.Done -> then()
            is MediaWriter.Change.Ask -> consent.ask(change.request, then)
            is MediaWriter.Change.Failed -> {}
        }
    }

    /** To Trash at once, with Undo (SPEC §5.5); [onDone] when they are there (a selection ends, the viewer knows it moved them). */
    fun trash(items: List<MediaItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        // Any of them in a user album: the message says they left every album too (SPEC-albums §5.5)
        val members = (context.applicationContext as GalleryApplication).albums.members.value.orEmpty().mapTo(HashSet()) { it.mediaId }
        val inAlbums = items.any { it.id in members }
        val message = when {
            items.size == 1 && inAlbums -> context.getString(R.string.msg_moved_to_trash_one_albums)
            items.size == 1 -> context.getString(R.string.msg_moved_to_trash_one)
            inAlbums -> context.getString(R.string.msg_moved_to_trash_albums, items.size)
            else -> context.getString(R.string.msg_moved_to_trash, items.size)
        }
        messageScope.launch {
            apply(writer.trash(items, true)) {
                onDone()
                say(message) { messageScope.launch { apply(writer.trash(items, false)) {} } }
            }
        }
    }

    /**
     * Favorite, or not (SPEC §5.4, §6.5). In the viewer the heart shows it; from a selection, which ends then
     * (owner's test of S4a: as in the family's other apps), the snackbar says it ([say]).
     */
    fun favorite(items: List<MediaItem>, favorite: Boolean, say: Boolean = false, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        val message = context.getString(if (favorite) R.string.msg_favorited else R.string.msg_unfavorited, items.size)
        messageScope.launch {
            apply(writer.favorite(items, favorite)) {
                onDone()
                if (say) say(message, undo = null)
            }
        }
    }

    /** Back from Trash (SPEC §11.1): no Undo, they can be trashed again. */
    fun restore(items: List<MediaItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        val message = if (items.size == 1) context.getString(R.string.msg_restored_one) else context.getString(R.string.msg_restored, items.size)
        messageScope.launch {
            apply(writer.trash(items, false)) {
                onDone()
                say(message, undo = null)
            }
        }
    }

    /** For good (SPEC §11.2), once the family's confirmation was given: no Undo. */
    fun deleteForever(items: List<MediaItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        messageScope.launch {
            apply(writer.deleteForever(items)) {
                onDone()
                // Deleted for good: out of every user album (SPEC-albums §9.12)
                messageScope.launch { (context.applicationContext as GalleryApplication).albums.forget(items.map { it.id }) }
            }
        }
    }

    /** Says [message] in the family's short snackbar; [undo] adds Undo. */
    fun say(message: String, undo: (() -> Unit)?) = say(message, context.getString(R.string.button_undo), undo)

    /** Says [message] with an action of its own ([label], e.g. Show), or none. */
    fun say(message: String, label: String, action: (() -> Unit)?) {
        messageScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(message, actionLabel = action?.let { label }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }
}

/** The shell's [MediaActions], for every grid and the viewer. */
val LocalMediaActions = staticCompositionLocalOf<MediaActions> { error("No media actions provided") }

/** Opens a user album's page (the snackbar's Show, Info's chips): over the viewer when it is open, else on the current tab. */
val LocalOpenAlbum = staticCompositionLocalOf<(Long) -> Unit> { {} }
