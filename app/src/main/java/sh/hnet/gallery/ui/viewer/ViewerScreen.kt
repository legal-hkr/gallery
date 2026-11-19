@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.MainActivity
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.ExifText
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Playback
import sh.hnet.gallery.model.ViewerList
import sh.hnet.gallery.ui.components.Consent
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.MenuItem
import sh.hnet.gallery.ui.components.TooltipIconButton

/** Formats the editor can't write (SPEC §6.2): Edit doesn't show for them at all. */
private val NOT_EDITABLE = setOf("image/gif", "image/x-adobe-dng")

/**
 * The full-screen viewer (SPEC §6, §7; LOGIKA §13): a pager over the items of the list it was
 * opened from, kept by id, with the zoom, the chrome toggled by a tap together with the system
 * bars, the HDR window mode while an Ultra HDR photo is settled, motion photos, Info, and the
 * actions built so far – the others are shown disabled until their stage (owner's decision D5).
 *
 * @param opened The ids of the list it was opened from, in its order
 * @param onPage The item settled on (the list under it scrolls there, so the flight back lands on its tile)
 * @param onLoaded An item's picture is drawn (the flight in hands over to it)
 * @param onRequestClose Asked first on closing: true when the host flies the item back to its tile, else the viewer fades
 */
@Composable
fun ViewerScreen(
    opened: List<Long>,
    start: Long,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    onPage: (Long) -> Unit,
    onLoaded: (Long) -> Unit,
    onClose: () -> Unit,
    onRequestClose: (ViewerCloseFrom, Long) -> Boolean,
) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val consent = LocalConsent.current
    val writer = remember { MediaWriter(context) }
    val items by app.index.items.collectAsStateWithLifecycle()
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val autoplay by app.preferences.autoplayMotion.collectAsStateWithLifecycle(true)
    val byId = remember(items) { items.orEmpty().associateBy { it.id } }
    val pages = remember(byId) { ViewerList.pages(opened, byId) }
    var currentId by rememberSaveable { mutableLongStateOf(start) }
    val pager = rememberPagerState(initialPage = pages.indexOf(start).coerceAtLeast(0)) { pages.size }
    val dismiss = rememberViewerDismissState()
    var chrome by rememberSaveable { mutableStateOf(true) }
    val chromeShown = chrome && !dismiss.active
    var info by rememberSaveable { mutableStateOf(false) }
    // Items this viewer put in Trash: their leaving the pages is no news
    val ownTrashed = remember { HashSet<Long>() }
    val hdrOf = remember { mutableStateMapOf<Long, Boolean>() }
    var sdr by remember(currentId) { mutableStateOf(false) }
    var motion by remember(currentId) { mutableStateOf(false) }
    var holding by remember(currentId) { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val deletedElsewhere = stringResource(R.string.msg_deleted_elsewhere)
    // One video player for this opening (LOGIKA §14.1)
    val player = remember { ViewerPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    val playback by player.state.collectAsStateWithLifecycle()
    // Each use of the chrome or the card restarts its 3 s while a video plays (D9)
    var touched by remember { mutableIntStateOf(0) }

    fun close() {
        if (dismiss.closing) return
        if (onRequestClose(dismiss.closeFrom(with(density) { 28.dp.toPx() }), currentId)) {
            dismiss.markClosing()
        } else {
            scope.launch {
                dismiss.fadeOut()
                onClose()
            }
        }
    }
    BackHandler { close() }

    // The page settled on is the current item. Restarted with every new list, it skips what it sees at once: after a
    // change of the list that is the old index over the new list, and the check below must still find the item gone
    // (owner's test of S2a, 19 Nov 2026: a file deleted by another app moved the pager on without a word)
    LaunchedEffect(pager, pages) {
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            pages.getOrNull(page)?.let { id ->
                currentId = id
                onPage(id)
            }
        }
    }
    // The list changed under the viewer (SPEC §6.9): the current item stays, or the next one takes its place
    LaunchedEffect(pages) {
        val follow = ViewerList.follow(currentId, opened, pages)
        val gone = currentId !in pages
        if (gone && currentId !in ownTrashed) {
            messageScope.launch {
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(deletedElsewhere, duration = SnackbarDuration.Short)
            }
        }
        if (follow == null) {
            onClose()
            return@LaunchedEffect
        }
        currentId = follow
        val index = pages.indexOf(follow)
        if (index >= 0 && pager.currentPage != index) pager.scrollToPage(index)
    }

    // The chrome and the system bars together (SPEC §6.2); the bars come back when the viewer goes
    val view = LocalView.current
    val window = activity?.window
    LaunchedEffect(chromeShown, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeShown) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window) {
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }

    val item = byId[currentId]
    val fact = facts?.get(currentId)
    val video = item?.video == true
    val hdr = if (video) playback.item == currentId && playback.hdr != null else hdrOf[currentId] == true
    // A video plays when its page settles and pauses when it is left (SPEC §8.2)
    LaunchedEffect(currentId, video) {
        val shown = item ?: return@LaunchedEffect
        if (video) player.show(shown) else playback.item?.let { player.pauseFor(it) }
    }
    // An HDR video's SDR toggle only where the phone's decoder tone-maps it (D11)
    var toneMaps by remember(currentId) { mutableStateOf(false) }
    LaunchedEffect(currentId, hdr) {
        val shown = item
        toneMaps = shown != null && video && hdr && ViewerPlayer.decoderToneMaps(context.contentResolver, shown)
    }
    // A long video stopped on its last frame: the chrome comes back and stays until it plays again (the owner, 19 Nov 2026)
    LaunchedEffect(playback.ended, video) {
        if (video && playback.ended) chrome = true
    }
    // The chrome hides 4 s after the last touch while a video plays; paused, it stays (D9)
    LaunchedEffect(chrome, video, playback.playing, touched) {
        if (chrome && video && playback.playing) {
            delay(Playback.CHROME_HIDE_MS)
            chrome = false
        }
    }
    // HDR only while an HDR photo is settled, and not after its toggle (SPEC §6.1, §7.4)
    LaunchedEffect(currentId, hdr, sdr, window) {
        window?.colorMode = if (hdr && !sdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
    }
    // A motion photo plays once, muted, when its page settles (SPEC §7.5)
    LaunchedEffect(currentId, autoplay, fact?.motion) {
        if (autoplay && fact?.motion == true) {
            delay(MOTION_SETTLE_MS)
            motion = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = dismiss.backdropAlpha))
            .onSizeChanged { dismiss.containerHeight = it.height }
            .viewerSwipeToDismiss(
                state = dismiss,
                // A video pauses while it is swiped (its frame stands in: D12) and goes on if the swipe settles back
                onStart = { (video && playback.playing).also { if (it) player.togglePlay() } },
                onClose = { close() },
                onSettle = { wasPlaying -> if (wasPlaying) player.togglePlay() },
                onSwipeUp = { if (item != null) info = true },
            ),
    ) {
        // The media, moved, scaled and rounded by the swipe (the gesture above reads untransformed positions)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = dismiss.offset
                val scale = 1f - 0.15f * dismiss.progress
                scaleX = scale
                scaleY = scale
                alpha = dismiss.contentAlpha
                shape = RoundedCornerShape(28.dp * dismiss.progress)
                clip = dismiss.progress > 0f
            },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages[it] }, beyondViewportPageCount = 1) { page ->
                val pageItem = byId[pages[page]] ?: return@HorizontalPager
                val isCurrent = pageItem.id == currentId
                if (pageItem.video) {
                    VideoPage(
                        pageItem, player, live = isCurrent && !dismiss.active,
                        onTap = {
                            touched++
                            chrome = !chrome
                        },
                        onOpenWith = { open(context, pageItem, Intent.ACTION_VIEW) },
                    )
                    LaunchedEffect(pageItem.id) { onLoaded(pageItem.id) }
                } else {
                    PhotoPage(
                        pageItem,
                        facts?.get(pageItem.id),
                        motion = isCurrent && (motion || holding),
                        holding = isCurrent && holding,
                        // A held motion photo plays with sound when it was turned on in this opening (SPEC §7.5, §8.3)
                        sound = isCurrent && holding && !playback.muted,
                        onMotionEnded = { if (isCurrent && !holding) motion = false },
                        onPicture = { picture ->
                            hdrOf[pageItem.id] = (picture as? Picture.Still)?.gainmap == true
                            onLoaded(pageItem.id)
                        },
                        onTap = { chrome = !chrome },
                        onHold = { held ->
                            if (isCurrent && facts?.get(pageItem.id)?.motion == true) {
                                holding = held
                                if (!held) motion = false
                            }
                        },
                        onOpenWith = { open(context, pageItem, Intent.ACTION_VIEW) },
                        zoomKey = isCurrent,
                    )
                }
            }
        }

        if (item != null) {
            AnimatedVisibility(chromeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopBar(
                    item, hdr = hdr && (!video || toneMaps), sdr = sdr, motionPhoto = fact?.motion == true,
                    onBack = { close() },
                    onSdr = { value ->
                        sdr = value
                        if (video) player.setSdr(value)
                    },
                    onPlayMotion = { motion = true },
                )
            }
            val toolbar: @Composable () -> Unit = {
                Toolbar(
                    item,
                    onFavorite = { scope.launch { change(writer.favorite(listOf(item), !item.favorite), consent) {} } },
                    onInfo = { info = true },
                    onDelete = { trash(item, writer, consent, snackbars, messageScope, ownTrashed, context) },
                    onSetAs = { open(context, item, Intent.ACTION_ATTACH_DATA) },
                    onEditElsewhere = { open(context, item, Intent.ACTION_EDIT) },
                    onOpenWith = { open(context, item, Intent.ACTION_VIEW) },
                )
            }
            val bottomInsets = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            if (video && landscape) {
                // Landscape (SPEC §8.5): the one-row card at the bottom start, beside the toolbar at the bottom end
                AnimatedVisibility(chromeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).then(bottomInsets).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlaybackCard(player, compact = true, onInteraction = { touched++ }, modifier = Modifier.weight(1f))
                        toolbar()
                    }
                }
            } else {
                if (video) {
                    // Portrait (SPEC §8.1): the card above the toolbar, 12 dp from the sides
                    AnimatedVisibility(chromeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).then(bottomInsets).padding(start = 12.dp, end = 12.dp, bottom = 92.dp)) {
                        PlaybackCard(player, compact = false, onInteraction = { touched++ }, modifier = Modifier.fillMaxWidth())
                    }
                }
                AnimatedVisibility(chromeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).then(bottomInsets).offset(y = -FloatingToolbarDefaults.ScreenOffset)) {
                    toolbar()
                }
            }
            if (info) {
                InfoSheet(item, fact, hdr, landscape, playback.takeIf { it.item == item.id }, onChangeDate = { date, time -> changeDate(context, item, date, time, writer, consent, snackbars, messageScope) }, onDismiss = { info = false })
            }
        }

        // The viewer's messages above its toolbar (SPEC §6.4: 92 dp above the bottom)
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(bottom = 92.dp)) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

