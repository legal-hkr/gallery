package sh.hnet.gallery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.gallery.about.WhatsNew
import sh.hnet.gallery.ui.GalleryApp
import sh.hnet.gallery.ui.share.ShareSheet
import sh.hnet.gallery.ui.theme.GalleryTheme

/** How long the vault stays open while its own system picker is over the app. */
private const val PICKING_GRACE_MS = 5 * 60_000L

class MainActivity : ComponentActivity() {

    // Dark mode or the language switched in place (configChanges has uiMode and locale, so nothing on screen is lost –
    // review before v0.1.0; the owner: a change of language closed the editor and reset the map): the system bars'
    // icons follow the mode again
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) WhatsNew.recordStart(this)
        setContent {
            GalleryTheme {
                GalleryApp()
            }
        }
        handle(intent)
    }

    // The vault locks once the app is in the background for the chosen delay (SPEC §18.4); coming back in time keeps it open
    override fun onStart() {
        super.onStart()
        (application as GalleryApplication).vault.cancelLock()
    }

    override fun onStop() {
        super.onStop()
        val app = application as GalleryApplication
        // A full-screen prompt of the screen lock stops the activity too: a vault that is still locked then has nothing to lock
        // At once, with the delay kept in the vault: a read launched here could come after the user was back and lock
        // the vault in use (review before v0.1.0)
        // Over the vault's own system picker, a grace of five minutes at least: leaving for it isn't leaving the app, but
        // leaving the app from it must not leave the vault open for good (review before v0.1.0)
        if (!isChangingConfigurations) app.vault.lockAfter(if (app.vault.picking) maxOf(app.vault.lockDelayMs, PICKING_GRACE_MS) else app.vault.lockDelayMs)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * The share sheet's location action (SPEC §14.2): the same items shared again with the other choice; a folder's
     * album opened from the outside viewer (its Show, Info's folder row).
     */
    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_FOLDER) {
            intent.getStringExtra(EXTRA_FOLDER)?.let { (application as GalleryApplication).openFolder.value = it }
            setIntent(Intent())
            return
        }
        if (intent?.action != ShareSheet.ACTION_TOGGLE) return
        // Only the app's own share sheet: the token is this process's secret
        if (intent.getStringExtra(ShareSheet.EXTRA_TOKEN) != ShareSheet.token) {
            setIntent(Intent())
            return
        }
        val ids = intent.getLongArrayExtra(ShareSheet.EXTRA_IDS)?.toList() ?: return
        (application as GalleryApplication).shareAgain.value = ShareAgain(ids, intent.getBooleanExtra(ShareSheet.EXTRA_REMOVE, true))
        setIntent(Intent())
    }

    companion object {
        private const val ACTION_OPEN_FOLDER = "sh.hnet.gallery.OPEN_FOLDER"
        private const val EXTRA_FOLDER = "sh.hnet.gallery.FOLDER"

        /** The app at a folder's album, from the outside viewer. */
        fun openFolder(context: Context, folder: String): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_FOLDER).putExtra(EXTRA_FOLDER, folder)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
