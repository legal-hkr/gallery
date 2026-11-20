package sh.hnet.gallery.ui.vault

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Collections
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.VaultEntry
import sh.hnet.gallery.ui.components.Consent
import sh.hnet.gallery.ui.components.MediaActions

/** The system's prompt for the vault's key (SPEC §18.2): a fingerprint, or the screen lock's PIN, pattern or password. */
object VaultPrompt {
    fun show(activity: Activity, title: String, cipher: Cipher, onPassed: (Cipher) -> Unit, onCancelled: () -> Unit = {}) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onPassed) ?: onCancelled()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onCancelled()
            },
        )
    }

    /** Whether the phone has a screen lock: without one no key can need the user (SPEC §18.2's Set screen lock). */
    fun secure(activity: Activity): Boolean = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/**
 * The vault's actions from anywhere in the app (S8b): opening it with the prompt, and moving items in – the question,
 * the prompt when locked, the encrypting with the status card, then the originals deleted (others' files through the
 * system's dialog; refused, the copies are taken back and nothing changed). Held by the shell, as [MediaActions].
 */
class VaultActions(
    private val activity: Activity,
    private val vault: Vault,
    private val writer: MediaWriter,
    private val consent: Consent,
    private val actions: MediaActions,
    private val scope: CoroutineScope,
) {
    private val app = activity.application as GalleryApplication

    /** Opens the vault's page (not set up, or its key lost: the moving can't go on); set by the shell. */
    var openVault: () -> Unit = {}

    /** The items asked about (the question's dialog), with what follows a move. */
    var asking by mutableStateOf<Pair<List<MediaItem>, () -> Unit>?>(null)
        private set

    /** Moving in, importing or exporting: done of all, while it runs (the shell's status card). */
    data class Progress(val kind: Kind, val done: Int, val total: Int)

    enum class Kind { MOVING, IMPORTING, EXPORTING, TAKING_OUT }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Runs [then] with the vault open: at once, or after the prompt; nothing when cancelled or the key is lost. */
    fun open(then: () -> Unit, cancelled: () -> Unit = {}) {
        if (vault.state.value is Vault.State.Open) return then()
        val cipher = vault.prepareUnlock() ?: return cancelled()
        VaultPrompt.show(activity, activity.getString(R.string.prompt_unlock_vault), cipher, onPassed = { passed ->
            scope.launch { if (vault.unlocked(passed)) then() else cancelled() }
        }, onCancelled = cancelled)
    }

    /** Move to vault (SPEC §18.5): asks first; [onDone] once they are in (a selection ends, the viewer drops the item). */
    fun moveIn(items: List<MediaItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        when (vault.state.value) {
            // The vault isn't there to take them: its page (setup, or the lost key's card)
            Vault.State.NotSetUp, is Vault.State.Lost -> openVault()
            else -> asking = items to onDone
        }
    }

    fun dismiss() {
        asking = null
    }

    /** The question answered with Move. */
    fun confirm() {
        val (items, onDone) = asking ?: return
        asking = null
        // Its key found lost on the way: the vault's page with the card, rather than nothing at all
        open(then = { move(items, onDone) }, cancelled = { if (vault.state.value is Vault.State.Lost) openVault() })
    }

    /**
     * Items back in the gallery and the albums they were put back in (SPEC-albums §18.3): the Add to album sheet then
     * opens with No album first, which takes them out of those albums again.
     */
    data class Returned(val items: List<MediaItem>, val putBack: List<Pair<Long, List<Long>>>)

    var returned by mutableStateOf<Returned?>(null)
        private set

    /**
     * Move out / Copy out of the vault (SPEC §18.3, SPEC-albums §18.3): each file to the folder it came from (else
     * `Download/`), then back in the user albums it was in; moving deletes the vault's copy only after the write
     * succeeded. [onDone] once they are out (a selection ends, the viewer drops the page).
     */
    fun takeOut(entries: List<VaultEntry>, move: Boolean, onDone: () -> Unit = {}) {
        // An item already on its way out isn't taken out twice (review before v0.1.0: a second tap wrote two copies)
        val these = entries.filter { it.file !in outgoing }
        if (these.isEmpty()) return
        // The key held to the end: the screen going off during a long copy locks the vault, not this
        val held = vault.hold() ?: return
        outgoing.addAll(these.map { it.file })
        scope.launch {
            try {
                takeOut(these, move, held, onDone)
            } finally {
                outgoing.removeAll(these.map { it.file }.toSet())
                _progress.value = null
            }
        }
    }

    /** Items on their way out now. */
    private val outgoing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private suspend fun takeOut(entries: List<VaultEntry>, move: Boolean, held: javax.crypto.SecretKey, onDone: () -> Unit) {
        val written = ArrayList<Pair<VaultEntry, Long>>()
        val failed = ArrayList<String>()
        // The status card, as for every long vault flow (review before v0.1.0: none while a large video went out)
        _progress.value = Progress(Kind.TAKING_OUT, 0, entries.size)
        for ((done, entry) in entries.withIndex()) {
            _progress.value = Progress(Kind.TAKING_OUT, done, entries.size)
            val id = try {
                val (reader, stream) = vault.open(entry, held)
                stream.use { writer.saveFromVault(entry) { reader.stream() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.add(entry.name + ": " + e.javaClass.simpleName)
                continue
            }
            if (id != null) written.add(entry to id) else failed.add(entry.name)
        }
        _progress.value = null
        if (written.isEmpty()) return actions.fail(activity.getString(R.string.error_take_out_vault), failed.joinToString("\n"))
        if (move) vault.remove(written.map { it.first }, held)
        onDone()
        val ids = written.map { it.second }
        // Some not taken out: said, with Show naming them (review before v0.1.0: only the ones that went were counted)
        if (failed.isNotEmpty()) actions.fail(activity.getString(R.string.error_take_out_some, failed.size), failed.joinToString("\n"))
        else actions.say(activity.getString(if (move) R.string.msg_moved_out_vault else R.string.msg_copied_out_vault, written.size), undo = null)
        // The index learns of the new files in a moment; the sheet needs them as items
        val items = withTimeoutOrNull(5000) { app.index.items.first { list -> list != null && ids.all { id -> list.any { it.id == id } } } }
            ?.filter { it.id in ids }.orEmpty()
        if (items.isEmpty()) return
        val byId = items.associateBy { it.id }
        val albums = app.albums.albums.value.orEmpty().mapTo(HashSet()) { it.id }
        val putBack = written.flatMap { (entry, id) -> entry.albums.filter { it in albums }.map { it to id } }
            .groupBy({ it.first }, { it.second })
        val now = System.currentTimeMillis()
        for ((album, members) in putBack) app.albums.add(album, members.mapNotNull { byId[it] }, now)
        returned = Returned(items, putBack.toList())
    }

    /** The sheet after moving out closed; [noAlbum]: it was No album, so out of the albums they were put back in. */
    fun returnedDone(noAlbum: Boolean) {
        val done = returned ?: return
        returned = null
        if (noAlbum) scope.launch { for ((album, ids) in done.putBack) app.albums.remove(album, ids) }
    }

    // Import (SPEC §18.6): the system's picker of documents, the question with Delete originals, then the encrypting

    /** Opens the system's picker of documents (the shell's launcher); set by the shell. */
    var pickFiles: () -> Unit = {}

    /** Opens the system's picker of a folder (the shell's launcher); set by the shell. */
    var pickFolder: () -> Unit = {}

    /** Files picked, waiting for the question's answer. */
    var importing by mutableStateOf<List<Uri>?>(null)
        private set

    /** Import's button on the vault's page. */
    fun askImport() {
        vault.picking = true
        pickFiles()
    }

    /** The picker's answer: the question for the files, none when it was cancelled. */
    fun picked(uris: List<Uri>) {
        vault.picking = false
        importing = uris.takeIf { it.isNotEmpty() }
    }

    fun dismissImport() {
        importing = null
    }

    /** The question answered with Import; [deleteOriginals] as its checkbox (off by default). */
    fun confirmImport(deleteOriginals: Boolean) {
        val uris = importing ?: return
        importing = null
        open(then = {
            scope.launch {
                _progress.value = Progress(Kind.IMPORTING, 0, uris.size)
                val (added, failures) = try {
                    vault.importFiles(uris) { done -> _progress.value = Progress(Kind.IMPORTING, done, uris.size) }
                } catch (e: Exception) {
                    // Locked between the prompt and the start: nothing imported, said as such – not a crash
                    emptyList<VaultEntry>() to listOf(e.javaClass.simpleName)
                } finally {
                    _progress.value = null
                }
                if (failures.isNotEmpty()) {
                    actions.fail(activity.getString(R.string.error_import_some, failures.size), failures.joinToString("\n"))
                } else {
                    actions.say(activity.getString(R.string.msg_imported, added.size), undo = null)
                    // Only once all are in, and where the provider can (SPEC §18.6)
                    if (deleteOriginals) {
                        val kept = withContext(Dispatchers.IO) {
                            uris.count { uri ->
                                try {
                                    !DocumentsContract.deleteDocument(activity.contentResolver, uri)
                                } catch (_: Exception) {
                                    true
                                }
                            }
                        }
                        if (kept > 0) actions.say(activity.getString(R.string.msg_originals_kept, kept), undo = null)
                    }
                }
            }
        })
    }

    // Export (SPEC §18.7): the question, the system's picker of a folder, then decrypted copies there

    /** Items to export, waiting for the question's answer or the folder. */
    var exporting by mutableStateOf<List<VaultEntry>?>(null)
        private set
    private var exportPending: List<VaultEntry>? = null

    fun askExport(entries: List<VaultEntry>, onDone: () -> Unit = {}) {
        if (entries.isEmpty()) return
        exporting = entries
        exportDone = onDone
    }

    private var exportDone: () -> Unit = {}

    fun dismissExport() {
        exporting = null
    }

    /** The question answered with Choose folder. */
    fun confirmExport() {
        exportPending = exporting
        exporting = null
        vault.picking = true
        pickFolder()
    }

    /** The folder's picker answered: the copies written there, none when it was cancelled. */
    fun folderPicked(tree: Uri?) {
        vault.picking = false
        val entries = exportPending ?: return
        exportPending = null
        if (tree == null) return
        exportDone()
        // The vault may have locked while the picker was open (the screen went off): the prompt first (review before
        // v0.1.0: the export crashed the app)
        open(then = { export(entries, tree) })
    }

    private fun export(entries: List<VaultEntry>, tree: Uri) {
        scope.launch {
            _progress.value = Progress(Kind.EXPORTING, 0, entries.size)
            val failures = try {
                vault.export(entries, tree) { done -> _progress.value = Progress(Kind.EXPORTING, done, entries.size) }
            } catch (e: Exception) {
                entries.map { it.name + ": " + e.javaClass.simpleName }
            } finally {
                _progress.value = null
            }
            if (failures.isNotEmpty()) actions.fail(activity.getString(R.string.error_export_some, failures.size), failures.joinToString("\n"))
            else actions.say(activity.getString(R.string.msg_exported, entries.size), undo = null)
        }
    }

    private fun move(items: List<MediaItem>, onDone: () -> Unit) {
        if (!vault.fits(items)) return actions.say(activity.getString(R.string.error_vault_space), undo = null)
        // The key held to the end, past the system's dialog and the screen going off: taking copies back must still work
        val held = vault.hold() ?: return
        scope.launch {
            _progress.value = Progress(Kind.MOVING, 0, items.size)
            val entries = try {
                val members = app.albums.members.value.orEmpty()
                vault.moveIn(items, app.facts.facts.value.orEmpty(), { id -> members.filter { it.mediaId == id }.map { it.albumId } }, app.media.canLocate()) { done ->
                    _progress.value = Progress(Kind.MOVING, done, items.size)
                }
            } catch (_: Exception) {
                _progress.value = null
                actions.say(activity.getString(R.string.error_move_to_vault), undo = null)
                return@launch
            }
            _progress.value = null
            val finish = {
                onDone()
                // Gone from the gallery: out of every user album, remembered in the vault for moving out (SPEC-albums §18.3)
                scope.launch { app.albums.forget(items.map { it.id }) }
                actions.say(activity.getString(R.string.msg_moved_to_vault, items.size), undo = null)
            }
            val takeBack = { scope.launch { vault.remove(entries, held) } }
            when (val change = writer.deleteForever(items)) {
                MediaWriter.Change.Done -> finish()
                is MediaWriter.Change.Ask -> consent.ask(change.request, then = finish, otherwise = { takeBack() })
                is MediaWriter.Change.Failed -> {
                    // Own files are deleted one by one: those already gone keep their copy, the rest take theirs back
                    // (review before v0.1.0: one failing delete took back the copies of photos already deleted)
                    val left = writer.stillThere(items)
                    val kept = items.indices.filter { items[it].id !in left }
                    scope.launch { vault.remove(items.indices.filter { items[it].id in left }.map { entries[it] }, held) }
                    if (kept.isNotEmpty()) scope.launch { app.albums.forget(kept.map { items[it].id }) }
                    actions.say(activity.getString(R.string.error_move_to_vault), undo = null)
                }
            }
        }
    }
}

/** The shell's [VaultActions]; none outside the main activity (another app's viewer, the picker). */
val LocalVaultActions = staticCompositionLocalOf<VaultActions?> { null }