/** Time a page must rest before its motion plays (SPEC §7.5: the pager idle ~300 ms). */
private const val MOTION_SETTLE_MS = 300L

/**
 * The top bar (SPEC §6.2): a `surface-container` band from the top edge, Back, the date and time
 * (the place under it comes with S6), and the state toggles – HDR on HDR photos, play on motion photos.
 */
@Composable
private fun TopBar(item: MediaItem, hdr: Boolean, sdr: Boolean, motionPhoto: Boolean, onBack: () -> Unit, onSdr: (Boolean) -> Unit, onPlayMotion: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    TopAppBar(
        title = {
            Column {
                Text(viewerTitle(item, facts?.get(item.id)), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        actions = {
            if (hdr) {
                val label = stringResource(if (!sdr) R.string.content_description_show_sdr else R.string.content_description_show_hdr)
                Tooltip(label) {
                    FilledTonalIconToggleButton(checked = !sdr, onCheckedChange = { onSdr(!it) }) {
                        Icon(painterResource(R.drawable.ic_hdr_on), contentDescription = label)
                    }
                }
            }
            if (motionPhoto) {
                val label = stringResource(R.string.content_description_play_motion)
                Tooltip(label) {
                    FilledTonalIconButton(onClick = onPlayMotion) {
                        Icon(painterResource(R.drawable.ic_motion_photos_on), contentDescription = label)
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    )
}

/** A plain tooltip on long press, as the family's [TooltipIconButton]. */
@Composable
private fun Tooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) { content() }
}

/**
 * The floating toolbar (SPEC §6.2): Share, Edit, Favorite, Info, More, Delete. Share and Edit come
 * with S5 and S7: shown disabled meanwhile (D5); Edit is left out for formats the editor can't write.
 */
@Composable
private fun Toolbar(
    item: MediaItem,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onSetAs: () -> Unit,
    onEditElsewhere: () -> Unit,
    onOpenWith: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(expanded = true) {
        TooltipIconButton(R.drawable.ic_share, stringResource(R.string.action_share), {}, enabled = false)
        if (item.mime !in NOT_EDITABLE) TooltipIconButton(R.drawable.ic_edit, stringResource(R.string.action_edit), {}, enabled = false)
        TooltipIconButton(
            if (item.favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
            stringResource(if (item.favorite) R.string.action_unfavorite else R.string.action_favorite),
            onFavorite,
            colors = if (item.favorite) IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary) else IconButtonDefaults.iconButtonColors(),
        )
        TooltipIconButton(R.drawable.ic_info, stringResource(R.string.action_info), onInfo)
        Box {
            TooltipIconButton(R.drawable.ic_more_horiz, stringResource(R.string.action_more), { more = true })
            MoreMenu(more, onDismiss = { more = false }, onSetAs = onSetAs, onEditElsewhere = onEditElsewhere, onOpenWith = onOpenWith)
        }
        TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.action_delete), onDelete, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error))
    }
}

/** More (SPEC §6.3), in three groups; what later stages bring is shown disabled (D5). */
@Composable
private fun MoreMenu(expanded: Boolean, onDismiss: () -> Unit, onSetAs: () -> Unit, onEditElsewhere: () -> Unit, onOpenWith: () -> Unit) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 3)) {
            MenuItem(stringResource(R.string.action_add_to_album), R.drawable.ic_library_add, 0, 2, enabled = false) {}
            MenuItem(stringResource(R.string.menu_rename), R.drawable.ic_drive_file_rename_outline, 1, 2, enabled = false) {}
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 3)) {
            MenuItem(stringResource(R.string.menu_set_as), R.drawable.ic_wallpaper, 0, 3) { onDismiss(); onSetAs() }
            MenuItem(stringResource(R.string.menu_slideshow_from_here), R.drawable.ic_slideshow, 1, 3, enabled = false) {}
            MenuItem(stringResource(R.string.action_move_to_vault), R.drawable.ic_lock, 2, 3, enabled = false) {}
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(2, 3)) {
            MenuItem(stringResource(R.string.menu_edit_in_another_app), R.drawable.ic_edit_square, 0, 2) { onDismiss(); onEditElsewhere() }
            MenuItem(stringResource(R.string.menu_open_with), R.drawable.ic_open_in_new, 1, 2) { onDismiss(); onOpenWith() }
        }
    }
}

