package sh.hnet.gallery.ui.share

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.chooser.ChooserAction
import sh.hnet.gallery.MainActivity
import sh.hnet.gallery.R
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

    fun chooser(context: Context, maker: ShareMaker, prepared: ShareMaker.Prepared.Ready, ids: List<Long>, removeLocation: Boolean): Intent {
        val chooser = Intent.createChooser(maker.sendIntent(prepared), null)
        val plan = prepared.plan
        if (plan.located == 0) return chooser
        // Back to the app's own activity, which opens the sheet anew – every sheet from there, as the first one: a sheet opened by
        // a trampoline activity that finished at once broke at the next toggle (owner's test of S5a). Clearing the top takes
        // the old sheet away and hands the request to the running activity, its viewer and grids as they were
        val toggle = Intent(context, MainActivity::class.java)
            .setAction(ACTION_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_IDS, ids.toLongArray())
            .putExtra(EXTRA_REMOVE, !removeLocation)
        val pending = PendingIntent.getActivity(context, ids.hashCode(), toggle, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
