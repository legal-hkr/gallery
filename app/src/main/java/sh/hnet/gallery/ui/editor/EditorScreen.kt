@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.editor

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withClip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.EditSaver
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.CropRatio
import sh.hnet.gallery.model.EditGeometry
import sh.hnet.gallery.model.EditHistory
import sh.hnet.gallery.model.EditState
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Orientation
import sh.hnet.gallery.model.PxSize
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.TooltipIconButton

/**
 * The editor (SPEC §15.1, §15.2, §15.6, §15.8, §15.9; LOGIKA §18): an overlay over the viewer (D24), upright while
 * open; the photo under the crop's frame and Crop's tools – no tabs while Crop is the only tool (the owner: nothing
 * that does nothing in a release). An edit is a list of changes on a preview; the full photo is made only on saving.
 *
 * @param onCopySaved A copy saved: the viewer goes on to it
 */
// Upright while editing: the family's portrait, the viewer's turning set back after (as ViewerHost)
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun EditorScreen(item: MediaItem, onClose: () -> Unit, onCopySaved: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val actions = LocalMediaActions.current
    val consent = LocalConsent.current
    val scope = rememberCoroutineScope()
    val saver = remember { EditSaver(context.applicationContext) }
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val motion = facts?.get(item.id)?.motion == true
    val located = remember { app.media.canLocate() }

    // Upright while editing (SPEC: portrait); the viewer under it turns with the phone again after
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
    }

    var history by remember { mutableStateOf(EditHistory()) }
    // A drag or the slider under way: shown, made a step of the history when it ends
    var live by remember { mutableStateOf<EditState?>(null) }
    val state = live ?: history.present
    var leaving by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val compare = remember { MutableInteractionSource() }
    val comparing by compare.collectIsPressedAsState()

    val preview by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri())) { decoder, info, _ ->
                    val longer = maxOf(info.size.width, info.size.height)
                    if (longer > PREVIEW) {
                        val s = PREVIEW.toFloat() / longer
                        decoder.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
                    }
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    val upright = preview?.let { PxSize(it.width, it.height) }

    // Changes of the crop that aren't a drag – a ratio, its orientation, a reset, undo and redo – are shown moving there
    // (owner's test of S7); a turn or a mirror has its own motion, so a step that turns doesn't count
    var cropMoves by remember { mutableIntStateOf(0) }
    fun step(next: EditHistory) {
        val before = history.present
        history = next
        if (before.quarters == next.present.quarters && before.flipped == next.present.flipped && before.crop != next.present.crop) cropMoves++
    }

    fun commit(next: EditState) {
        live = null
        history = history.push(next)
    }

    /** A choice that moves the crop at once (a ratio, its orientation, a reset): shown moving. */
    fun choose(next: EditState) {
        live = null
        step(history.push(next))
    }

    // Not while saving: leaving cancelled the save's end, so a copy written or an original replaced was reported as a
    // failure (review before v0.1.0); the photo and its tools hold still meanwhile too
    fun leave() = when {
        saving -> {}
        history.present.changed -> leaving = true
        else -> onClose()
    }
    BackHandler(onBack = ::leave)

    fun failed() {
        saving = false
        actions.say(resources.getString(R.string.error_save_failed, item.name), undo = null)
    }

    fun saveCopy() {
        saving = true
        scope.launch {
            val suffix = resources.getString(R.string.file_suffix_edited)
            val taken = MediaWriter(context).namesBeside(item, app.index.items.value.orEmpty()) + item.name
            val id = runCatching { saver.saveCopy(item, history.present, suffix, taken, located) }.getOrNull()
            if (id == null) {
                failed()
                return@launch
            }
            // The copy in the index before the viewer goes to it (three seconds at most). Owner's test of S7: an exception
            // thrown to stop waiting was never caught, so the app fell over right after saving
            withTimeoutOrNull(3_000) { app.index.items.first { list -> list.orEmpty().any { it.id == id } } }
            saving = false
            actions.say(resources.getString(if (motion) R.string.msg_copy_saved_no_motion else R.string.msg_copy_saved), undo = null)
            onCopySaved(id)
        }
    }

    fun replace() {
        val write: () -> Unit = {
            saving = true
            scope.launch {
                val done = runCatching { saver.replace(item, history.present, located) }.isSuccess
                if (!done) {
                    failed()
                    return@launch
                }
                saving = false
                actions.say(resources.getString(if (motion) R.string.msg_original_replaced_no_motion else R.string.msg_original_replaced), undo = null)
                onClose()
            }
        }
        val request = MediaWriter(context).writeRequest(listOf(item))
        if (request == null) write() else consent.ask(request, write)
    }

    // A surface of its own: an overlay over the whole app, outside what gives the shell's content its colour (owner's
    // test of S7: the names were black in the dark theme – as the picker's in S5c)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Top bar (SPEC §15.1): Close, Undo, Redo, Reset all, Save
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_close), ::leave, enabled = !saving)
            Spacer(Modifier.weight(1f))
            // Before and after (SPEC §15.1), as a button held rather than a hold on the photo (owner's test of S7: the hold
            // came late – it was checked only when the finger moved again – and the photo is the crop's to drag)
            IconButton(onClick = {}, interactionSource = compare, enabled = history.present.changed && !saving) {
                Icon(painterResource(R.drawable.ic_compare), contentDescription = stringResource(R.string.content_description_hold_original))
            }
            TooltipIconButton(R.drawable.ic_undo, stringResource(R.string.content_description_undo), { step(history.undo()) }, enabled = history.canUndo && !saving)
            TooltipIconButton(R.drawable.ic_redo, stringResource(R.string.content_description_redo), { step(history.redo()) }, enabled = history.canRedo && !saving)
            // Everything back at once, without asking; Undo brings it back (SPEC §15.1)
            TooltipIconButton(R.drawable.ic_restart_alt, stringResource(R.string.content_description_reset_all), { choose(EditState()) }, enabled = history.present.changed && !saving)
            Box {
                Button(onClick = { menu = true }, enabled = history.present.changed && !saving && preview != null, modifier = Modifier.padding(horizontal = 8.dp).height(40.dp)) {
                    if (saving) {
                        LoadingIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.button_saving))
                    } else {
                        Text(stringResource(R.string.button_save))
                    }
                }
                SaveMenu(menu, motion, saver.canReplace(item), located, onDismiss = { menu = false }, onCopy = {
                    menu = false
                    saveCopy()
                }, onReplace = {
                    menu = false
                    replacing = true
                })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().holdWhile(saving)) {
            val image = preview
            if (image != null && upright != null) CropArea(image, upright, state, comparing, cropMoves, now = { live ?: history.present }, onLive = { live = it }, onCommit = ::commit)
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).holdWhile(saving)) {
            if (upright != null) CropTools(state, EditGeometry.turned(upright, state), onLive = { live = it }, onCommit = ::commit, onChoose = ::choose)
            // No tabs while Crop is the only tool (the owner, 20 Nov 2026: no buttons that do nothing in a release);
            // Adjust, Filters and Draw bring them back with S9–S10
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.title_discard_changes)) },
            text = { Text(stringResource(R.string.desc_discard_photo)) },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.button_keep_editing)) } },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onClose()
                }) { Text(stringResource(R.string.button_discard)) }
            },
        )
    }
    if (replacing) {
        val text = stringResource(R.string.desc_replace_original_confirm, item.name) + if (motion) "\n\n" + stringResource(R.string.desc_replace_motion) else ""
        ConfirmDestructiveDialog(R.drawable.ic_save, stringResource(R.string.title_replace_original), AnnotatedString(text), stringResource(R.string.button_replace), onConfirm = {
            replacing = false
            replace()
        }, onDismiss = { replacing = false })
    }
}

