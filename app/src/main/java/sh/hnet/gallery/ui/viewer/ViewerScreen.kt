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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.MainActivity
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.ExifText
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Playback
import sh.hnet.gallery.model.SourceState
import sh.hnet.gallery.model.ViewerList
import sh.hnet.gallery.ui.albums.AddToAlbumSheet
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.Consent
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.LocalOpenAlbum
import sh.hnet.gallery.ui.components.MediaActions
import sh.hnet.gallery.ui.components.MenuItem
import sh.hnet.gallery.ui.components.RenameDialog
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.components.label
import sh.hnet.gallery.ui.components.placeData
import sh.hnet.gallery.ui.components.placeLanguage
import sh.hnet.gallery.ui.grid.ShareButton
import sh.hnet.gallery.ui.slideshow.LocalSlideshow
import sh.hnet.gallery.ui.slideshow.SlideshowRequest
import sh.hnet.gallery.ui.vault.LocalVaultActions
import sh.hnet.gallery.ui.vault.SecureWindow

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
 * @param onLoaded An item's picture is drawn (the flight in hands over to it), with the photo decoded for the screen
 * @param onRequestClose Asked first on closing: true when the host flies the item back to its tile, else the viewer fades
 * @param trash Opened from Trash (SPEC §6.6): its pages are the trashed items, its toolbar Restore, Info, Delete for good
 */
