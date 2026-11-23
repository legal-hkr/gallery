package sh.hnet.gallery.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.MainActivity
import sh.hnet.gallery.R
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.OnThisDay
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.model.WidgetPick

/**
 * The app's widgets (S11, SPEC §21, LOGIKA §22): On this day so far. Drawn with plain remote views, the system's colour
 * roles resolved by the launcher (as the family's widgets). Their data is asked of the media directly – today's day in
 * earlier years, a few days around it –, never the whole index: with the app closed nothing else is read (D4).
 */
object Widgets {
    /** Asked for a redraw; several requests waiting become one (as Contacts'). */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val waiting = ConcurrentLinkedQueue<() -> Unit>()
    private val started = AtomicBoolean(false)

    /**
     * Redraws every placed widget, one redraw at a time; requests that come meanwhile are met by one redraw after it.
     *
     * @param done Called once a redraw that started after this request has finished: a receiver or a job keeps its
     *   process until then
     */
    fun update(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext as GalleryApplication
        done?.let { waiting += it }
        if (started.compareAndSet(false, true)) {
            app.appScope.launch {
                for (request in requests) {
                    val answered = generateSequence { waiting.poll() }.toList()
                    try {
                        draw(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Drawn again at the next request; a widget must never take the app down
                    } finally {
                        answered.forEach { runCatching(it) }
                    }
                }
            }
        }
        requests.trySend(Unit)
    }

    /** As [update], returning when the redraw is done: a job that started it ends only then. */
    suspend fun updateAndWait(context: Context) = suspendCancellableCoroutine { continuation -> update(context) { continuation.resume(Unit) } }

    /** Whether any of the app's widgets is on a home screen. */
    fun placed(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, OnThisDayWidget::class.java)).isNotEmpty()

    private suspend fun draw(app: GalleryApplication) {
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, OnThisDayWidget::class.java))
        if (ids.isEmpty()) {
            WidgetJob.cancelAll(app)
            return
        }
        val access = app.media.access() != Access.NONE
        if (!access) {
            for (id in ids) manager.updateAppWidget(id, state(app, R.drawable.ic_no_photography, R.string.widget_no_access, openApp(app)))
            WidgetJob.cancelAll(app)
            return
        }
        WidgetJob.ensure(app)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Without the sources' states the timeline can't be told: the widgets stay as they are, rather than show a
        // hidden folder's photo (review of S11)
        val timeline = timelineOf(app) ?: return
        val near = withContext(Dispatchers.IO) { timeline(app.media.photosTakenIn(OnThisDay.windows(today, zone))) }
        val nearOffsets = app.offsetsOf(near.map { it.id })
        val screenshot: (MediaItem) -> Boolean = { TimelineFolders.isScreenshots(it.path) }
        // Any photo of the timeline only when the days around today give none (screenshots and days just outside the
        // week don't count – review of S11): the newest few hundred will do
        val none = WidgetPick.of(near, emptyList(), today, zone, { nearOffsets[it] }, screenshot, 0) == WidgetPick.Nothing
        val recent = if (none) withContext(Dispatchers.IO) { timeline(app.media.newestPhotos(RECENT)) } else emptyList()
        val offsets = nearOffsets + app.offsetsOf(recent.map { it.id })
        for (id in ids) {
            val pick = WidgetPick.of(near, recent, today, zone, { offsets[it] }, screenshot, WidgetPick.seed(today, id))
            manager.updateAppWidget(id, onThisDay(app, manager.getAppWidgetOptions(id), pick, today))
        }
    }

    /**
     * The photos of the timeline among a list: the sources In Photos, album covers left out, as the app's own; null when
     * the sources' states can't be read in time.
     */
    private suspend fun timelineOf(app: GalleryApplication): ((List<MediaItem>) -> List<MediaItem>)? {
        val rows = withTimeoutOrNull(10_000) { app.sources.rows.filterNotNull().first() } ?: return null
        val newFolders = app.preferences.newFoldersInPhotos.first()
        return { items -> Library.of(items) { SourceStore.stateOf(it, rows, newFolders) }.timeline }
    }

    private fun onThisDay(context: Context, options: Bundle, pick: WidgetPick, today: LocalDate): RemoteViews {
        val shown = when (pick) {
            is WidgetPick.Today -> pick.shown[pick.index]
            is WidgetPick.Around -> pick.shown[pick.index]
            is WidgetPick.AnyDay -> pick.item
            WidgetPick.Nothing -> return state(context, R.drawable.ic_photo_library, R.string.msg_no_media, openApp(context))
        }
        val label = when (pick) {
            is WidgetPick.Today -> context.resources.getQuantityString(R.plurals.label_years_ago, pick.yearsAgo, pick.yearsAgo)
            is WidgetPick.Around -> context.getString(R.string.label_around_this_day, context.resources.getQuantityString(R.plurals.label_years_ago, pick.yearsAgo, pick.yearsAgo))
            is WidgetPick.AnyDay -> {
                val locale = context.resources.configuration.locales[0]
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(pick.date)
            }
            WidgetPick.Nothing -> ""
        }
        val photo = photo(context, shown, options)
        return RemoteViews(context.packageName, R.layout.widget_on_this_day).apply {
            if (photo != null) setImageViewBitmap(R.id.photo, photo) else setImageViewResource(R.id.photo, R.drawable.widget_placeholder)
            setTextViewText(R.id.label, label)
            setContentDescription(R.id.photo, label)
            setOnClickPendingIntent(R.id.photo, openPhotos(context, pick.shown.map { it.id }, shown.id, today))
        }
    }

    /**
     * The photo decoded from its file to the widget's size and shape, centre-cropped (a remote view's bitmap must stay
     * small); null when it can't be read. Not the media's thumbnail: its cache is small, and a wide widget showed it
     * stretched and blurred (owner's test of S11). The widget's size as on a phone held upright (as Contacts'): its
     * least width and greatest height.
     */
    private fun photo(context: Context, item: MediaItem, options: Bundle): Bitmap? = try {
        val density = context.resources.displayMetrics.density
        val boxW = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250) * density
        val boxH = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110) * density
        // At most [LONGEST] on the longer side: the binder's limit for remote views holds
        val fit = minOf(1f, LONGEST / max(boxW, boxH))
        val width = (boxW * fit).roundToInt().coerceAtLeast(1)
        val height = (boxH * fit).roundToInt().coerceAtLeast(1)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri())) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Scaled to cover the box, then its middle cut out (the file's orientation applied by the decoder)
            val scale = max(width.toFloat() / info.size.width, height.toFloat() / info.size.height)
            val scaledW = (info.size.width * scale).roundToInt().coerceAtLeast(width)
            val scaledH = (info.size.height * scale).roundToInt().coerceAtLeast(height)
            decoder.setTargetSize(scaledW, scaledH)
            val left = (scaledW - width) / 2
            val top = (scaledH - height) / 2
            decoder.crop = Rect(left, top, left + width, top + height)
        }
    } catch (_: Exception) {
        null
    }

    /** A state on a card (text needs it, SPEC §21.3): an icon and one sentence; the tap does [action]. */
    private fun state(context: Context, @DrawableRes icon: Int, @StringRes text: Int, action: PendingIntent): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_state).apply {
            val sentence = context.getString(text)
            setImageViewResource(R.id.state_icon, icon)
            setTextViewText(R.id.state_text, sentence)
            setViewVisibility(R.id.state_icon, View.VISIBLE)
            setContentDescription(R.id.state_piece, sentence)
            setOnClickPendingIntent(R.id.state_piece, action)
        }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The viewer over the widget's photos, at the one it shows; the address tells each day's intent apart. */
    private fun openPhotos(context: Context, ids: List<Long>, start: Long, today: LocalDate): PendingIntent {
        val intent = WidgetOpenActivity.intent(context, ids, start)
            .setData(Uri.Builder().scheme("gallery-widget").authority("on-this-day").appendPath(today.toString()).appendPath(start.toString()).build())
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The widget's photo at most this many pixels on its longer side (a phone's width, sharp across 4 cells). */
    private const val LONGEST = 1440f

    /** How many of the newest photos the last fallback chooses among. */
    private const val RECENT = 300
}

