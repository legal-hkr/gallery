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

    /** Shares from an outside viewer, its token: its sheet's location toggle comes back to it ([ShareSheet.chooser]). */
    var outside: String? = null

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
                    start(ShareSheet.chooser(context, app.sharing, prepared, items.map { it.id }, remove, outside))
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

    /** Runs [then] once the change is done: at once, or after the system's dialog was accepted; a refusal runs [otherwise], a failure is said. */
    fun apply(change: MediaWriter.Change, otherwise: () -> Unit = {}, then: () -> Unit) {
        when (change) {
            MediaWriter.Change.Done -> then()
            is MediaWriter.Change.Ask -> consent.ask(change.request, then, otherwise)
            // Said, with Show and what the system answered (owner's test before v0.1.1: TEST_TR3.mov stayed, without a word)
            is MediaWriter.Change.Failed -> fail(context.getString(R.string.error_change_failed), change.details)
        }
    }

    /**
     * To Trash or back from it, [then] once the items are really there: the system's yes is checked, a file it left is
     * moved here, and a failure is said instead of "Moved to Trash" (owner's test before v0.1.1).
     */
    private suspend fun trashChecked(items: List<MediaItem>, trashed: Boolean, then: (List<MediaItem>) -> Unit) {
        val failed: (String) -> Unit = { details -> fail(context.getString(R.string.error_change_failed), details) }
        // Some stayed: those that went still get [then] – their Undo, the selection's end – and the rest is said
        // (review since v0.1.0: nothing of the moved ones was told, the viewer took them for deleted elsewhere)
        suspend fun partly(moving: List<MediaItem>, details: String) {
            val stayed = writer.leftBehind(moving, trashed).mapTo(HashSet()) { it.id }
            val went = moving.filter { it.id !in stayed }
            if (went.isNotEmpty()) then(went)
            failed(details)
        }
        suspend fun move(moving: List<MediaItem>) {
            apply(writer.trash(moving, trashed)) {
                messageScope.launch {
                    // The system's yes checked: what it left is moved here, with the right to write it asked first – its
                    // yes to Trash gives none (owner's test before v0.1.1: RecoverableSecurityException)
                    val left = writer.leftBehind(moving, trashed)
                    if (left.isEmpty()) {
                        then(moving)
                        return@launch
                    }
                    // A refusal said too: the others went, these stayed (review before v0.1.1: nothing was shown)
                    apply(writer.writeChange(left), otherwise = { messageScope.launch { partly(moving, left.joinToString("\n") { "${it.name}: not moved" }) } }) {
                        messageScope.launch {
                            val error = writer.trashDirectly(left, trashed)
                            if (error == null) then(moving) else partly(moving, error)
                        }
                    }
                }
            }
        }
        // A file MediaStore typed against its name can't go to Trash or back (Android 17: a QuickTime .mov typed
        // video/mp4 – owner's test before v0.1.1): its type set right first, with the right to write it (one more
        // question from the system, none with Media management)
        val wrong = writer.mistyped(items)
        if (wrong.isEmpty()) {
            move(items)
            return
        }
        apply(writer.writeChange(wrong.keys.toList())) {
            messageScope.launch {
                // Handed on with their types as they are now, so Undo doesn't find them wrong again (review before v0.1.1)
                writer.retype(wrong)?.let(failed) ?: move(items.map { item -> wrong[item]?.let { item.copy(mime = it) } ?: item })
            }
        }
    }


    /** To Trash at once, with Undo (SPEC §5.5); [onDone] when they are there (a selection ends, the viewer knows it moved them). */
    fun trash(items: List<MediaItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        // Any of them in a user album: the message says they left every album too (SPEC-albums §5.5)
        val members = (context.applicationContext as GalleryApplication).albums.members.value.orEmpty().mapTo(HashSet()) { it.mediaId }
        messageScope.launch {
            trashChecked(items, true) { moved ->
                // What went, counted (some may have stayed)
                val inAlbums = moved.any { it.id in members }
                val message = when {
                    moved.size == 1 && inAlbums -> context.getString(R.string.msg_moved_to_trash_one_albums)
                    moved.size == 1 -> context.getString(R.string.msg_moved_to_trash_one)
                    inAlbums -> context.getString(R.string.msg_moved_to_trash_albums, moved.size)
                    else -> context.getString(R.string.msg_moved_to_trash, moved.size)
                }
                onDone()
                say(message) { messageScope.launch { trashChecked(moved, false) {} } }
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
            trashChecked(items, false) {
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

    /** A failure in the family's way: [message] with Show, which opens [details] (S8c's import and export). */
    fun fail(message: String, details: String) {
        messageScope.reportFailure(snackbars, message, details, context.getString(R.string.button_show), showError)
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