@Composable
fun ViewerScreen(
    opened: List<Long>,
    start: Long,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    trash: Boolean = false,
    album: Long? = null,
    revealed: Boolean = true,
    /** Opened by the picker's long press to check an item (SPEC §17.4): Info is the only action. */
    readOnly: Boolean = false,
    /** Where the opening came from (an album's name, Photos): the slideshow's position line; none, no slideshow from here. */
    title: String? = null,
    /** Opened from the vault (S8b2): its items through its provider, its own toolbar, the window kept secure. */
    vault: Boolean = false,
    extra: List<MediaItem> = emptyList(),
    locked: Boolean = false,
    unlock: (() -> Unit) -> Unit = { it() },
    onSaveToPhone: ((MediaItem) -> Unit)? = null,
    onPage: (Long) -> Unit,
    onLoaded: (Long, ImageBitmap?) -> Unit,
    onClose: () -> Unit,
    onRequestClose: (ViewerCloseFrom, Long) -> Boolean,
    onOpenFolder: (MediaItem) -> Unit = {},
    /** Info's small map (SPEC §7.3): the map page at the item's point; none where no map can open. */
    onOpenMap: ((Double, Double) -> Unit)? = null,
    /** The editor over the viewer (S7); none where it can't open (another app's viewer). */
    onEdit: ((MediaItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as GalleryApplication
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val consent = LocalConsent.current
    val writer = remember { MediaWriter(context) }
    val items by app.index.items.collectAsStateWithLifecycle()
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val autoplay by app.preferences.autoplayMotion.collectAsStateWithLifecycle(true)
    // Items another app opened (SPEC §17): read on their own, so they show before – or without – the index; the index's
    // own copy wins once it has one
    // The vault's items, read through its provider while it is open (S8b2)
    val vaultState by app.vault.state.collectAsStateWithLifecycle()
    val vaultEntries = if (vault) (vaultState as? Vault.State.Open)?.entries.orEmpty() else emptyList()
    val vaultItems = remember(vaultEntries) { vaultEntries.map(app.vault::item) }
    val byId = remember(items, extra, vaultItems) { (extra + vaultItems).associateBy { it.id } + items.orEmpty().associateBy { it.id } }
    if (vault) {
        // Nothing of the vault in Recents or screenshots; locked, the viewer goes (SPEC §18.4)
        SecureWindow()
        LaunchedEffect(vaultState) { if (vaultState !is Vault.State.Open) onClose() }
    }
    // Opened from a user album, an item removed from it leaves the pages as a trashed one does (SPEC-albums §9.8)
    val members by app.albums.members.collectAsStateWithLifecycle()
    val inAlbum = remember(members, album) { album?.let { id -> members.orEmpty().filter { it.albumId == id }.mapTo(HashSet()) { it.mediaId } } }
    val albumName = app.albums.albums.collectAsStateWithLifecycle().value?.firstOrNull { it.id == album }?.name
    val pages = remember(byId, inAlbum) { ViewerList.pages(opened, byId, trash).let { list -> if (inAlbum == null) list else list.filter { it in inAlbum } } }
    var adding by remember { mutableStateOf<MediaItem?>(null) }
    val openAlbum = LocalOpenAlbum.current
    val allAlbums by app.albums.albums.collectAsStateWithLifecycle()
    /** The user albums an item is in, by name in the language's order (Info's "In albums"). */
    fun albumsOf(id: Long): List<Pair<Long, String>> {
        val ids = members.orEmpty().filter { it.mediaId == id }.mapTo(HashSet()) { it.albumId }
        return allAlbums.orEmpty().filter { it.id in ids }.sortedBy { it.name.lowercase() }.map { it.id to it.name }
    }
    var currentId by rememberSaveable { mutableLongStateOf(start) }
    val slideshow = LocalSlideshow.current
    val vaultActions = LocalVaultActions.current
    // The pages as they are when a slideshow ends (items may have gone meanwhile)
    val latestPages by rememberUpdatedState(pages)
    val pager = rememberPagerState(initialPage = pages.indexOf(start).coerceAtLeast(0)) { pages.size }
    val dismiss = rememberViewerDismissState()
    var chrome by rememberSaveable { mutableStateOf(true) }
    // The chrome fades in once the flight has landed (owner: it jumped in) and goes while the viewer is swiped or flown away
    val chromeShown = chrome && !dismiss.active && revealed
    var info by rememberSaveable { mutableStateOf(false) }
    // Items this viewer put in Trash (or restored, or deleted, from Trash): their leaving the pages is no news
    val ownTrashed = remember { HashSet<Long>() }
    val actions = LocalMediaActions.current
    var renaming by remember { mutableStateOf<Pair<MediaItem, List<String>>?>(null) }
    var deleting by remember { mutableStateOf<MediaItem?>(null) }
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
    // The app leaving the screen pauses the video (review before v0.1.0: its sound went on in the background, a short
    // clip looped for good)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.item?.let { player.pauseFor(it) } }
    // A zoomed photo's drag past its edge moves the pages; released, they settle by how far and how fast (the owner, 19 Nov 2026)
    val flingPx = with(density) { 400.dp.toPx() }
    val handoff = remember { PagerHandoff() }
    fun settlePages(vx: Float) {
        scope.launch {
            val position = pager.currentPage + pager.currentPageOffsetFraction
            val target = when {
                vx < -flingPx -> floor(position).toInt() + 1
                vx > flingPx -> ceil(position).toInt() - 1
                else -> position.roundToInt()
            }.coerceIn(0, (pager.pageCount - 1).coerceAtLeast(0))
            try {
                pager.animateScrollToPage(target)
            } finally {
                // Only now is a page settled: the photo left behind goes back to fitted once off screen
                handoff.active = false
            }
        }
    }
    // Each use of the chrome or the card restarts its 4 s while a video plays (D9)
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
        // While the viewer itself moves the pages (a zoomed photo's drag past its edge, and its settling) the pager doesn't count
        // as scrolling, so its settled page would follow the middle of the move: the photo left behind went back to fitted
        // on screen, half-way (owner's phone test, 19 Nov 2026: "a third photo jumps in")
        snapshotFlow { if (handoff.active) null else pager.settledPage }.filterNotNull().drop(1).collect { page ->
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
        // The vault locking empties its pages: the viewer just closes, nothing was deleted (review before v0.1.0)
        val vaultLocked = vault && app.vault.state.value !is Vault.State.Open
        if (gone && currentId !in ownTrashed && !vaultLocked) {
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

    // The system bars with the chrome the user toggles (SPEC §6.2) – not with its going away while the viewer closes: the
    // bars disappeared for a moment on the way back to the grid (owner's phone test); they come back when the viewer goes
    val view = LocalView.current
    val window = activity?.window
    val barsShown = chrome || dismiss.active
    LaunchedEffect(barsShown, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsShown) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
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
    // The screen stays on while a video plays (CLAUDE.md, S2a: FLAG_KEEP_SCREEN_ON; review before v0.1.0: it went dark)
    val keepOn = video && playback.playing
    DisposableEffect(keepOn) {
        if (keepOn) view.keepScreenOn = true
        onDispose { if (keepOn) view.keepScreenOn = false }
    }
    val hdr = if (video) playback.item == currentId && playback.hdr != null else hdrOf[currentId] == true
    // A video plays when its page settles and pauses when it is left (SPEC §8.2) – once per arrival: the effect runs
    // again when the list of media passes through a change (the item missing for a moment, its row touched), and each run
    // played the video again; a video written over (its size changed – not its row's generation, which a favorite raises
    // too) plays anew
    val arrived = remember { arrayOfNulls<Pair<Long, Long>>(1) }
    LaunchedEffect(currentId, video, item?.size) {
        val shown = item ?: return@LaunchedEffect
        if (!video) {
            arrived[0] = null
            playback.item?.let { player.pauseFor(it) }
            return@LaunchedEffect
        }
        val key = shown.id to shown.size
        if (arrived[0] == key) return@LaunchedEffect
        arrived[0] = key
        player.show(shown)
    }
    // An HDR video's SDR toggle only where the phone's decoder tone-maps it (D11)
    var toneMaps by remember(currentId) { mutableStateOf(false) }
    LaunchedEffect(currentId, hdr) {
        val shown = item
        toneMaps = shown != null && video && hdr && ViewerPlayer.decoderToneMaps(context.contentResolver, shown)
    }
    // A long video stopped on its last frame: the chrome comes back and stays until it plays again (the owner, 19 Nov 2026);
    // a video that can't be played keeps it all the time (the problem card has no tap to bring it back)
    val broken = video && playback.item == currentId && playback.error
    LaunchedEffect(playback.ended, video, broken) {
        if (video && playback.ended || broken) chrome = true
    }
    // The chrome hides 4 s after the last touch while a video plays; paused, it stays (D9)
    LaunchedEffect(chrome, video, playback.playing, touched) {
        if (chrome && video && playback.playing && !broken) {
            delay(Playback.CHROME_HIDE_MS)
            chrome = false
        }
    }
    // HDR only while an HDR photo is settled, and not after its toggle (SPEC §6.1, §7.4)
    LaunchedEffect(currentId, hdr, sdr, window) {
        // At once: easing the headroom in and out looked worse on Android 17 than the switch (owner's test of S7)
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
            // Above the pager, so it doesn't move with the pages it moves
            .pagerHandoff(handoff, onDrag = { dx -> pager.dispatchRawDelta(-dx) }, onRelease = ::settlePages)
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
                        onOpenWith = if (vault) null else ({ open(context, pageItem, Intent.ACTION_VIEW) }),
                    )
                    LaunchedEffect(pageItem.id) { onLoaded(pageItem.id, null) }
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
                            onLoaded(pageItem.id, (picture as? Picture.Still)?.image)
                        },
                        onTap = { chrome = !chrome },
                        onHold = { held ->
                            if (isCurrent && facts?.get(pageItem.id)?.motion == true) {
                                holding = held
                                if (!held) motion = false
                            }
                        },
                        onOpenWith = if (vault) null else ({ open(context, pageItem, Intent.ACTION_VIEW) }),
                        zoomKey = isCurrent,
                        pager = handoff,
                    )
                }
            }
        }

        if (item != null) {
            AnimatedVisibility(chromeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopBar(
                    item, hdr = hdr && (!video || toneMaps), sdr = sdr, motionPhoto = fact?.motion == true,
                    onBack = { close() },
                    vault = vault,
                    folderName = (!item.outside && extra.isNotEmpty()).takeIf { it }?.let { item.folder },
                    onSdr = { value ->
                        sdr = value
                        if (video) player.setSdr(value)
                    },
                    onPlayMotion = { motion = true },
                )
            }
            val toolbar: @Composable () -> Unit = {
                if (readOnly) {
                    HorizontalFloatingToolbar(expanded = true) {
                        TooltipIconButton(R.drawable.ic_info, stringResource(R.string.action_info), { info = true })
                    }
                } else if (vault) {
                    // The vault's (SPEC §18.3): Info, Move out, Copy out, Export, Delete for good – no Share, no Edit
                    val entry = vaultEntries.firstOrNull { Vault.idOf(it.file) == item.id }
                    VaultToolbar(
                        onInfo = { info = true },
                        onMoveOut = { entry?.let { vaultActions?.takeOut(listOf(it), move = true) { ownTrashed.add(item.id) } } },
                        onCopyOut = { entry?.let { vaultActions?.takeOut(listOf(it), move = false) } },
                        onExport = { entry?.let { vaultActions?.askExport(listOf(it)) } },
                        onDeleteForever = { deleting = item },
                    )
                } else if (item.outside) {
                    // A file from outside (SPEC §17.2, SPEC-albums §17.2): Share, Save to phone, Info, Open with – nothing that changes it
                    OutsideToolbar(
                        onShare = { actions.share(listOf(item)) },
                        onSave = { onSaveToPhone?.invoke(item) },
                        onInfo = { info = true },
                        onOpenWith = { open(context, item, Intent.ACTION_VIEW) },
                    )
                } else if (trash) {
                    TrashToolbar(
                        onRestore = { actions.restore(listOf(item)) { ownTrashed.add(item.id) } },
                        onInfo = { info = true },
                        onDeleteForever = { deleting = item },
                    )
                } else {
                    Toolbar(
                        item,
                        // On a locked phone Share and More ask to unlock first (SPEC §17.3); Move to Trash works at once
                        onShare = { unlock { actions.share(listOf(item)) } },
                        locked = locked,
                        unlock = unlock,
                        onFavorite = { actions.favorite(listOf(item), !item.favorite) },
                        onInfo = { info = true },
                        onDelete = { actions.trash(listOf(item)) { ownTrashed.add(item.id) } },
                        onRename = { scope.launch { renaming = item to writer.namesBeside(item, items.orEmpty()) } },
                        onAddToAlbum = { adding = item },
                        removeFrom = albumName,
                        onRemoveFromAlbum = {
                            val id = album ?: return@Toolbar
                            ownTrashed.add(item.id)
                            val message = resources.getString(R.string.msg_removed_from_album, albumName.orEmpty(), 1)
                            scope.launch {
                                val removed = app.albums.remove(id, listOf(item.id))
                                actions.say(message) { app.appScope.launch { app.albums.putBack(removed) } }
                            }
                        },
                        onSetAs = { open(context, item, Intent.ACTION_ATTACH_DATA) },
                        onEditElsewhere = { open(context, item, Intent.ACTION_EDIT) },
                        // The video pauses under the editor, which plays it with its own player
                        onEdit = onEdit?.let { edit ->
                            {
                                playback.item?.let { player.pauseFor(it) }
                                unlock { edit(item) }
                            }
                        },
                        onOpenWith = { open(context, item, Intent.ACTION_VIEW) },
                        // Slideshow from here (SPEC §16): this opening's pages from this one; on its end the viewer goes on
                        // at the item the show stopped at
                        onSlideshow = if (title != null && slideshow != null && !trash) {
                            {
                                playback.item?.let { player.pauseFor(it) }
                                slideshow(SlideshowRequest(pages, item.id, title) { last ->
                                    val at = latestPages.indexOf(last)
                                    if (at >= 0) scope.launch { pager.scrollToPage(at) }
                                })
                            }
                        } else null,
                        // Move to vault (SPEC §18.5): the item leaves the pages as one this viewer removed itself
                        onMoveToVault = vaultActions?.takeIf { !readOnly && !trash && !item.outside }?.let { v -> { v.moveIn(listOf(item)) { ownTrashed.add(item.id) } } },
                    )
                }
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
            adding?.let { target -> AddToAlbumSheet(listOf(target), onDismiss = { adding = null }, onAdded = { adding = null }) }
            renaming?.let { (target, taken) ->
                RenameDialog(target.name, taken, onDismiss = { renaming = null }) { name ->
                    renaming = null
                    rename(context, target, name, writer, consent, actions, messageScope)
                }
            }
            deleting?.let { target ->
                ConfirmDestructiveDialog(
                    R.drawable.ic_delete_forever,
                    stringResource(R.string.title_delete_forever_one),
                    AnnotatedString(stringResource(R.string.desc_delete_forever)),
                    stringResource(R.string.button_delete_forever),
                    onConfirm = {
                        deleting = null
                        if (vault) {
                            // The app's own files: no system dialog (SPEC §18.3)
                            ownTrashed.add(target.id)
                            vaultEntries.firstOrNull { Vault.idOf(it.file) == target.id }?.let { entry -> app.appScope.launch { app.vault.remove(listOf(entry)) } }
                        } else {
                            actions.deleteForever(listOf(target)) { ownTrashed.add(target.id) }
                        }
                    },
                    onDismiss = { deleting = null },
                )
            }
            if (info) {
                // The folder row opens the source's album over the viewer (SPEC-albums §3.3, §7.1); a hidden folder's opens nothing
                val sources by app.sources.rows.collectAsStateWithLifecycle()
                val newFolders by app.preferences.newFoldersInPhotos.collectAsStateWithLifecycle(false)
                val folderShown = sources?.let { SourceStore.stateOf(item.folder, it, newFolders) != SourceState.HIDDEN } == true
                InfoSheet(
                    item, fact, hdr, landscape, playback.takeIf { it.item == item.id },
                    // Not for a file from outside: nothing changes it (SPEC §17.2), and MediaStore can't ask for another app's file
                    onChangeDate = if (readOnly || vault || item.outside) null else ({ date, time -> changeDate(context, item, date, time, writer, consent, snackbars, messageScope) }),
                    onDismiss = { info = false },
                    // The sheet is a window of its own, above anything the viewer's host lays over it: it closes as the album opens (owner's test of S4b1)
                    onOpenFolder = if (folderShown && !trash && !readOnly) ({
                        info = false
                        onOpenFolder(item)
                    }) else null,
                    albums = if (trash || readOnly) emptyList() else albumsOf(item.id),
                    // The sheet is a window above the map page too: it closes as the map opens
                    onOpenMap = if (readOnly || onOpenMap == null) null else ({ lat, lon ->
                        info = false
                        onOpenMap(lat, lon)
                    }),
                    onOpenAlbum = { id ->
                        info = false
                        openAlbum(id)
                    },
                )
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
private fun TopBar(item: MediaItem, hdr: Boolean, sdr: Boolean, motionPhoto: Boolean, onBack: () -> Unit, onSdr: (Boolean) -> Unit, onPlayMotion: () -> Unit, folderName: FolderKey? = null, vault: Boolean = false) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    TopAppBar(
        title = {
            Column {
                // A file from outside: its name and "Outside Gallery" (SPEC §17.2); one another app opened in its folder: the
                // date and the folder's album (SPEC §17.1, §17.3)
                // A vault item: its date as any, "Vault" under it
                Text(if (item.outside && !vault) item.name else viewerTitle(item, facts?.get(item.id)), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The place under the date (SPEC §6.2: town and country), when the item has one
                val place = placeData()?.label(facts?.get(item.id), placeLanguage(), full = false)
                val subtitle = when {
                    vault -> stringResource(R.string.label_vault)
                    item.outside -> stringResource(R.string.label_outside_gallery)
                    folderName != null -> albumName(folderName)
                    else -> place
                }
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (item.outside && !vault) TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_close), onBack)
            else TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack)
        },
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
 * The floating toolbar (SPEC §6.2): Share, Edit, Favorite, Info, More, Delete. Edit is left out for formats the
 * editor can't write and for videos (their trimming comes with S9).
 */
@Composable
private fun Toolbar(
    item: MediaItem,
    onShare: () -> Unit,
    locked: Boolean,
    unlock: (() -> Unit) -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onAddToAlbum: () -> Unit,
    removeFrom: String?,
    onRemoveFromAlbum: () -> Unit,
    onSetAs: () -> Unit,
    onEditElsewhere: () -> Unit,
    onOpenWith: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onSlideshow: (() -> Unit)? = null,
    onMoveToVault: (() -> Unit)? = null,
) {
    var more by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(expanded = true) {
        ShareButton(onShare)
        // Edit: photos (S7) and videos – trimming (S9b)
        if (item.mime !in NOT_EDITABLE) TooltipIconButton(R.drawable.ic_edit, stringResource(R.string.action_edit), { onEdit?.invoke() }, enabled = onEdit != null)
        TooltipIconButton(
            if (item.favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
            stringResource(if (item.favorite) R.string.action_unfavorite else R.string.action_favorite),
            onFavorite,
            colors = if (item.favorite) IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary) else IconButtonDefaults.iconButtonColors(),
        )
        TooltipIconButton(R.drawable.ic_info, stringResource(R.string.action_info), onInfo)
        Box {
            TooltipIconButton(R.drawable.ic_more_horiz, stringResource(R.string.action_more), { if (locked) unlock { more = true } else more = true })
            MoreMenu(more, onDismiss = { more = false }, removeFrom = removeFrom, onRemoveFromAlbum = onRemoveFromAlbum, onAddToAlbum = onAddToAlbum, onRename = onRename, onSetAs = onSetAs, onEditElsewhere = onEditElsewhere, onOpenWith = onOpenWith, onSlideshow = onSlideshow, onMoveToVault = onMoveToVault)
        }
        TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.action_delete), onDelete, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error))
    }
}

