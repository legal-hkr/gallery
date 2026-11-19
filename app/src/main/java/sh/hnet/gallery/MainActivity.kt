package sh.hnet.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.gallery.about.WhatsNew
import sh.hnet.gallery.ui.GalleryApp
import sh.hnet.gallery.ui.share.ShareSheet
import sh.hnet.gallery.ui.theme.GalleryTheme

class MainActivity : ComponentActivity() {
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
