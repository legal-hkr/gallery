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
import android.media.MediaMetadataRetriever
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
import kotlin.random.Random
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
import sh.hnet.gallery.model.FrameQueue
import sh.hnet.gallery.model.Library
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.OnThisDay
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.model.WidgetPick

/**
 * The app's widgets (S11, SPEC §21, LOGIKA §22): On this day and Frame. Drawn with plain remote views, the system's colour
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
    fun placed(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return PROVIDERS.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    private val PROVIDERS = listOf(OnThisDayWidget::class.java, FrameWidget::class.java)

    private suspend fun draw(app: GalleryApplication) {
        val manager = AppWidgetManager.getInstance(app)
        val days = manager.getAppWidgetIds(ComponentName(app, OnThisDayWidget::class.java))
        val frames = manager.getAppWidgetIds(ComponentName(app, FrameWidget::class.java))
        if (days.isEmpty() && frames.isEmpty()) {
            WidgetJob.cancelAll(app)
            return
        }
        val access = app.media.access() != Access.NONE
        if (!access) {
            for (id in days + frames) manager.updateAppWidget(id, state(app, R.drawable.ic_no_photography, R.string.widget_no_access, openApp(app)))
            WidgetJob.cancelAll(app)
            return
        }
        WidgetJob.ensure(app)
        // Each kind on its own: a failure of one never keeps the other from being drawn, nor the Frames' next change
        // from being set (review of S11)
        try {
            if (days.isNotEmpty()) drawDays(app, manager, days)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        var next: Long? = null
        try {
            if (frames.isNotEmpty()) next = drawFrames(app, manager, frames)
        } finally {
            WidgetJob.scheduleFrames(app, next ?: if (frames.isNotEmpty()) System.currentTimeMillis() + RETRY else null)
        }
    }

    /** On this day's widgets. */
    private suspend fun drawDays(app: GalleryApplication, manager: AppWidgetManager, ids: IntArray) {
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
     * The Frames (S11, SPEC §21.1): each one's photo, changed when its time is up or when it left the album; a state when
     * the album is empty or gone (a tap chooses another). The earliest next change among them, for the job, or null.
     */
    private suspend fun drawFrames(app: GalleryApplication, manager: AppWidgetManager, ids: IntArray): Long? {
        var earliest: Long? = null
        fun at(time: Long) {
            earliest = minOf(earliest ?: time, time)
        }
        val now = System.currentTimeMillis()
        for (id in ids) {
            try {
                // Without its choice (placed and its configuration left, or the app's data cleared): a tap chooses
                val frame = Frames.get(app, id)
                if (frame == null) {
                    manager.updateAppWidget(id, state(app, R.drawable.ic_photo_library, R.string.widget_frame_setup, configure(app, id)))
                    continue
                }
                // The sources' states not read in time, or the media's ask failed (a card taken out): as it is, tried
                // again in a few minutes, rather than "empty" or a hidden folder's photo
                val content = withContext(Dispatchers.IO) { Frames.content(app, frame.album) }
                if (content == null) {
                    at(now + RETRY)
                    continue
                }
                val items = (content as? Frames.Content.Items)?.items
                when {
                    items == null -> manager.updateAppWidget(id, state(app, R.drawable.ic_hide_image, R.string.widget_album_deleted, configure(app, id)))
                    items.isEmpty() -> manager.updateAppWidget(id, state(app, R.drawable.ic_photo_library, R.string.widget_album_empty, configure(app, id)))
                    else -> {
                        val next = FrameQueue.step(frame, items.map { it.id }, now, Random.Default)
                        if (next != frame) Frames.advance(app, id, frame, next)
                        val shown = items.first { it.id == next.current }
                        val picture = withContext(Dispatchers.IO) { picture(app, shown, manager.getAppWidgetOptions(id)) }
                        // The viewer over the album around the photo: a few hundred either side, not the whole album – its
                        // ids went with the remote views and a large folder's passed the binder's limit (review of S11)
                        val index = items.indexOf(shown)
                        val around = items.subList((index - AROUND).coerceAtLeast(0), (index + AROUND + 1).coerceAtMost(items.size)).map { it.id }
                        manager.updateAppWidget(id, RemoteViews(app.packageName, R.layout.widget_on_this_day).apply {
                            if (picture != null) setImageViewBitmap(R.id.photo, picture) else setImageViewResource(R.id.photo, R.drawable.widget_placeholder)
                            setViewVisibility(R.id.label, View.GONE)
                            setOnClickPendingIntent(R.id.photo, openFrame(app, id, frame.album, around, shown.id))
                        })
                        at(next.nextAt)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // This one tried again soon; the others go on
                at(now + RETRY)
            }
        }
        return earliest
    }

    /** A photo, or a video's frame, at the widget's size, centre-cropped; null when it can't be read. */
    private fun picture(context: Context, item: MediaItem, options: Bundle): Bitmap? = if (item.video) videoFrame(context, item, options) else photo(context, item, options)

    /** The widget's box in pixels, as on a phone held upright (its least width, its greatest height), at most [LONGEST]. */
    private fun box(context: Context, options: Bundle): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val boxW = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250) * density
        val boxH = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110) * density
        val fit = minOf(1f, LONGEST / max(boxW, boxH))
        return (boxW * fit).roundToInt().coerceAtLeast(1) to (boxH * fit).roundToInt().coerceAtLeast(1)
    }

    /** A video's first frame covering the box, its middle cut out. */
    private fun videoFrame(context: Context, item: MediaItem, options: Bundle): Bitmap? {
        val (width, height) = box(context, options)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, item.uri())
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: return null
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: return null
            if (rotation % 180 != 0) w = h.also { h = w }
            // Asked to fit within the size that covers the box: its scaled frame then covers it exactly
            val scale = max(width / w, height / h)
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * scale).roundToInt().coerceAtLeast(width), (h * scale).roundToInt().coerceAtLeast(height)) ?: return null
            val cropW = minOf(width, frame.width)
            val cropH = minOf(height, frame.height)
            val cut = Bitmap.createBitmap(frame, (frame.width - cropW) / 2, (frame.height - cropH) / 2, cropW, cropH)
            // The whole frame given back at once: several video Frames drawn together held them all
            if (cut !== frame) frame.recycle()
            cut
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
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
        // At most [LONGEST] on the longer side: the binder's limit for remote views holds
        val (width, height) = box(context, options)
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

    /** A Frame's setup again (its album emptied or deleted): the widget's configuration, its address its own. */
    private fun configure(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, FrameConfigActivity::class.java)
            .setData(Uri.Builder().scheme("gallery-widget").authority("frame-setup").appendPath(id.toString()).build())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The viewer over a Frame's album, at its photo; the address its own per widget and photo. */
    private fun openFrame(context: Context, id: Int, album: String, ids: List<Long>, start: Long): PendingIntent {
        val intent = WidgetOpenActivity.intent(context, ids, start, album)
            .setData(Uri.Builder().scheme("gallery-widget").authority("frame").appendPath(id.toString()).appendPath(start.toString()).build())
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The widget's photo at most this many pixels on its longer side (a phone's width, sharp across 4 cells). */
    private const val LONGEST = 1440f

    /** A Frame that couldn't be drawn is tried again after this long. */
    private const val RETRY = 5 * 60_000L

    /** How many of a Frame's album either side of its photo a tap opens the viewer over. */
    private const val AROUND = 200

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