/** More (SPEC §6.3), in three groups; what later stages bring is shown disabled (D5). */
@Composable
private fun MoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    removeFrom: String?,
    onRemoveFromAlbum: () -> Unit,
    onAddToAlbum: () -> Unit,
    onRename: () -> Unit,
    onSetAs: () -> Unit,
    onEditElsewhere: () -> Unit,
    onOpenWith: () -> Unit,
    onSlideshow: (() -> Unit)?,
    onMoveToVault: (() -> Unit)?,
) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 3)) {
            val count = if (removeFrom != null) 3 else 2
            // From a user album, Remove from album first with the album's name under it (SPEC-albums §6.3)
            if (removeFrom != null) {
                SelectableDropdownMenuItem(
                    selected = false,
                    onClick = { onDismiss(); onRemoveFromAlbum() },
                    text = { Text(stringResource(R.string.action_remove_from_album), maxLines = 1) },
                    supportingText = { Text(removeFrom, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shapes = MenuDefaults.itemShape(0, count),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_library_remove), contentDescription = null, modifier = Modifier.size(20.dp)) },
                )
            }
            MenuItem(stringResource(R.string.action_add_to_album), R.drawable.ic_library_add, count - 2, count) { onDismiss(); onAddToAlbum() }
            MenuItem(stringResource(R.string.menu_rename), R.drawable.ic_drive_file_rename_outline, count - 1, count) { onDismiss(); onRename() }
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 3)) {
            MenuItem(stringResource(R.string.menu_set_as), R.drawable.ic_wallpaper, 0, 3) { onDismiss(); onSetAs() }
            MenuItem(stringResource(R.string.menu_slideshow_from_here), R.drawable.ic_slideshow, 1, 3, enabled = onSlideshow != null) { onDismiss(); onSlideshow?.invoke() }
            MenuItem(stringResource(R.string.action_move_to_vault), R.drawable.ic_lock, 2, 3, enabled = onMoveToVault != null) { onDismiss(); onMoveToVault?.invoke() }
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(2, 3)) {
            MenuItem(stringResource(R.string.menu_edit_in_another_app), R.drawable.ic_edit_square, 0, 2) { onDismiss(); onEditElsewhere() }
            MenuItem(stringResource(R.string.menu_open_with), R.drawable.ic_open_in_new, 1, 2) { onDismiss(); onOpenWith() }
        }
    }
}

