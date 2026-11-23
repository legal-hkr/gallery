package sh.hnet.gallery.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.MainActivity

/**
 * A widget's tap (S11): hands its photos to the app and opens it. Not exported – only the app's own widgets' pending
 * intents reach it –, so no other app can have the viewer opened over photos it names, hidden ones too (review of S11:
 * the main activity, exported for the launcher, took the request itself). Shows nothing; ends at once.
 */
class WidgetOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList()
        val start = intent.getLongExtra(EXTRA_START, -1)
        if (!ids.isNullOrEmpty() && start in ids) (application as GalleryApplication).openPhotos.value = ids to start
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        private const val EXTRA_IDS = "sh.hnet.gallery.widget.IDS"
        private const val EXTRA_START = "sh.hnet.gallery.widget.START"

        fun intent(context: Context, ids: List<Long>, start: Long): Intent =
            Intent(context, WidgetOpenActivity::class.java).putExtra(EXTRA_IDS, ids.toLongArray()).putExtra(EXTRA_START, start).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
