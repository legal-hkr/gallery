package sh.hnet.gallery

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

    /** The share sheet's location action (SPEC §14.2): the same items shared again with the other choice. */
    private fun handle(intent: Intent?) {
        if (intent?.action != ShareSheet.ACTION_TOGGLE) return
        val ids = intent.getLongArrayExtra(ShareSheet.EXTRA_IDS)?.toList() ?: return
        (application as GalleryApplication).shareAgain.value = ShareAgain(ids, intent.getBooleanExtra(ShareSheet.EXTRA_REMOVE, true))
        setIntent(Intent())
    }
}
