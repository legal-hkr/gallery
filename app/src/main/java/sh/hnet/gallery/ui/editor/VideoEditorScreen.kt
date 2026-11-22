@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.editor

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.ExportException
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.VideoTrimmer
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.History
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Trim
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.StatusCard
import sh.hnet.gallery.ui.components.Thumbnails
import sh.hnet.gallery.ui.components.TooltipIconButton

/**
 * The video editor (SPEC §15.7, §15.9; LOGIKA §20.6–§20.7): an overlay over the viewer like the photo editor, upright
 * while open, with one tool – Trim – so no tabs. The video above (a SurfaceView, so HDR shows as HDR), the strip of its
 * frames with the kept part's handles under it, then play and the times. Each handle let go is a step of Undo; Reset
 * all brings the whole video back. Saving runs on Media3's Transformer behind the status card, which can cancel it.
 *
 * @param onCopySaved A copy saved: the viewer goes on to it
 */
@SuppressLint("SourceLockedOrientationActivity")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(item: MediaItem, onClose: () -> Unit, onCopySaved: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val actions = LocalMediaActions.current
    val consent = LocalConsent.current
    val locale = LocalLocale.current.platformLocale
    val scope = rememberCoroutineScope()
    val trimmer = remember { VideoTrimmer(context.applicationContext) }
    val located = remember { app.media.canLocate() }

    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
    }

    // The editor's own player: the selection plays with its sound, frames shown exactly where a handle is
    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            playWhenReady = false
            setMediaItem(PlayerItem.fromUri(item.uri()))
            prepare()
        }
    }
    val seeker = remember { ExactSeeker(player) }
    var playing by remember { mutableStateOf(false) }
    // The video's length from the player once it is ready – the media's own can be rounded or missing
    var history by remember { mutableStateOf<History<Trim>?>(null) }
    var track by remember { mutableStateOf<Format?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && history == null) {
                    val length = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    if (length != null) history = History(Trim.whole(length))
                }
                // A selection up to the video's end loops too: back to its start, still playing
                if (playbackState == Player.STATE_ENDED) {
                    val start = history?.present?.startMs
                    if (start != null && player.playWhenReady) player.seekTo(start) else player.pause()
                }
                seeker.settled()
            }

            override fun onRenderedFirstFrame() = seeker.settled()

            // A video the phone can't play (no decoder – TR2's 10-bit HEVC on the emulator): said, and the editor goes,
            // rather than waiting for ever (review of S9b)
            override fun onPlayerError(error: PlaybackException) {
                if (history == null) {
                    actions.say(resources.getString(R.string.error_cannot_display), undo = null)
                    onClose()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)?.let { track = it }
            }

            // Wanted rather than actually playing: the short stop at each loop's seek doesn't flicker the button
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    // A handle under the finger: shown, made a step when let go
    var live by remember { mutableStateOf<Trim?>(null) }
    val trim = live ?: history?.present
    var position by remember { mutableLongStateOf(0L) }
    var leaving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    // A save waiting on the question about SDR (D35): true for Replace, false for a copy
    var askingSdr by remember { mutableStateOf<Boolean?>(null) }
    // An HDR video this phone can neither keep HDR nor bring down to SDR: said at once, nothing tried
    var cantSave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // What Save needs to know (HDR, an HDR encoder) from the video track as the player read it
    val facts by androidx.compose.runtime.produceState<VideoTrimmer.Facts?>(null, track) { value = track?.let { trimmer.facts(item, it) } }

    // The playhead follows the video; the selection plays in a loop (SPEC §15.7)
    val trimNow by rememberUpdatedState(trim)
    LaunchedEffect(playing) {
        while (true) {
            withFrameMillis { }
            val now = player.currentPosition
            val t = trimNow
            if (playing && t != null && now >= t.endMs) player.seekTo(t.startMs)
            if (!seeker.busy) position = now
            if (!playing) break
        }
    }

    fun togglePlay() {
        val t = trim ?: return
        if (player.playWhenReady) {
            player.pause()
            return
        }
        // From the start of the selection when the playhead is outside it or at its end
        if (position < t.startMs || position >= t.endMs - 50) {
            player.seekTo(t.startMs)
            position = t.startMs
        }
        player.play()
    }

    fun step(next: History<Trim>) {
        history = next
        // The playhead kept inside the kept part
        val t = next.present
        if (position !in t.startMs..t.endMs) {
            player.seekTo(t.startMs)
            position = t.startMs
        }
    }

    fun leave() = when {
        saving -> {}
        history?.present?.changed == true -> leaving = true
        else -> onClose()
    }
    BackHandler(onBack = ::leave)

    // With Show and the details (Transformer's error code, the causes): what failed can be told back (owner's test of S9b)
    fun failed(error: Throwable? = null) {
        saving = false
        val message = resources.getString(R.string.error_save_failed, item.name)
        if (error == null) actions.say(message, undo = null) else actions.fail(message, detailsOf(error))
    }

    fun save(replace: Boolean, sdr: Boolean) {
        val t = history?.present ?: return
        val sdrMode = if (sdr) facts?.sdrMode else null
        player.pause()
        saving = true
        progress = null
        job = scope.launch {
            try {
                if (!replace) {
                    val suffix = resources.getString(R.string.file_suffix_edited)
                    val taken = MediaWriter(context).namesBeside(item, app.index.items.value.orEmpty()) + item.name
                    val id = trimmer.saveCopy(item, t, sdrMode, suffix, taken, located) { progress = it }
                    if (id == null) {
                        failed()
                        return@launch
                    }
                    // Saved: what follows isn't cancelled any more
                    withContext(NonCancellable) {
                        withTimeoutOrNull(3_000) { app.index.items.first { list -> list.orEmpty().any { it.id == id } } }
                        saving = false
                        actions.say(resources.getString(R.string.msg_copy_saved), undo = null)
                        onCopySaved(id)
                    }
                } else {
                    trimmer.replace(item, t, sdrMode, located) { progress = it }
                    saving = false
                    actions.say(resources.getString(R.string.msg_original_replaced), undo = null)
                    onClose()
                }
            } catch (e: CancellationException) {
                // Cancelled on the card: nothing was written, the editing goes on
                saving = false
                throw e
            } catch (e: Exception) {
                failed(e)
            } finally {
                job = null
            }
        }
    }

    // Replace is confirmed first (the family's destructive dialog), then the system may ask too
    var replaceSdr by remember { mutableStateOf(false) }

    fun proceedReplace(sdr: Boolean) {
        val request = MediaWriter(context).writeRequest(listOf(item))
        if (request == null) save(replace = true, sdr) else consent.ask(request) { save(replace = true, sdr) }
    }

    fun proceed(replace: Boolean, sdr: Boolean) {
        if (!replace) {
            save(replace = false, sdr)
            return
        }
        replaceSdr = sdr
        replacing = true
    }

    /** Save's way chosen: first the question about SDR when the phone can't keep this video HDR (D35). */
    fun choose(replace: Boolean) {
        val f = facts
        when {
            f == null || !f.hdr || f.hdrSavable -> proceed(replace, sdr = false)
            f.sdrMode != null -> askingSdr = replace
            else -> cantSave = true
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Top bar (SPEC §15.1): Close, Undo, Redo, Reset all, Save
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val h = history
                TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_close), ::leave, enabled = !saving)
                Spacer(Modifier.weight(1f))
                TooltipIconButton(R.drawable.ic_undo, stringResource(R.string.content_description_undo), { h?.let { step(it.undo()) } }, enabled = h?.canUndo == true && !saving)
                TooltipIconButton(R.drawable.ic_redo, stringResource(R.string.content_description_redo), { h?.let { step(it.redo()) } }, enabled = h?.canRedo == true && !saving)
                TooltipIconButton(
                    R.drawable.ic_restart_alt,
                    stringResource(R.string.content_description_reset_all),
                    { h?.let { step(it.push(Trim.whole(it.present.durationMs))) } },
                    enabled = h?.present?.changed == true && !saving,
                )
                Box {
                    Button(onClick = { menu = true }, enabled = h?.present?.changed == true && !saving && facts != null, modifier = Modifier.padding(horizontal = 8.dp).height(40.dp)) {
                        if (saving) {
                            LoadingIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.button_saving))
                        } else {
                            Text(stringResource(R.string.button_save))
                        }
                    }
                    SaveMenu(
                        menu,
                        motion = false,
                        canReplace = trimmer.canReplace(item),
                        located = located,
                        onDismiss = { menu = false },
                        onCopy = {
                            menu = false
                            choose(replace = false)
                        },
                        onReplace = {
                            menu = false
                            choose(replace = true)
                        },
                        formatNote = R.string.desc_replace_format_video,
                        locationNote = R.string.desc_replace_location_video,
                    )
                }
            }
            // The video, fitted; its frame over the surface until the first picture is drawn. Not held while saving: the
            // status card's Cancel is here
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val px = Thumbnails.sizeFor(Int.MAX_VALUE)
                MediaThumbnail(item, px, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                val presentation = rememberPresentationState(player)
                val size = presentation.videoSizeDp
                val fitted = if (size != null && size.isSpecified && size.width > 0f && size.height > 0f) Modifier.aspectRatio(size.width / size.height) else Modifier.fillMaxSize()
                PlayerSurface(player, fitted, SURFACE_TYPE_SURFACE_VIEW)
                if (presentation.coverSurface) MediaThumbnail(item, px, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                if (saving) {
                    val p = progress
                    StatusCard(
                        title = if (p == null) stringResource(R.string.status_saving_video_unknown) else stringResource(R.string.status_saving_video, (p * 100).roundToInt()),
                        line = item.name,
                        progress = p,
                        // Cancel only while the video is being made: what follows writes the file (review of S9b)
                        onCancel = if (p == null || p < 1f) ({ job?.cancel() }) else null,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).holdWhile(saving).navigationBarsPadding().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val t = trim
                if (t != null) {
                    TrimStrip(
                        item,
                        t,
                        position,
                        onLive = { next, at ->
                            if (player.playWhenReady) player.pause()
                            live = next
                            position = at
                            seeker.to(at)
                        },
                        onCommit = { next ->
                            live = null
                            history?.let { history = it.push(next) }
                        },
                        onSeek = { at ->
                            position = at
                            seeker.to(at)
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val label = stringResource(if (playing) R.string.content_description_pause else R.string.content_description_play)
                        FilledIconButton(onClick = ::togglePlay, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(if (playing) R.drawable.ic_pause_filled else R.drawable.ic_play_arrow_filled), contentDescription = label)
                        }
                        Spacer(Modifier.width(12.dp))
                        val style = MaterialTheme.typography.labelLarge
                        Text(Trim.timeText(t.startMs, locale), style = style)
                        Text(
                            stringResource(R.string.label_trim_length, Trim.timeText(t.lengthMs, locale)),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        Text(Trim.timeText(t.endMs, locale), style = style)
                    }
                } else {
                    // The player not ready yet: the strip's place kept
                    Box(Modifier.fillMaxWidth().height(56.dp + 52.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.title_discard_changes)) },
            text = { Text(stringResource(R.string.desc_discard_video)) },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.button_keep_editing)) } },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onClose()
                }) { Text(stringResource(R.string.button_discard)) }
            },
        )
    }
    askingSdr?.let { replace ->
        AlertDialog(
            onDismissRequest = { askingSdr = null },
            title = { Text(stringResource(R.string.title_save_sdr)) },
            text = { Text(stringResource(R.string.desc_save_sdr)) },
            dismissButton = { TextButton(onClick = { askingSdr = null }) { Text(stringResource(R.string.button_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    askingSdr = null
                    proceed(replace, sdr = true)
                }) { Text(stringResource(R.string.button_save_sdr)) }
            },
        )
    }
    if (cantSave) {
        AlertDialog(
            onDismissRequest = { cantSave = false },
            title = { Text(stringResource(R.string.title_cant_save_video)) },
            text = { Text(stringResource(R.string.desc_cant_save_hdr)) },
            confirmButton = { TextButton(onClick = { cantSave = false }) { Text(stringResource(R.string.button_close)) } },
        )
    }
    if (replacing) {
        ConfirmDestructiveDialog(
            R.drawable.ic_save,
            stringResource(R.string.title_replace_original),
            AnnotatedString(stringResource(R.string.desc_replace_original_confirm, item.name)),
            stringResource(R.string.button_replace),
            onConfirm = {
                replacing = false
                proceedReplace(replaceSdr)
            },
            onDismiss = { replacing = false },
        )
    }
}

