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
class Consent internal constructor(private val launch: (PendingIntent, () -> Unit) -> Unit) {
    fun ask(request: PendingIntent, then: () -> Unit) = launch(request, then)
}

val LocalConsent = staticCompositionLocalOf<Consent> { error("No consent provided") }

@Composable
fun rememberConsent(): Consent {
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val then = pending[0]
        pending[0] = null
        if (result.resultCode == Activity.RESULT_OK) then?.invoke()
    }
    return remember(launcher) {
        Consent { request, then ->
            pending[0] = then
            launcher.launch(IntentSenderRequest.Builder(request).build())
        }
    }
}