/**
 * Save's menu (SPEC §15.6): Save as copy and Replace original, with what each means; for a motion photo a note
 * that it is saved without the motion. Replace is disabled for a type the platform can't write back (D26) and
 * without the right to the photos' location – replacing would take the location out of the original.
 */
@Composable
private fun SaveMenu(open: Boolean, motion: Boolean, canReplace: Boolean, located: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit, onReplace: () -> Unit) {
    val groups = if (motion) 2 else 1
    DropdownMenuPopup(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, groups)) {
            SelectableDropdownMenuItem(
                selected = false,
                onClick = onCopy,
                text = { Text(stringResource(R.string.menu_save_copy)) },
                supportingText = { Text(stringResource(R.string.desc_save_copy)) },
                shapes = MenuDefaults.itemShape(0, 2),
                leadingIcon = { Icon(painterResource(R.drawable.ic_file_copy), contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
            SelectableDropdownMenuItem(
                selected = false,
                onClick = onReplace,
                enabled = canReplace && located,
                text = { Text(stringResource(R.string.menu_replace_original)) },
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                !canReplace -> R.string.desc_replace_format
                                !located -> R.string.desc_replace_location
                                else -> R.string.desc_replace_original
                            },
                        ),
                    )
                },
                shapes = MenuDefaults.itemShape(1, 2),
                leadingIcon = { Icon(painterResource(R.drawable.ic_save), contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
        }
        if (motion) {
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, groups)) {
                SelectableDropdownMenuItem(
                    selected = false,
                    onClick = {},
                    enabled = false,
                    text = { Text(stringResource(R.string.menu_motion_note)) },
                    shapes = MenuDefaults.itemShape(0, 1),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_motion_photos_off), contentDescription = null, modifier = Modifier.size(20.dp)) },
                )
            }
        }
    }
}

