package sh.hnet.gallery.ui.slideshow

import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.KenBurns
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.SlideTransition
import sh.hnet.gallery.model.Slideshow
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.Thumbnails
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.viewer.PhotoDecoder
import sh.hnet.gallery.ui.viewer.Picture

/**
 * A slideshow asked for (SPEC §16): the items in their list's order, from [start]; [title] is where it came from (the
 * position line), [onEnd] gets the item it stopped at (the viewer goes on from there).
 */
class SlideshowRequest(val ids: List<Long>, val start: Long, val title: String, val onEnd: (Long) -> Unit = {})

/** Starts a slideshow over everything (the shell's overlay); none where no show can open (another app's viewer). */
val LocalSlideshow = staticCompositionLocalOf<((SlideshowRequest) -> Unit)?> { null }

/** One slide on screen: its time (0…1) and its opacity; a newer one fades in as it fades out, then takes its place. */
private class Slide(val id: Long, val path: KenBurns, val turn: Int) {
    val clock = Animatable(0f)
    val alpha = Animatable(0f)
}

/** The slide's clock in real time even with the system's animations off (they would end it at once). */
private object RealTime : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** The controls hide this long after the last touch (as the viewer's chrome, the owner's 4 s; SPEC said 3 s). */
private const val CHROME_HIDE_MS = 4000L