/** Runs [then] once the change is done: at once, or after the system's dialog was accepted; a refusal or a failure changes nothing. */
private fun change(change: MediaWriter.Change, consent: Consent, then: () -> Unit) {
    when (change) {
        MediaWriter.Change.Done -> then()
        is MediaWriter.Change.Ask -> consent.ask(change.request, then)
        is MediaWriter.Change.Failed -> {}
    }
}

/**
 * Delete in the viewer (SPEC §6.4, §5.5): to Trash at once, the pager moves on by itself, and the
 * family's snackbar offers Undo, which puts it back (asking the system again for another app's file).
 */
private fun trash(item: MediaItem, writer: MediaWriter, consent: Consent, snackbars: SnackbarHostState, messageScope: CoroutineScope, ownTrashed: MutableSet<Long>, context: Context) {
    val moved = context.getString(R.string.msg_moved_to_trash_one)
    val undo = context.getString(R.string.button_undo)
    messageScope.launch {
        change(writer.trash(listOf(item), true), consent) {
            ownTrashed.add(item.id)
            messageScope.launch {
                snackbars.currentSnackbarData?.dismiss()
                if (snackbars.showSnackbar(moved, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    change(writer.trash(listOf(item), false), consent) {}
                }
            }
        }
    }
}

/**
 * The date taken changed in Info (SPEC §7.2): `DateTimeOriginal` and `OffsetTimeOriginal` (the
 * phone's zone at that moment) written into the file, after the system's consent for another app's
 * file; the system scans the file again, so the item moves to its new day by itself.
 */
private fun changeDate(context: Context, item: MediaItem, date: LocalDate, time: LocalTime, writer: MediaWriter, consent: Consent, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val failed = context.getString(R.string.error_save_failed, item.name)
    val offset = ZoneId.systemDefault().rules.getOffset(LocalDateTime.of(date, time)).totalSeconds / 60
    val (stamp, zone) = ExifText.exifDate(date.year, date.monthValue, date.dayOfMonth, time.hour, time.minute, offset)
    val write: () -> Unit = {
        messageScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openFileDescriptor(item.uri(), "rw")?.use { descriptor ->
                        val exif = ExifInterface(descriptor.fileDescriptor)
                        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, zone)
                        exif.saveAttributes()
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            }
            if (!ok) {
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(failed, duration = SnackbarDuration.Short)
            }
        }
    }
    val request = writer.writeRequest(listOf(item))
    if (request == null) write() else consent.ask(request, write)
}

/**
 * Hands the item to another app (SPEC §6.3): Set as (wallpaper, contact photo), Edit in another app,
 * Open with – the system's chooser, read access granted, the app itself left out of the choice.
 */
private fun open(context: Context, item: MediaItem, action: String) {
    val uri = item.uri()
    val intent = Intent(action).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (action == Intent.ACTION_ATTACH_DATA) intent.putExtra("mimeType", item.mime)
    // An editor may save over the file only where the app may itself
    if (action == Intent.ACTION_EDIT && item.ours) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    runCatching { context.startActivity(chooser) }
}