/**
 * The photo and the crop's frame (SPEC §15.2): the view fits the crop to the area less a margin, the turned and
 * straightened photo under it; outside the crop the `scrim` at 32 %, the frame 2 dp, thirds 1 dp, corner handles
 * 24 × 6 dp (touched within 48 dp). A corner resizes the frame; one finger elsewhere moves the photo under it and two
 * zoom and move it (as iOS). [original] shows the original instead (the top bar's Compare, held).
 */
@Composable
private fun CropArea(image: ImageBitmap, upright: PxSize, state: EditState, original: Boolean, cropMoves: Int, now: () -> EditState, onLive: (EditState) -> Unit, onCommit: (EditState) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    var area by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(density) { 24.dp.toPx() }
    val turned = EditGeometry.turned(upright, state)
    val scope = rememberCoroutineScope()
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val reach = with(density) { 24.dp.toPx() }

    // A crop moved by a choice (a ratio, a reset, undo) goes there from where it was drawn, on the motion scheme's effects
    // curve – a spring's overshoot would take it past the photo for a moment
    val lastShown = remember { arrayOfNulls<sh.hnet.gallery.model.Crop>(1) }
    val cropFrom = remember(cropMoves) { lastShown[0] ?: state.crop }
    val cropProgress = remember(cropMoves) { Animatable(if (cropMoves == 0) 1f else 0f) }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(cropMoves) { cropProgress.animateTo(1f, effects) }
    val shownCrop = lerp(cropFrom, state.crop, cropProgress.value)
    SideEffect { lastShown[0] = shownCrop }

    // The view fits the crop (owner, 20 Nov 2026, as the Photos editor of iOS): the frame fills the area and the photo
    // lies under it, the rest of it dimmed around. A corner resizes the frame over a view that holds still ([frozen]);
    // let go, the view eases to fit the new crop ([viewFrom] → the crop). One finger anywhere else moves the photo and
    // two fingers zoom and move it, under a frame that stays – the view follows the crop as it changes (the owner,
    // after trying one finger on the frame: the photo moving is much better)
    var frozen by remember { mutableStateOf<sh.hnet.gallery.model.Crop?>(null) }
    var viewFrom by remember { mutableStateOf<HeldView?>(null) }
    val viewProgress = remember { Animatable(1f) }
    val easing = remember { arrayOfNulls<Job>(1) }
    // An ease begun before a turn or a mirror is in the shares of the photo as it lay then: dropped, the turn's own
    // motion takes over (review before v0.1.0)
    val viewCrop = frozen ?: viewFrom?.takeIf { it.quarters == state.quarters && it.flipped == state.flipped }?.let { lerp(it.crop, shownCrop, viewProgress.value) } ?: shownCrop
    val view = if (area.width == 0) null else viewOf(viewCrop, turned, area, margin)
    val viewNow = remember { arrayOfNulls<sh.hnet.gallery.model.Crop>(1) }
    SideEffect { viewNow[0] = viewCrop }
    val fit = view?.scale ?: 0.0

    // A turn or a mirror shown, not jumped to (owner's test of S7): the photo starts where it was – turned back by a
    // quarter at its old size, or turned over – and the motion scheme's spring brings it to its new place. The way it
    // turns is what the eye sees, not the count of quarters: after a mirror the count goes the other way.
    // The start is set in the composition that brings the new state, so its very first frame is drawn from there (the
    // owner's phone test: set in an effect, it came a frame late, and the new state flashed unturned). A turn begun
    // while another runs starts from where that one is
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val seen = remember { Seen(state.quarters, state.flipped) }
    val turn = remember(state.quarters, state.flipped) {
        val quarters = ((state.quarters - seen.quarters) % 4 + 4) % 4
        val mirrored = state.flipped != seen.flipped
        seen.quarters = state.quarters
        seen.flipped = state.flipped
        val was = seen.turn
        val (spin, zoom, over) = Snapshot.withoutReadObservation { Triple(was?.spin?.value ?: 0f, was?.zoom?.value ?: 1f, was?.over?.value ?: 0f) }
        val next = when {
            fit <= 0.0 -> Turn(0f, 1f, 0f)
            quarters == 1 || quarters == 3 -> {
                // The old look was the new one turned back: a turn left starts a quarter to the right; the view fitted the
                // crop as it lay then – its sides the other way round
                val left = (quarters == 1) != state.flipped
                val c = state.crop
                val fitBefore = min((area.width - 2 * margin) / (c.height * turned.height), (area.height - 2 * margin) / (c.width * turned.width))
                Turn(spin + if (left) 90f else -90f, zoom * (fitBefore / fit).toFloat(), over)
            }
            // The shorter way round: a mirror while one runs goes back the way it came
            mirrored -> Turn(spin, zoom, (over + 180f).let { if (it > 180f) it - 360f else it })
            else -> Turn(spin, zoom, over)
        }
        seen.turn = next
        next
    }
    LaunchedEffect(turn) {
        launch { turn.zoom.animateTo(1f, spatial) }
        launch { turn.over.animateTo(0f, spatial) }
        turn.spin.animateTo(0f, spatial)
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { area = it }.pointerInput(upright) {
            awaitEachGesture {
                val down = awaitFirstDown()
                // The state as it is now, not as last drawn: a quick second drag began from the one before the first
                val start = now()
                val t = EditGeometry.turned(upright, start)
                if (area.width == 0) return@awaitEachGesture
                // The view as drawn now (it may be easing): one finger works over it as it holds still
                val held = viewNow[0] ?: start.crop
                val v = viewOf(held, t, area, margin)
                fun sx(share: Double) = v.x0 + share * t.width * v.scale
                fun sy(share: Double) = v.y0 + share * t.height * v.scale
                val c = start.crop
                val corners = listOf(sx(c.left) to sy(c.top), sx(c.right) to sy(c.top), sx(c.right) to sy(c.bottom), sx(c.left) to sy(c.bottom))
                // The nearest corner within reach; on a crop too small for its handles to be apart, only right at one
                // (the first in reach resized from the top-left whenever a small crop was grabbed to move it)
                val distances = corners.map { (cx, cy) -> kotlin.math.hypot(down.position.x - cx, down.position.y - cy) }
                val nearest = distances.indices.minBy { distances[it] }
                val small = sx(c.right) - sx(c.left) < 3 * reach || sy(c.bottom) - sy(c.top) < 3 * reach
                val corner = if (distances[nearest] < (if (small) reach / 2 else reach)) nearest else -1
                // Nothing yet, a corner (the frame), two fingers or one off the corners (the photo under the frame)
                var mode = 0
                var last = start
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    // A second finger on a moving photo zooms it from there on; a corner's drag keeps to its one finger
                    if (mode != 1 && pressed >= 2) {
                        mode = 2
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            // The view as drawn (an ease after a corner may still run: it goes on, the fingers follow
                            // what is on screen); the fingers' point in the photo's shares
                            val drawn = viewOf(viewNow[0] ?: last.crop, t, area, margin)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val zoomed = EditGeometry.zoom(
                                last,
                                (centroid.x - drawn.x0) / (t.width * drawn.scale), (centroid.y - drawn.y0) / (t.height * drawn.scale),
                                zoom.toDouble(), 0.0, 0.0, t,
                            )
                            // The fingers' move at the scale the zoom really reached (it stops at the smallest crop and
                            // at the photo's edge)
                            val after = drawn.scale * (last.crop.width / zoomed.crop.width.coerceAtLeast(1e-9))
                            last = if (pan == Offset.Zero) zoomed else EditGeometry.move(zoomed, -pan.x / (t.width * after), -pan.y / (t.height * after), t)
                            onLive(last)
                        }
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        continue
                    }
                    // After two fingers, the one left lifts without moving anything
                    if (mode == 2) {
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val total = change.position - down.position
                    // The move so far when the gesture becomes a move, then each step
                    var step = change.positionChange()
                    if (mode == 0 && total.getDistance() > slop) {
                        if (corner >= 0) {
                            mode = 1
                            frozen = held
                        } else {
                            mode = 3
                            step = total
                        }
                    }
                    if (mode == 1 || mode == 3) {
                        last = if (mode == 1) {
                            EditGeometry.drag(start, corner, (change.position.x - v.x0) / (t.width * v.scale), (change.position.y - v.y0) / (t.height * v.scale), t)
                        } else {
                            // The photo follows the finger, so the crop goes the other way, at the scale on screen (an
                            // ease after a corner may still run; dropping it made the photo jump – review before v0.1.0)
                            val drawn = viewOf(viewNow[0] ?: last.crop, t, area, margin)
                            EditGeometry.move(last, -step.x / (t.width * drawn.scale), -step.y / (t.height * drawn.scale), t)
                        }
                        onLive(last)
                        change.consume()
                    } else if (change.positionChange() != Offset.Zero) {
                        change.consume()
                    }
                }
                if (mode == 1) {
                    // Let go: from the view held to one fitting the new crop
                    viewFrom = HeldView(held, start.quarters, start.flipped)
                    frozen = null
                    easing[0]?.cancel()
                    easing[0] = scope.launch {
                        viewProgress.snapTo(0f)
                        viewProgress.animateTo(1f, spatial)
                        viewFrom = null
                    }
                }
                onCommit(if (mode != 0) last else start)
            }
        },
    ) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                rotationZ = turn.spin.value
                scaleX = turn.zoom.value
                scaleY = turn.zoom.value
                rotationY = turn.over.value
                cameraDistance = 16 * density.density
            },
        ) {
            if (fit <= 0.0) return@Canvas
            val bitmap = image.asAndroidBitmap()
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                if (original) {
                    // The original as it is: upright, fitted, no change applied
                    val f = min((size.width - 2 * margin) / upright.width, (size.height - 2 * margin) / upright.height)
                    val m = Matrix().apply {
                        setScale(f.toFloat(), f.toFloat())
                        postTranslate(((size.width - upright.width * f) / 2).toFloat(), ((size.height - upright.height * f) / 2).toFloat())
                    }
                    native.drawBitmap(bitmap, m, paint)
                } else {
                    // The photo turned, mirrored and straightened, before the crop: the frame shows the crop over it
                    val shown = view ?: return@drawIntoCanvas
                    val m = Matrix().apply {
                        setValues(EditGeometry.matrix(upright, Orientation.NORMAL, state.copy(crop = sh.hnet.gallery.model.Crop.WHOLE)).values())
                        postScale(shown.scale.toFloat(), shown.scale.toFloat())
                        postTranslate(shown.x0.toFloat(), shown.y0.toFloat())
                    }
                    native.withClip(0f, 0f, size.width, size.height) { drawBitmap(bitmap, m, paint) }
                }
            }
            if (original) return@Canvas
            val shown = view ?: return@Canvas
            val c = shownCrop
            val l = (shown.x0 + c.left * turned.width * shown.scale).toFloat()
            val t = (shown.y0 + c.top * turned.height * shown.scale).toFloat()
            val r = (shown.x0 + c.right * turned.width * shown.scale).toFloat()
            val b = (shown.y0 + c.bottom * turned.height * shown.scale).toFloat()
            // Outside the crop under the scrim (SPEC §15.2, approved by the owner)
            val shade = scheme.scrim.copy(alpha = 0.32f)
            drawRect(shade, Offset.Zero, Size(size.width, t))
            drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(shade, Offset(0f, t), Size(l, b - t))
            drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
            val frame = with(density) { 2.dp.toPx() }
            val third = with(density) { 1.dp.toPx() }
            drawRect(scheme.onSurface, Offset(l, t), Size(r - l, b - t), style = Stroke(frame))
            for (i in 1..2) {
                val x = l + (r - l) * i / 3
                val y = t + (b - t) * i / 3
                drawLine(scheme.onSurface, Offset(x, t), Offset(x, b), third)
                drawLine(scheme.onSurface, Offset(l, y), Offset(r, y), third)
            }
            // The corner handles: two bars of 24 × 6 dp in each corner
            val long = with(density) { 24.dp.toPx() }
            val thick = with(density) { 6.dp.toPx() }
            for ((cx, cy, dx, dy) in listOf(listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(r, b, -1f, -1f), listOf(l, b, 1f, -1f))) {
                drawRect(scheme.onSurface, Offset(if (dx > 0) cx - thick / 2 else cx - long + thick / 2, if (dy > 0) cy - thick / 2 else cy - thick / 2), Size(long, thick))
                drawRect(scheme.onSurface, Offset(cx - thick / 2, if (dy > 0) cy - thick / 2 else cy - long + thick / 2), Size(thick, long))
            }
        }
        if (original) {
            // A label, not a chip to tap: on a surface of its own as Places' badges (owner's test of S7: an assist chip
            // has no container, only its outline, so the photo showed through)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.label_original), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/**
 * Crop's tools (SPEC §15.2): the ratios with the orientation switch, turn left and mirror; the straightening slider
 * (centred, −45°…45°, snapping at 0°) with its value and a reset.
 */
@Composable
private fun CropTools(state: EditState, turned: PxSize, onLive: (EditState) -> Unit, onCommit: (EditState) -> Unit, onChoose: (EditState) -> Unit) {
    val ratios = listOf(
        CropRatio.FREE to stringResource(R.string.ratio_free),
        CropRatio.ORIGINAL to stringResource(R.string.ratio_original),
        CropRatio.SQUARE to stringResource(R.string.ratio_square),
        CropRatio.FOUR_THREE to "4:3",
        CropRatio.THREE_TWO to "3:2",
        CropRatio.SIXTEEN_NINE to "16:9",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((ratio, label) in ratios) {
                FilterChip(selected = state.ratio == ratio, onClick = { onChoose(EditGeometry.withRatio(state, ratio, state.portrait, turned)) }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TooltipIconButton(R.drawable.ic_rotate_90_degrees_ccw, stringResource(R.string.content_description_rotate), { onCommit(EditGeometry.rotate(state)) })
            TooltipIconButton(R.drawable.ic_flip, stringResource(R.string.content_description_flip), { onCommit(EditGeometry.flip(state)) })
            // A ratio's sides swapped; a free crop and a square have none to swap
            val swappable = state.ratio != CropRatio.FREE && state.ratio != CropRatio.SQUARE
            TooltipIconButton(
                if (state.portrait) R.drawable.ic_crop_landscape else R.drawable.ic_crop_portrait,
                stringResource(R.string.content_description_swap_ratio),
                { onChoose(EditGeometry.withRatio(state, state.ratio, !state.portrait, turned)) },
                enabled = swappable,
            )
            // An icon for the slider's name, so the slider has the room (owner's test of S7: "Straighten" left it short)
            Icon(painterResource(R.drawable.ic_straighten), contentDescription = stringResource(R.string.label_straighten), modifier = Modifier.padding(start = 8.dp))
            // The centred slider takes its own state (SPEC §15.2): the thumb follows the finger as it is (writing a snapped
            // value back fought the slider's own drag – owner's test of S7: it trembled); the photo's angle snaps to 0°
            // near it, with a tick as it does; every move straightens the crop as it was when the drag began
            val latest by rememberUpdatedState(state)
            val haptics = LocalHapticFeedback.current
            val slider = remember { SliderState(state.angle.toFloat(), 0, -EditGeometry.MAX_ANGLE.toFloat()..EditGeometry.MAX_ANGLE.toFloat()) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(state.angle) { if (!dragging) slider.value = state.angle.toFloat() }
            Slider(
                state = slider,
                onValueChange = { value ->
                    dragging = true
                    slider.value = value
                    val angle = EditGeometry.snap(value.toDouble())
                    if (angle == 0.0 && latest.angle != 0.0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    if (angle != latest.angle) onLive(EditGeometry.withAngle(latest, angle, turned))
                },
                onValueChangeFinished = {
                    dragging = false
                    onCommit(latest)
                },
                track = { SliderDefaults.CenteredTrack(it) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            // The value in a width of its own, the widest it can be ("-45,0°"), figures of one width: a value of other length
            // made the slider beside it longer or shorter, so the same finger meant another angle (owner's test of S7)
            val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
            val number = remember { NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 1 } }
            val measurer = rememberTextMeasurer()
            val widest = stringResource(R.string.format_degrees, number.format(-EditGeometry.MAX_ANGLE))
            val valueWidth = with(LocalDensity.current) { remember(widest, style) { measurer.measure(widest, style).size.width }.toDp() }
            Text(
                stringResource(R.string.format_degrees, number.format(state.angle)),
                style = style,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(valueWidth),
            )
            TooltipIconButton(R.drawable.ic_restart_alt, stringResource(R.string.content_description_reset_value, stringResource(R.string.label_straighten)), { onCommit(EditGeometry.withAngle(state, 0.0, turned)) }, enabled = state.angle != 0.0)
        }
    }
}

/** Every touch taken before the content sees it while [held] (the editor while saving). */
private fun Modifier.holdWhile(held: Boolean): Modifier = if (!held) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** The view an ease starts from: a crop in the shares of the photo turned and mirrored as it was then. */
private class HeldView(val crop: sh.hnet.gallery.model.Crop, val quarters: Int, val flipped: Boolean)

/** How the crop area shows the turned photo: its shares to the area's pixels ([x0] + share × size × [scale]). */
private class CropView(val scale: Double, val x0: Double, val y0: Double)

/** The view fitting [crop] to the area less its margin, the crop in the middle. */
private fun viewOf(crop: sh.hnet.gallery.model.Crop, turned: PxSize, area: IntSize, margin: Float): CropView {
    val w = max(crop.width * turned.width, 1e-6)
    val h = max(crop.height * turned.height, 1e-6)
    val scale = min((area.width - 2 * margin) / w, (area.height - 2 * margin) / h)
    return CropView(scale, area.width / 2.0 - crop.centerX * turned.width * scale, area.height / 2.0 - crop.centerY * turned.height * scale)
}

/** Between two crops, [p] of the way. */
private fun lerp(a: sh.hnet.gallery.model.Crop, b: sh.hnet.gallery.model.Crop, p: Float): sh.hnet.gallery.model.Crop = if (p >= 1f) b else sh.hnet.gallery.model.Crop(
    a.left + (b.left - a.left) * p,
    a.top + (b.top - a.top) * p,
    a.right + (b.right - a.right) * p,
    a.bottom + (b.bottom - a.bottom) * p,
)

/** A turn's or a mirror's motion: where it starts, each going home to none. */
private class Turn(spin: Float, zoom: Float, over: Float) {
    val spin = Animatable(spin)
    val zoom = Animatable(zoom)
    val over = Animatable(over)
}

/** The quarters and mirror last shown, and their motion. */
private class Seen(var quarters: Int, var flipped: Boolean, var turn: Turn? = null)

/** The preview's long side: the photo area of a phone, a little more. */
private const val PREVIEW = 2048

