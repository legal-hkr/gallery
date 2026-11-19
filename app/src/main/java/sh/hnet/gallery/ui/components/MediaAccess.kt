package sh.hnet.gallery.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/** What is remembered about the first run and the questions asked in it; kept through Reset and out of the backup. */
private fun Context.firstRun() = getSharedPreferences("first_run", Context.MODE_PRIVATE)

/** The first run (SPEC §19) and the media permissions, as Contacts' contacts access. */
object MediaAccess {
    private const val WIZARD_DONE = "wizard_done"
    private const val REQUESTED = "media_requested"

    /**
     * Asked together (SPEC §19.1): the system offers all, some or none of the photos and videos
     * (the partial choice needs `READ_MEDIA_VISUAL_USER_SELECTED` in the request), then the location in them.
     */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

    /** Whether the first-run wizard was gone through; it is shown once, before the timeline. */
    fun wizardDone(context: Context): Boolean = context.firstRun().getBoolean(WIZARD_DONE, false)

    fun markWizardDone(context: Context) = context.firstRun().edit { putBoolean(WIZARD_DONE, true) }

    /**
     * Whether the system would still show its permission dialog: before the first request, and after
     * one refusal. After the second the system answers for the user, and only its settings page helps (SPEC §4.7).
     */
    fun mayAsk(context: Context, activity: Activity?): Boolean =
        !context.firstRun().getBoolean(REQUESTED, false) ||
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) == true ||
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_VIDEO) == true

    internal fun markRequested(context: Context) = context.firstRun().edit { putBoolean(REQUESTED, true) }

    /** The app's page in the system settings, where Permissions › Photos and videos is. */
    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Asks for the media permissions in the system's dialog; with partial access already given the
 * system shows its selection again (SPEC §4.7). [onAnswer] runs after the user answered, whatever the answer.
 */
@Composable
fun rememberMediaAccessAsk(onAnswer: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAnswer() }
    return {
        MediaAccess.markRequested(context)
        launcher.launch(MediaAccess.PERMISSIONS)
    }
}