/** Frame (SPEC §21.1), 2 × 2 by default, resizable; set up by [FrameConfigActivity] when placed and from the launcher. */
class FrameWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** Resized: the photo is read at the new size. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    /** Removed: its album and round go with it. */
    override fun onDeleted(context: Context, ids: IntArray) = Frames.forget(context, ids)

    /** Restored by the launcher with new ids: the choices follow them (review of S11). */
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) = Frames.move(context, oldIds, newIds)

    override fun onDisabled(context: Context) = redraw(context)

    private fun redraw(context: Context) {
        val pending = goAsync()
        Widgets.update(context) { pending.finish() }
    }
}

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
                // (the Frames' tick is set by the redraw itself, at the earliest next change)
                if (Widgets.placed(app)) {
                    if (params.jobId == CHANGES) scheduleChanges(app) else if (params.jobId == MIDNIGHT) scheduleMidnight(app)
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
        private const val FRAMES = 3

        /**
         * The Frames' next change, at [at] (epoch ms) – the earliest among them –, or none. About then (D43): the scheduler
         * may run it some minutes late, and later still while the phone dozes.
         */
        fun scheduleFrames(context: Context, at: Long?) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (at == null) {
                scheduler.cancel(FRAMES)
                return
            }
            val wait = (at - System.currentTimeMillis()).coerceAtLeast(60_000)
            val job = JobInfo.Builder(FRAMES, ComponentName(context, WidgetJob::class.java))
                .setMinimumLatency(wait)
                .setOverrideDeadline(wait + 15 * 60_000)
                .build()
            schedule(context, job)
        }

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
            scheduler.cancel(FRAMES)
        }

        private fun scheduleChanges(context: Context) {
            val job = JobInfo.Builder(CHANGES, ComponentName(context, WidgetJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                // A Frame shows videos too (review since v0.1.0)
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
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