/** On this day (SPEC §21.2), 4 × 2 by default, resizable; no configuration. */
class OnThisDayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** Resized: the photo is read at the new size. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    /** The last one removed: the redraw finds no widget and stops the jobs. */
    override fun onDisabled(context: Context) = redraw(context)

    private fun redraw(context: Context) {
        val pending = goAsync()
        Widgets.update(context) { pending.finish() }
    }
}

/** The system's broadcasts the widgets follow. */
private val SYSTEM = setOf(Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)

/** A change of language, zone or clock while the app is closed: the widgets' words and day follow. */
class WidgetSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SYSTEM) return
        // Another zone or clock: midnight is elsewhere now – the waiting job, set for the old one, is set again (review of S11)
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) WidgetJob.resetMidnight(context)
        val pending = goAsync()
        Widgets.update(context) { pending.finish() }
    }
}

/**
 * Redraws the widgets while the app is closed: after a change of the media (a photo trashed, moved to the vault, taken
 * – the system runs the job then), and just after midnight (the day's photo). Each job runs once, so it is set again
 * each time while widgets are placed (as Contacts'). Times are the scheduler's: about midnight, not to the second.
 */
class WidgetJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as GalleryApplication
        app.appScope.launch {
            try {
                Widgets.updateAndWait(app)
            } finally {
                // Set again before this run is given back: a job still running counts as waiting, so [ensure] would leave it
                if (Widgets.placed(app)) {
                    if (params.jobId == CHANGES) scheduleChanges(app) else scheduleMidnight(app)
                }
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val CHANGES = 1
        private const val MIDNIGHT = 2

        /** Sets the jobs unless they are waiting already. */
        fun ensure(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(CHANGES) == null) scheduleChanges(context)
            if (scheduler.getPendingJob(MIDNIGHT) == null) scheduleMidnight(context)
        }

        /** Midnight set again (a change of zone or clock): the redraw that follows sets it at the new one. */
        fun resetMidnight(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(MIDNIGHT)

        fun cancelAll(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(CHANGES)
            scheduler.cancel(MIDNIGHT)
        }

        private fun scheduleChanges(context: Context) {
            val job = JobInfo.Builder(CHANGES, ComponentName(context, WidgetJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                // A copy of many photos writes many rows: wait for it to settle, but not longer than ten seconds
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(10_000)
                .build()
            schedule(context, job)
        }

        private fun scheduleMidnight(context: Context) {
            val now = ZonedDateTime.now()
            // A minute after midnight, so the day has certainly changed; the scheduler may run it a little later
            val wait = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1)).toMillis().coerceAtLeast(60_000)
            val job = JobInfo.Builder(MIDNIGHT, ComponentName(context, WidgetJob::class.java))
                .setMinimumLatency(wait)
                .setOverrideDeadline(wait + 15 * 60_000)
                .build()
            schedule(context, job)
        }

        private fun schedule(context: Context, job: JobInfo) {
            try {
                context.getSystemService(JobScheduler::class.java).schedule(job)
            } catch (_: Exception) {
                // Refused: the widgets follow at the app's next start
            }
        }
    }
}
