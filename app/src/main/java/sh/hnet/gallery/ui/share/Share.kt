package sh.hnet.gallery.ui.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.chooser.ChooserAction
import java.util.UUID
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.MainActivity
import sh.hnet.gallery.R
import sh.hnet.gallery.ShareAgain
import sh.hnet.gallery.data.ShareMaker

/**
 * The system's share sheet for a prepared share (SPEC §14.2): when any item had a location, one action that prepares
 * it again with the other choice – Include location or Remove location – and the line under the preview saying which
 * holds, with the files whose location can't be removed (D18).
 */
object ShareSheet {
    const val ACTION_TOGGLE = "sh.hnet.gallery.share.TOGGLE"
    const val EXTRA_IDS = "sh.hnet.gallery.share.IDS"
    const val EXTRA_REMOVE = "sh.hnet.gallery.share.REMOVE"
    const val EXTRA_TOKEN = "sh.hnet.gallery.share.TOKEN"
    const val EXTRA_VIEWER = "sh.hnet.gallery.share.VIEWER"

    /**
     * This process's secret for the toggle: the main activity is exported for the launcher, so without it any app could
     * have it share chosen items with their location (review before v0.1.0). A sheet of an earlier process toggles nothing.
     */
    val token: String = UUID.randomUUID().toString()

    /**
     * @param outside Shared from a viewer another app opened (`ViewerActivity`, a task of its own), this viewer's token:
     *   the toggle comes back to it through [ShareToggleReceiver] – sent to the main activity, it opened the sheet in the
     *   app's main task (review before v0.1.0, fixed after v0.1.1)
     */
    fun chooser(context: Context, maker: ShareMaker, prepared: ShareMaker.Prepared.Ready, ids: List<Long>, removeLocation: Boolean, outside: String? = null): Intent {
        val chooser = Intent.createChooser(maker.sendIntent(prepared), null)
        val plan = prepared.plan
        // Files from outside can't be shared again by the main activity (their grant was the outside viewer's): their
        // share takes Settings' choice as it is, without the toggle (review before v0.1.0)
        if (plan.located == 0 || ids.any { it < 0 }) return chooser
        // Back to the app's own activity, which opens the sheet anew – every sheet from there, as the first one: a sheet opened by
        // a trampoline activity that finished at once broke at the next toggle (owner's test of S5a). Clearing the top takes
        // the old sheet away and hands the request to the running activity, its viewer and grids as they were
        val toggle = Intent(context, MainActivity::class.java)
            .setAction(ACTION_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_IDS, ids.toLongArray())
            .putExtra(EXTRA_REMOVE, !removeLocation)
            .putExtra(EXTRA_TOKEN, token)
        val pending = if (outside != null) {
            // To the app's own receiver (not exported: no process token needed); that outside viewer shares anew itself
            val again = Intent(context, ShareToggleReceiver::class.java)
                .putExtra(EXTRA_IDS, ids.toLongArray())
                .putExtra(EXTRA_REMOVE, !removeLocation)
                .putExtra(EXTRA_VIEWER, outside)
            PendingIntent.getBroadcast(context, ids.hashCode(), again, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(context, ids.hashCode(), toggle, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val action = ChooserAction.Builder(
            Icon.createWithResource(context, if (removeLocation) R.drawable.ic_location_on else R.drawable.ic_location_off),
            context.getString(if (removeLocation) R.string.action_include_location else R.string.action_remove_location),
            pending,
        ).build()
        chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
        val line = if (removeLocation) {
            // Each sentence only when it says something: none removed (all of them can't be) leaves the first out (owner, 19 Nov 2026)
            val removed = plan.located - plan.kept
            listOfNotNull(
                context.getString(R.string.meta_location_removed, removed).takeIf { removed > 0 },
                context.getString(R.string.meta_location_kept, plan.kept).takeIf { plan.kept > 0 },
            ).joinToString(" ")
        } else {
            context.getString(R.string.meta_location_included)
        }
        // The line under the preview is Android 15's; on 14 the action's own name tells the choice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) chooser.putExtra(Intent.EXTRA_METADATA_TEXT, line)
        return chooser
    }
}

/** The share sheet's location toggle for a share from the outside viewer: the request for that viewer to share again. */
class ShareToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getLongArrayExtra(ShareSheet.EXTRA_IDS)?.toList() ?: return
        val viewer = intent.getStringExtra(ShareSheet.EXTRA_VIEWER) ?: return
        (context.applicationContext as GalleryApplication).shareAgainOutside.value = viewer to ShareAgain(ids, intent.getBooleanExtra(ShareSheet.EXTRA_REMOVE, true))
    }
}
