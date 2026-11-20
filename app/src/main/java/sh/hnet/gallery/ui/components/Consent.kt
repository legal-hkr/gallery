package sh.hnet.gallery.ui.components

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The system's consent to change other apps' files (SPEC §10.6, §22.4): launches a MediaStore
 * request and runs what follows only when the user agreed; a refusal changes nothing and says
 * nothing. Held by the shell, so an Undo still asks after the screen that started it is gone.
 */
class Consent internal constructor(private val launch: (PendingIntent, () -> Unit, () -> Unit) -> Unit) {
    fun ask(request: PendingIntent, then: () -> Unit) = launch(request, then) {}

    /** As [ask], with [otherwise] on a refusal (moving into the vault takes its copies back). */
    fun ask(request: PendingIntent, then: () -> Unit, otherwise: () -> Unit) = launch(request, then, otherwise)

    /** The user agreed in the system's dialog (the second offer of Media management follows the first time, SPEC §10.6). */
    var onConfirmed: () -> Unit = {}
}

val LocalConsent = staticCompositionLocalOf<Consent> { error("No consent provided") }

@Composable
fun rememberConsent(): Consent {
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val refused = remember { arrayOfNulls<() -> Unit>(1) }
    val holder = remember { arrayOfNulls<Consent>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val then = pending[0]
        val otherwise = refused[0]
        pending[0] = null
        refused[0] = null
        if (result.resultCode == Activity.RESULT_OK) {
            then?.invoke()
            holder[0]?.onConfirmed?.invoke()
        } else {
            otherwise?.invoke()
        }
    }
    return remember(launcher) {
        Consent { request, then, otherwise ->
            pending[0] = then
            refused[0] = otherwise
            launcher.launch(IntentSenderRequest.Builder(request).build())
        }.also { holder[0] = it }
    }
}