/** A file from outside Gallery (SPEC §17.2, SPEC-albums §17.2): Share, Save to phone, Info, Open with. */
@Composable
private fun OutsideToolbar(onShare: () -> Unit, onSave: () -> Unit, onInfo: () -> Unit, onOpenWith: () -> Unit) {
    HorizontalFloatingToolbar(expanded = true) {
        ShareButton(onShare)
        TooltipIconButton(R.drawable.ic_download, stringResource(R.string.action_save_to_phone), onSave)
        TooltipIconButton(R.drawable.ic_info, stringResource(R.string.action_info), onInfo)
        TooltipIconButton(R.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), onOpenWith)
    }
}

/** The vault's viewer (SPEC §18.3): Info, Move out, Copy out, Export, Delete for good last in the error colour. */
@Composable
private fun VaultToolbar(onInfo: () -> Unit, onMoveOut: () -> Unit, onCopyOut: () -> Unit, onExport: () -> Unit, onDeleteForever: () -> Unit) {
    HorizontalFloatingToolbar(expanded = true) {
        TooltipIconButton(R.drawable.ic_info, stringResource(R.string.action_info), onInfo)
        TooltipIconButton(R.drawable.ic_drive_file_move, stringResource(R.string.action_move_out), onMoveOut)
        TooltipIconButton(R.drawable.ic_file_copy, stringResource(R.string.action_copy_out), onCopyOut)
        TooltipIconButton(R.drawable.ic_output, stringResource(R.string.action_export), onExport)
        TooltipIconButton(R.drawable.ic_delete_forever, stringResource(R.string.action_delete_forever), onDeleteForever, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error))
    }
}