/**
 * The slideshow (SPEC §16, LOGIKA §19.2): an overlay over the whole app (as the viewer and the editor), turning with the
 * phone, the screen kept on and the system bars hidden. Photos crossfade, or pan and zoom slowly (Ken Burns – not with
 * the system's animations off); the next photo is decoded during the present one's time. Videos play muted from their
 * start, up to 15 s. HDR goes on once for the whole show when any photo of it has a gain map (D29). A tap shows the
 * controls; Back ends the show.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SlideshowScreen(request: SlideshowRequest, onClose: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val activity = LocalActivity.current
    val window = activity?.window
    val view = LocalView.current
    val items by app.index.items.collectAsStateWithLifecycle()
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val byId = remember(items) { items.orEmpty().associateBy { it.id } }
    // What can't be shown – a file that doesn't decode, a video the phone can't play – leaves the show (owner's test of
    // S8a: the damaged X1 was shown as its broken mark)
    val unplayable = remember { mutableStateSetOf<Long>() }
    // While the index reads again everything counts as there; an item trashed, gone or unplayable drops out
    fun alive(id: Long): Boolean = id !in unplayable && (items == null || byId[id]?.let { !it.trashed } == true)

    // The system's animations off: crossfade only, without the fade's motion and without Ken Burns (SPEC §16)
    val moving = remember { ValueAnimator.areAnimatorsEnabled() }
    val longSide = LocalWindowInfo.current.containerSize.let { maxOf(it.width, it.height) }.coerceAtLeast(1)
    val thumbPx = Thumbnails.sizeFor(Int.MAX_VALUE)

    // This show's choices start from Settings › Slideshow
    var ready by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(Slideshow.DEFAULT_SECONDS) }
    var transition by remember { mutableStateOf(SlideTransition.CROSSFADE) }
    LaunchedEffect(Unit) {
        seconds = app.preferences.slideSeconds.first()
        transition = app.preferences.slideTransition.first()
        ready = true
    }
    var playing by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }

    var current by remember { mutableStateOf(Slideshow.first(request.ids, request.start, ::alive)) }
    // Every slide shown is a new turn, also when the only one left comes again
    var turn by remember { mutableIntStateOf(0) }
    val slides = remember { mutableStateListOf<Slide>() }
    val pictures = remember { mutableStateMapOf<Long, Picture>() }
    fun advance() {
        val now = current ?: return
        current = Slideshow.next(request.ids, now, ::alive)
        turn++
    }
    // Only from the slide shown now: a video's end and its clock may both come at once, and must not skip a slide
    fun advanceFrom(slide: Slide) {
        if (slide.turn == turn) advance()
    }
    fun close() = onClose(current ?: slides.lastOrNull()?.id ?: request.start)
    BackHandler { close() }

    // Nothing left to show (all deleted meanwhile): the show ends; the present slide gone: the next one now
    LaunchedEffect(current) { if (current == null) onClose(request.start) }
    LaunchedEffect(byId, unplayable.size) { current?.let { if (!alive(it)) advance() } }

    // Turns with the phone (the rotation lock respected, D1), the screen on, the bars away; all as before afterwards
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { if (before != null) activity.requestedOrientation = before }
    }
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val colorBefore = window?.colorMode
        onDispose {
            // Always back: a show starts where the bars are shown (a page, or the viewer with its menu open). Asking
            // whether they were shown counted the caption bar a phone hasn't, so the answer was always no and they stayed
            // hidden after the show (owner's test of S8a)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (colorBefore != null) window.colorMode = colorBefore
        }
    }
    // HDR once for the whole show (D29): a switch at every HDR slide would jump the brightness every few seconds
    val anyHdr = remember(facts) { request.ids.any { facts?.get(it)?.gainmap == true } }
    LaunchedEffect(anyHdr, window) { window?.colorMode = if (anyHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT }

    // One muted player for the show's videos, without audio focus (as the viewer's muted playback)
    val player = remember { ExoPlayer.Builder(context.applicationContext).build().apply { volume = 0f } }
    var videoTurn by remember { mutableIntStateOf(-1) }
    var videoEnded by remember { mutableIntStateOf(-1) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) videoEnded = videoTurn
            }

            override fun onPlayerError(error: PlaybackException) {
                slides.lastOrNull()?.takeIf { it.turn == videoTurn }?.let { unplayable.add(it.id) }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // A new slide: over the last, fading in (at once for the first and with animations off); the ones under it go after
    LaunchedEffect(turn, ready) {
        if (!ready) return@LaunchedEffect
        val id = current ?: return@LaunchedEffect
        val video = byId[id]?.video == true
        val slide = Slide(id, if (transition == SlideTransition.KEN_BURNS && moving && !video) KenBurns.random() else KenBurns.STILL, turn)
        val under = slides.toList()
        slides.add(slide)
        if (under.isEmpty() || !moving) {
            slide.alpha.snapTo(1f)
        } else {
            // A crossfade: the slides under it fade out as it fades in (owner's test of S8a: the old one stayed whole
            // under the new and went at once at the end – where their shapes differ it showed and vanished)
            coroutineScope {
                launch { slide.alpha.animateTo(1f, tween(Slideshow.FADE_MS)) }
                under.forEach { old -> launch { old.alpha.animateTo(0f, tween(Slideshow.FADE_MS)) } }
            }
        }
        while (slides.isNotEmpty() && slides.first() !== slide) slides.removeAt(0)
    }
    val top = slides.lastOrNull()
    val topItem = top?.let { byId[it.id] }

    // The present photo and the next one decoded at the screen's size, the rest let go
    val next = current?.let { Slideshow.next(request.ids, it, ::alive) }
    LaunchedEffect(current, next) {
        val wanted = slides.map { it.id }.toSet() + listOfNotNull(current, next)
        pictures.keys.filter { it !in wanted }.forEach { pictures.remove(it) }
        for (id in listOfNotNull(current, next)) {
            val item = byId[id] ?: continue
            if (item.video || pictures.containsKey(id)) continue
            val picture = PhotoDecoder.screen(context.contentResolver, item, longSide)
            // Found unreadable while it was the next one: skipped before it shows; while shown (the first): the next now
            if (picture is Picture.Broken) unplayable.add(id) else pictures[id] = picture
        }
    }

    // A video slide plays from its start; anything else stops the player
    LaunchedEffect(top) {
        val slide = top ?: return@LaunchedEffect
        val item = byId[slide.id]
        if (item?.video == true) {
            videoTurn = slide.turn
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri()))
            player.prepare()
            player.playWhenReady = playing
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }
    LaunchedEffect(playing) { player.playWhenReady = playing }

    // The slide's time, counted once its photo is there; paused, it waits where it is; a video ended early goes on at once
    val loaded = topItem != null && (topItem.video || pictures.containsKey(topItem.id))
    LaunchedEffect(top, playing, seconds, loaded, videoEnded) {
        val slide = top ?: return@LaunchedEffect
        val item = topItem ?: return@LaunchedEffect
        if (item.video && videoEnded == slide.turn) {
            advanceFrom(slide)
            return@LaunchedEffect
        }
        if (!playing || !loaded) return@LaunchedEffect
        val total = Slideshow.slideMs(item.video, item.duration, seconds)
        val left = ((1f - slide.clock.value) * total).toInt()
        if (left > 0) withContext(RealTime) { slide.clock.animateTo(1f, tween(left, easing = LinearEasing)) }
        advanceFrom(slide)
    }

    // The controls go 4 s after the last touch; the show doesn't pause for them
    LaunchedEffect(chrome, touched) {
        if (chrome) {
            delay(CHROME_HIDE_MS)
            chrome = false
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    chrome = !chrome
                    touched++
                })
            },
        ) {
            slides.forEach { slide ->
                val item = byId[slide.id] ?: return@forEach
                val live = slide === top && item.video
                SlideView(slide, item, pictures[slide.id], thumbPx, if (live) player else null)
            }

            // Any touch on the controls counts as a touch: they stay while used
            val keepShown = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched++
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).then(keepShown)) {
                val alive = request.ids.filter(::alive)
                val position = (current?.let { alive.indexOf(it) } ?: -1) + 1
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.title_slideshow), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (position > 0) Text(stringResource(R.string.format_slideshow_position, request.title, position, alive.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_end_slideshow), ::close) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            }
            AnimatedVisibility(
                chrome, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().offset(y = -FloatingToolbarDefaults.ScreenOffset).then(keepShown),
            ) {
                SlideshowToolbar(
                    playing = playing,
                    onPlay = { playing = !playing },
                    transition = transition,
                    onTransition = { transition = it },
                    seconds = seconds,
                    onSeconds = { seconds = it },
                )
            }
        }
    }
}

/** A slide: the photo fitted, moved by its Ken Burns path; a video's frame with the player's picture over it while live. */
@OptIn(UnstableApi::class)
@Composable
private fun SlideView(slide: Slide, item: MediaItem, picture: Picture?, thumbPx: Int, player: ExoPlayer?) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = slide.alpha.value }, contentAlignment = Alignment.Center) {
        val still = picture as? Picture.Still
        val animated = picture as? Picture.Animated
        // The photo's fitted frame, not clipped: zoomed, it grows into the screen's free space and keeps all it shows
        // (owner's test of S8a: clipped to the fitted frame, it lost its edges for nothing); only the screen's edge cuts
        // it, where it already reached it. An animation plays in it too (A1 and A2 stood still, without Ken Burns)
        val aspect = when {
            still != null && still.bitmap.height > 0 -> still.bitmap.width.toFloat() / still.bitmap.height
            animated != null && animated.full.height > 0 -> animated.full.width.toFloat() / animated.full.height
            else -> 0f
        }
        val moved = Modifier.fillMaxSize().graphicsLayer {
            val pose = slide.path.at(slide.clock.value)
            scaleX = pose.scale
            scaleY = pose.scale
            translationX = pose.shiftX(size.width)
            translationY = pose.shiftY(size.height)
        }
        when {
            item.video -> {
                MediaThumbnail(item, thumbPx, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                if (player != null) {
                    val presentation = rememberPresentationState(player)
                    val size = presentation.videoSizeDp
                    val fitted = if (size != null && size.isSpecified && size.width > 0f && size.height > 0f) Modifier.aspectRatio(size.width / size.height) else Modifier.fillMaxSize()
                    PlayerSurface(player, fitted, SURFACE_TYPE_SURFACE_VIEW)
                    if (presentation.coverSurface) MediaThumbnail(item, thumbPx, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            aspect > 0f -> Box(Modifier.aspectRatio(aspect)) {
                if (still != null) {
                    Image(bitmap = still.image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = moved)
                } else if (animated != null) {
                    // As the viewer: the platform's view plays it, looping
                    AndroidView(
                        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_XY } },
                        update = { view ->
                            view.setImageDrawable(animated.drawable)
                            animated.drawable.start()
                        },
                        modifier = moved,
                    )
                }
            }
            // Not decoded yet: its thumbnail
            else -> MediaThumbnail(item, thumbPx, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/**
 * The show's toolbar (SPEC §16): play/pause (Music's transport: a rounded square paused, round playing), the transition
 * as connected toggles, and the photo's time as a chip with its menu. Choices hold for this show.
 */
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SlideshowToolbar(playing: Boolean, onPlay: () -> Unit, transition: SlideTransition, onTransition: (SlideTransition) -> Unit, seconds: Int, onSeconds: (Int) -> Unit) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        expandedShadowElevation = 3.dp,
    ) {
        val label = stringResource(if (playing) R.string.content_description_pause else R.string.content_description_play)
        FilledIconToggleButton(
            checked = playing,
            onCheckedChange = { onPlay() },
            shapes = IconButtonDefaults.toggleableShapes(shape = RoundedCornerShape(16.dp), checkedShape = CircleShape),
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(if (playing) R.drawable.ic_pause_filled else R.drawable.ic_play_arrow_filled), contentDescription = label)
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            val labels = listOf(SlideTransition.CROSSFADE to R.string.transition_crossfade, SlideTransition.KEN_BURNS to R.string.transition_ken_burns)
            labels.forEachIndexed { index, (value, text) ->
                ToggleButton(
                    checked = transition == value,
                    onCheckedChange = { if (it) onTransition(value) },
                    shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                ) {
                    Text(stringResource(text), maxLines = 1)
                }
            }
        }
        Box {
            var open by remember { mutableStateOf(false) }
            val described = stringResource(R.string.content_description_photo_time, seconds)
            AssistChip(
                onClick = { open = true },
                label = { Text(stringResource(R.string.format_seconds, seconds)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_timer), contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics { stateDescription = described },
            )
            DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    Slideshow.SECONDS.forEachIndexed { index, value ->
                        SelectableDropdownMenuItem(
                            selected = value == seconds,
                            onClick = {
                                open = false
                                onSeconds(value)
                            },
                            text = { Text(stringResource(R.string.format_seconds, value), maxLines = 1) },
                            shapes = MenuDefaults.itemShape(index, Slideshow.SECONDS.size),
                            trailingContent = { if (value == seconds) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        )
                    }
                }
            }
        }
    }
}