/** A failure's details: Transformer's error code, then each cause's type and message. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun detailsOf(error: Throwable): String = buildString {
    if (error is ExportException) appendLine(error.errorCodeName)
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 6) {
        appendLine("${cause.javaClass.simpleName}: ${cause.message.orEmpty()}")
        cause = cause.cause
        depth++
    }
}.trim()

/**
 * Seeks to exact frames while a handle or the playhead is dragged, one at a time: a new place waits until the last one
 * is shown, and only the latest waiting is kept – exact seeks queued one after another fell behind the finger.
 */
private class ExactSeeker(private val player: ExoPlayer) {
    var busy = false
        private set
    private var pending: Long? = null
    private var since = 0L

    fun to(ms: Long) {
        pending = ms
        // A seek that never reported back doesn't hold the next one for long
        if (!busy || System.currentTimeMillis() - since > 500) next()
    }

    fun settled() {
        busy = false
        next()
    }

    private fun next() {
        val ms = pending ?: return
        pending = null
        busy = true
        since = System.currentTimeMillis()
        player.seekTo(ms)
    }
}

/**
 * The strip of frames (SPEC §15.7): 56 dp high, corners 12 dp, the frames laid between the handles' room at the ends;
 * the kept part framed in `primary` – 3 dp above and below, 14 dp handles outside its ends –, the rest under the
 * `scrim` at 32 %, a 3 dp `on-surface` playhead. A handle follows the finger (the preview shows its frame); elsewhere
 * the finger moves the playhead within the kept part.
 */