/** The viewer opened from Trash (SPEC §6.6): Restore, Info, Delete for good – last, in the error colour, asking first. */
@Composable
private fun TrashToolbar(onRestore: () -> Unit, onInfo: () -> Unit, onDeleteForever: () -> Unit) {
    HorizontalFloatingToolbar(expanded = true) {
        TooltipIconButton(R.drawable.ic_restore_from_trash, stringResource(R.string.action_restore), onRestore)
        TooltipIconButton(R.drawable.ic_info, stringResource(R.string.action_info), onInfo)
        TooltipIconButton(R.drawable.ic_delete_forever, stringResource(R.string.action_delete_forever), onDeleteForever, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error))
    }
}

/**
 * A file's new name (SPEC §10.3): the app's own file at once, another app's after the system's consent;
 * the snackbar says it (a failure – e.g. a file of that name MediaStore didn't show – says that instead).
 */
private fun rename(context: Context, item: MediaItem, name: String, writer: MediaWriter, consent: Consent, actions: MediaActions, messageScope: CoroutineScope) {
    val write = {
        messageScope.launch {
            // Asked again just before writing: the folder may have changed while the dialog was open, and a taken name would replace that file
            val known = (context.applicationContext as GalleryApplication).index.items.value.orEmpty()
            if (writer.namesBeside(item, known).any { it.equals(name, ignoreCase = true) }) {
                actions.say(context.getString(R.string.error_file_exists), undo = null)
                return@launch
            }
            val done = writer.rename(item, name)
            actions.say(if (done) context.getString(R.string.msg_renamed) else context.getString(R.string.error_save_failed, item.name), undo = null)
        }
        Unit
    }
    val request = writer.writeRequest(listOf(item))
    if (request == null) write() else consent.ask(request, write)
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
