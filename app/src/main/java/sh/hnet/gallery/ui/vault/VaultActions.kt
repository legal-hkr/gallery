package sh.hnet.gallery.ui.vault

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.MediaItem
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

    /** Moving in: done of all, while it runs (the shell's status card). */
    data class Progress(val done: Int, val total: Int)

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

    private fun move(items: List<MediaItem>, onDone: () -> Unit) {
        if (!vault.fits(items)) return actions.say(activity.getString(R.string.error_vault_space), undo = null)
        scope.launch {
            _progress.value = Progress(0, items.size)
            val entries = try {
                val members = app.albums.members.value.orEmpty()
                vault.moveIn(items, app.facts.facts.value.orEmpty(), { id -> members.filter { it.mediaId == id }.map { it.albumId } }, app.media.canLocate()) { done ->
                    _progress.value = Progress(done, items.size)
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
            val takeBack = { scope.launch { vault.remove(entries) } }
            when (val change = writer.deleteForever(items)) {
                MediaWriter.Change.Done -> finish()
                is MediaWriter.Change.Ask -> consent.ask(change.request, then = finish, otherwise = { takeBack() })
                is MediaWriter.Change.Failed -> {
                    takeBack()
                    actions.say(activity.getString(R.string.error_move_to_vault), undo = null)
                }
            }
        }
    }
}

/** The shell's [VaultActions]; none outside the main activity (another app's viewer, the picker). */
val LocalVaultActions = staticCompositionLocalOf<VaultActions?> { null }