@Composable
private fun TrimStrip(item: MediaItem, trim: Trim, position: Long, onLive: (Trim, Long) -> Unit, onCommit: (Trim) -> Unit, onSeek: (Long) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val handle = with(density) { 14.dp.toPx() }
    val edge = with(density) { 3.dp.toPx() }
    val reach = with(density) { 24.dp.toPx() }
    val heightPx = with(density) { 56.dp.roundToPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val inner = (size.width - 2 * handle).coerceAtLeast(1f)
    val duration = trim.durationMs
    fun xOf(ms: Long) = handle + Trim.xOf(ms, inner, duration)

    // The frames, taken from the file once the strip's width is known: their number from the first one's shape
    val frames = remember(item.id) { mutableStateListOf<ImageBitmap?>() }
    LaunchedEffect(item.id, size.width) {
        if (size.width == 0 || frames.isNotEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri())
                val first = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, heightPx * 2, heightPx) ?: return@withContext
                val aspect = first.width.toFloat() / first.height.coerceAtLeast(1)
                val count = ceil(inner / (heightPx * aspect)).toInt().coerceIn(1, 30)
                val slot = (inner / count).roundToInt().coerceAtLeast(1)
                withContext(Dispatchers.Main) { repeat(count) { frames.add(null) } }
                Trim.frameTimes(duration, count).forEachIndexed { i, ms ->
                    val frame = retriever.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, slot.coerceAtLeast(heightPx), heightPx)?.asImageBitmap()
                    withContext(Dispatchers.Main) { if (i < frames.size) frames[i] = frame }
                }
            } catch (_: Exception) {
                // No frames: the strip stays plain, the handles still work
            } finally {
                retriever.release()
            }
        }
    }

    val trimNow by rememberUpdatedState(trim)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHighest)
            .onSizeChanged { size = it }
            // The handles lie by the screen's edges: the system's Back swipe took them (owner's test of S9b)
            .systemGestureExclusion()
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = trimNow
                    val x0 = down.position.x
                    val left = handle + Trim.xOf(start.startMs, inner, duration) - handle / 2
                    val right = handle + Trim.xOf(start.endMs, inner, duration) + handle / 2
                    // Which part the finger took: the nearer handle within reach, else the playhead
                    val target = when {
                        abs(x0 - left) <= reach && abs(x0 - left) <= abs(x0 - right) -> 0
                        abs(x0 - right) <= reach -> 1
                        else -> 2
                    }
                    down.consume()
                    var last = start
                    // The cut stays where it is when a handle is only touched: the finger's offset from it is kept (a
                    // handle's middle lies half a handle outside the cut – touching it moved the cut, review of S9b)
                    val offset = when (target) {
                        0 -> handle + Trim.xOf(start.startMs, inner, duration) - x0
                        1 -> handle + Trim.xOf(start.endMs, inner, duration) - x0
                        else -> 0f
                    }
                    fun follow(x: Float) {
                        val ms = Trim.msAt(x + offset - handle, inner, duration)
                        when (target) {
                            0 -> {
                                last = start.withStart(ms)
                                onLive(last, last.startMs)
                            }
                            1 -> {
                                last = start.withEnd(ms)
                                onLive(last, last.endMs)
                            }
                            else -> onSeek(ms.coerceIn(start.startMs, start.endMs))
                        }
                    }
                    follow(x0)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        follow(change.position.x)
                        change.consume()
                    }
                    // An unchanged trim adds no step (History.push keeps the same state as it is)
                    if (target != 2) onCommit(last)
                }
            },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = with(density) { handle.toDp() })) {
            frames.forEach { frame ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (frame != null) Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        val scrim = scheme.scrim.copy(alpha = 0.32f)
        val primary = scheme.primary
        val onPrimary = scheme.onPrimary
        val head = scheme.onSurface
        Canvas(Modifier.fillMaxSize()) {
            val xs = xOf(trim.startMs)
            val xe = xOf(trim.endMs)
            // Outside the kept part, the handles' room included
            drawRect(scrim, Offset(0f, 0f), Size((xs - handle).coerceAtLeast(0f), size.height.toFloat()))
            drawRect(scrim, Offset(xe + handle, 0f), Size((size.width - xe - handle).coerceAtLeast(0f), size.height.toFloat()))
            // The frame: 3 dp above and below, the handles at its ends
            drawRect(primary, Offset(xs, 0f), Size(xe - xs, edge))
            drawRect(primary, Offset(xs, size.height - edge), Size(xe - xs, edge))
            val radius = CornerRadius(with(density) { 4.dp.toPx() })
            drawRoundRect(primary, Offset(xs - handle, 0f), Size(handle, size.height.toFloat()), radius)
            drawRoundRect(primary, Offset(xe, 0f), Size(handle, size.height.toFloat()), radius)
            // A grip on each handle
            val grip = Size(with(density) { 2.dp.toPx() }, size.height * 0.3f)
            drawRoundRect(onPrimary, Offset(xs - handle / 2 - grip.width / 2, (size.height - grip.height) / 2), grip, CornerRadius(grip.width / 2))
            drawRoundRect(onPrimary, Offset(xe + handle / 2 - grip.width / 2, (size.height - grip.height) / 2), grip, CornerRadius(grip.width / 2))
            // The playhead
            val xp = xOf(position.coerceIn(0, duration))
            drawRect(head, Offset(xp - edge / 2, 0f), Size(edge, size.height.toFloat()))
        }
    }
}
