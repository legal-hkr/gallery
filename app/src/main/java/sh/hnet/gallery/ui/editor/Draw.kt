package sh.hnet.gallery.ui.editor

import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withClip
import kotlin.math.min
import kotlin.math.roundToInt
import sh.hnet.gallery.R
import sh.hnet.gallery.data.AdjustShader
import sh.hnet.gallery.data.MarkupPainter
import sh.hnet.gallery.model.Adjustments
import sh.hnet.gallery.model.Affine
import sh.hnet.gallery.model.Crop
import sh.hnet.gallery.model.EditGeometry
import sh.hnet.gallery.model.EditState
import sh.hnet.gallery.model.MarkColors
import sh.hnet.gallery.model.MarkSize
import sh.hnet.gallery.model.MarkTool
import sh.hnet.gallery.model.Markup
import sh.hnet.gallery.model.Orientation
import sh.hnet.gallery.model.Pt
import sh.hnet.gallery.model.PxSize
import sh.hnet.gallery.model.Stroke
import sh.hnet.gallery.model.TextMark

/** Draw's choices of this editing: the tool, the size and the ink. */
class DrawChoice {
    var tool by mutableStateOf(MarkTool.PEN)
    var size by mutableStateOf(MarkSize.MEDIUM)
    var color by mutableStateOf(MarkColors.ALL[2])

    /** Ends a text being typed (set by [DrawArea] while it is shown); whether that made a step of the history. */
    var finishTyping: () -> Boolean = { false }

    /** A text field is open. */
    var open by mutableStateOf(false)

    /**
     * The open field holds a change – a new text not blank, or an opened one altered: Save and Reset all count it, an
     * empty field doesn't (review before v0.1.1: an empty field let Replace original write the photo over unedited).
     */
    var pending by mutableStateOf(false)
}

/** A text's letters' height for each size dot, as a share of the photo's shorter side. */
private fun textHeight(size: MarkSize): Double = when (size) {
    MarkSize.THIN -> 0.04
    MarkSize.MEDIUM -> 0.06
    MarkSize.THICK -> 0.09
}

/**
 * A text being typed: a new one (no [id]) or one of the marks opened again. [anchor] (upright pixels) is its first line's
 * left end – where the tap was – and stays put while typing; the block's middle follows from it when it ends.
 */
private class Typing(val id: Long?, val anchor: Pt, val rotation: Double, val flipped: Boolean, val height: Double, val color: Int, val initial: String) {
    val input = TextFieldState(initial)
    val text: String get() = input.text.toString()
}

/**
 * Draw (SPEC §15.5, LOGIKA §20.4): the photo as it will be saved – cropped, no frame – with its marks. One finger draws,
 * erases, or places and moves a text; two fingers zoom and move the view for precision (the crop stays), or move, size
 * and turn the text they start on (D33). A tap with Text writes a new text there, or opens the one under it again.
 */
@Composable
internal fun DrawArea(image: ImageBitmap, upright: PxSize, state: EditState, original: Boolean, choice: DrawChoice, now: () -> EditState, onLive: (EditState) -> Unit, onCommit: (EditState) -> Unit, editable: Boolean = true) {
    val density = LocalDensity.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(density) { 24.dp.toPx() }
    val turned = EditGeometry.turned(upright, state)
    // The view: the crop fitted, then the user's own zoom for precision (two fingers), reset when the crop changes
    // One state for the gesture to write to: remembered by the crop, a new one after Undo went unseen by the gesture still
    // writing to the old one, and two fingers stopped zooming (review of S9a)
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(state.crop, state.quarters, state.flipped, state.angle) {
        zoom = 1f
        pan = Offset.Zero
    }
    val base = if (area.width == 0) null else viewOf(state.crop, turned, area, margin)
    // Upright preview pixels → screen
    fun mapOf(s: EditState): Affine? {
        val v = base ?: return null
        val toTurned = EditGeometry.matrix(upright, Orientation.NORMAL, s.copy(crop = Crop.WHOLE))
        val center = Pt(area.width / 2.0, area.height / 2.0)
        return toTurned
            .then(Affine.scale(v.scale))
            .then(Affine.translate(v.x0, v.y0))
            .then(Affine.translate(-center.x, -center.y))
            .then(Affine.scale(zoom.toDouble()))
            .then(Affine.translate(center.x + pan.x, center.y + pan.y))
    }
    val map = mapOf(state)
    val mapNow by rememberUpdatedState(map)
    val choiceTool by rememberUpdatedState(choice.tool)
    // The stroke under the finger, drawn as it grows
    val growing = remember { mutableStateListOf<Pt>() }
    var typing by remember { mutableStateOf<Typing?>(null) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val adjuster = remember { AdjustShader() }
    val headroom = rememberScreenHeadroom()
    val reach = with(density) { 16.dp.toPx() }

    fun commitTyping(): Boolean {
        val t = typing ?: return false
        typing = null
        choice.open = false
        choice.pending = false
        val s = now()
        val rest = s.marks.filterNot { it.id == t.id }
        // Only the end trimmed: the start stays where the tap was
        val text = t.text.trimEnd()
        // Emptied: the text goes (a new one was never there)
        val next = if (text.isBlank()) rest else {
            val aspect = MarkupPainter.aspectOf(text)
            val heightPx = t.height * min(upright.width, upright.height)
            val center = Markup.textCenter(t.anchor, heightPx, aspect, text.count { it == '\n' } + 1, t.rotation, t.flipped)
            rest + TextMark(t.id ?: System.nanoTime(), text, t.color, t.height, Markup.toShare(center, upright), t.rotation, aspect, t.flipped)
        }
        if (next == s.marks) return false
        onCommit(s.copy(marks = next))
        return true
    }
    // Back, or leaving Draw (a tab, saving's end), ends the typing too – Enter now breaks the line
    BackHandler(enabled = typing != null) { commitTyping() }
    // The field's state for the top bar: open, and whether it holds a change
    LaunchedEffect(typing) {
        val t = typing
        choice.open = t != null
        if (t == null) {
            choice.pending = false
            return@LaunchedEffect
        }
        snapshotFlow { t.text }.collect { text -> choice.pending = if (t.id == null) text.isNotBlank() else text.trimEnd() != t.initial.trimEnd() }
    }
    // Leaving Draw for Adjust or Filters – the same area, without drawing – ends the typing too (review before v0.1.1:
    // the field and the keyboard stayed over Adjust's sliders)
    LaunchedEffect(editable) {
        if (!editable) commitTyping()
    }
    val commitNow by rememberUpdatedState(::commitTyping)
    DisposableEffect(choice) {
        choice.finishTyping = { commitNow() }
        onDispose {
            choice.finishTyping = { false }
            commitNow()
            // Gone with the area (Crop's tab): nothing open any more (review before v0.1.1: Save stayed enabled)
            choice.open = false
            choice.pending = false
        }
    }

    Box(
        // Clipped to its place: zoomed, the photo covered the top bar and its Save (owner's test of S9a)
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { area = it }.then(if (!editable) Modifier.pointerInput(Unit) {
            // Adjust and Filters (owner's test of S10a): the view alone – two fingers zoom and move it as in Draw, and one
            // moves it, as nothing is drawn there
            detectTransformGestures { _, move, change, _ ->
                zoom = (zoom * change).coerceIn(1f, 8f)
                pan += move
            }
        } else Modifier.pointerInput(upright) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = now()
                val m = mapNow ?: return@awaitEachGesture
                val back = m.inverse()
                // Screen → upright pixels, and the reach of a finger there
                fun toUpright(p: Offset) = back.map(Pt(p.x.toDouble(), p.y.toDouble()))
                val scale = kotlin.math.hypot(m.sx, m.ky)
                val reachPx = reach / scale
                val tool = choiceTool
                if (typing != null) {
                    // A touch beside the field ends the typing
                    commitTyping()
                    return@awaitEachGesture
                }
                val startText = Markup.textAt(start.marks, toUpright(down.position), reachPx, upright)
                var mode = 0 // 0 nothing yet, 1 drawing or erasing, 2 two fingers on the view, 3 a text moved, 4 a text turned and sized
                var last = start
                var moved = false
                growing.clear()
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (pressed >= 2 && mode != 1) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (mode == 0 || mode == 3) mode = if (startText != null && tool == MarkTool.TEXT) 4 else 2
                        if (mode == 4 && startText != null) {
                            // The text follows the fingers: moved, sized and turned (D33)
                            val current = last.marks.firstOrNull { it.id == startText.id } as? TextMark ?: startText
                            val c = Markup.toPx(current.center, upright)
                            val from = toUpright(centroid)
                            val to = toUpright(centroid + event.calculatePan())
                            val moved2 = Markup.toShare(Pt(c.x + to.x - from.x, c.y + to.y - from.y), upright)
                            val next = current.copy(
                                center = moved2,
                                height = (current.height * event.calculateZoom()).coerceIn(0.01, 0.5),
                                rotation = current.rotation + Markup.turnBy(m, event.calculateRotation().toDouble()),
                            )
                            last = last.copy(marks = last.marks.map { if (it.id == next.id) next else it })
                            onLive(last)
                        } else {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                            pan += event.calculatePan()
                        }
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (mode == 2 || mode == 4) {
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val position = change.position
                    // Pen and eraser start past the touch slop: a second finger before it makes a pinch, not a stray mark
                    // (review of S9a); the stroke still starts where the finger came down
                    val started = mode == 1 || (position - down.position).getDistance() > viewConfiguration.touchSlop
                    when (tool) {
                        MarkTool.PEN, MarkTool.HIGHLIGHTER -> if (started) {
                            mode = 1
                            if (growing.isEmpty()) growing.add(Markup.toShare(toUpright(down.position), upright))
                            growing.add(Markup.toShare(toUpright(position), upright))
                        }
                        MarkTool.ERASER -> if (started) {
                            val first = mode != 1
                            mode = 1
                            var left = Markup.erase(last.marks, toUpright(position), reachPx, upright)
                            if (first) left = Markup.erase(left, toUpright(down.position), reachPx, upright)
                            if (left.size != last.marks.size) {
                                last = last.copy(marks = left)
                                onLive(last)
                            }
                        }
                        MarkTool.TEXT -> if (startText != null && (moved || (position - down.position).getDistance() > viewConfiguration.touchSlop)) {
                            // One finger moves the text it started on
                            moved = true
                            mode = 3
                            val d = toUpright(position).let { p -> toUpright(down.position).let { q -> Pt(p.x - q.x, p.y - q.y) } }
                            val c = Markup.toPx(startText.center, upright)
                            val next = startText.copy(center = Markup.toShare(Pt(c.x + d.x, c.y + d.y), upright))
                            last = last.copy(marks = last.marks.map { if (it.id == next.id) next else it })
                            onLive(last)
                        }
                    }
                    change.consume()
                }
                when {
                    growing.isNotEmpty() -> {
                        val leastPx = 1.5 / scale
                        val points = Markup.thin(growing.map { Markup.toPx(it, upright) }, leastPx).map { Markup.toShare(it, upright) }
                        growing.clear()
                        onCommit(start.copy(marks = start.marks + Stroke(System.nanoTime(), tool == MarkTool.HIGHLIGHTER, choice.color, choice.size, points)))
                    }
                    // A dot: a tap with the pen
                    mode == 0 && (tool == MarkTool.PEN || tool == MarkTool.HIGHLIGHTER) ->
                        onCommit(start.copy(marks = start.marks + Stroke(System.nanoTime(), tool == MarkTool.HIGHLIGHTER, choice.color, choice.size, listOf(Markup.toShare(toUpright(down.position), upright)))))
                    // A tap with the eraser takes the mark under it (review of S9a: only a moving finger erased)
                    mode == 0 && tool == MarkTool.ERASER -> {
                        val left = Markup.erase(start.marks, toUpright(down.position), reachPx, upright)
                        if (left.size != start.marks.size) onCommit(start.copy(marks = left))
                    }
                    // A tap with Text: the text under it opened again, or a new one there – level and readable on screen
                    // whatever the photo's turn and mirror (review of S9a)
                    mode == 0 && tool == MarkTool.TEXT -> typing = if (startText != null) {
                        val anchor = Markup.textAnchor(Markup.toPx(startText.center, upright), startText.heightPx(upright), startText.aspect, startText.lines, startText.rotation, startText.flipped)
                        Typing(startText.id, anchor, startText.rotation, startText.flipped, startText.height, startText.color, startText.text)
                    } else {
                        val (rotation, flipped) = Markup.levelOn(m)
                        Typing(null, toUpright(down.position), rotation, flipped, textHeight(choice.size), choice.color, "")
                    }
                    last != start -> onCommit(last)
                }
            }
        }),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val m = map ?: return@Canvas
            val v = base ?: return@Canvas
            val bitmap = image.asAndroidBitmap()
            // The crop's frame on screen: what is saved, and all that shows
            val frame = run {
                val c = state.crop
                val center = Pt(area.width / 2.0, area.height / 2.0)
                fun sx(x: Double) = ((v.x0 + x * turned.width * v.scale) - center.x) * zoom + center.x + pan.x
                fun sy(y: Double) = ((v.y0 + y * turned.height * v.scale) - center.y) * zoom + center.y + pan.y
                floatArrayOf(sx(c.left).toFloat(), sy(c.top).toFloat(), sx(c.right).toFloat(), sy(c.bottom).toFloat())
            }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.withClip(frame[0], frame[1], frame[2], frame[3]) {
                    val matrix = Matrix().apply { setValues(m.values()) }
                    adjuster.draw(this, bitmap, matrix, if (original) Adjustments.NONE else state.adjust, paint, AdjustShader.hdrWeight(bitmap, headroom()))
                    if (!original) {
                        val editing = typing?.id
                        MarkupPainter.draw(this, state.marks.filter { it.id != editing }, upright, matrix)
                        if (growing.isNotEmpty()) {
                            MarkupPainter.draw(this, listOf(Stroke(0, choice.tool == MarkTool.HIGHLIGHTER, choice.color, choice.size, growing.toList())), upright, matrix)
                        }
                    }
                }
            }
        }
        typing?.let { t ->
            val m = map
            if (m != null) TextField(t, m, upright)
        }
    }
}

/**
 * The text being typed, where it stands on the photo, in its ink and size: its first line starting where the tap was,
 * turned about there (the same pose as about the middle once it is done). Enter breaks the line; a touch beside it,
 * Back or leaving Draw ends it.
 */
@Composable
private fun TextField(typing: Typing, map: Affine, upright: PxSize) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(typing) { focus.requestFocus() }
    val anchor = map.map(typing.anchor)
    val scale = kotlin.math.hypot(map.sx, map.ky)
    val heightPx = typing.height * min(upright.width, upright.height) * scale
    val linePx = (heightPx * TextMark.LINE).toFloat()
    val fontSize = with(density) { heightPx.toFloat().toSp() }
    Box(
        Modifier
            .wrapContentSize(unbounded = true)
            .offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) },
    ) {
        BasicTextField(
            state = typing.input,
            textStyle = TextStyle(
                color = Color(typing.color),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                // Lines as far apart as the painter draws them, the letters' middle in the middle of each
                lineHeight = with(density) { linePx.toSp() },
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            ),
            cursorBrush = SolidColor(Color(typing.color)),
            modifier = Modifier
                .focusRequester(focus)
                .widthIn(min = 2.dp)
                .anchoredAt(linePx / 2, Markup.screenTurn(map, typing.rotation, typing.flipped).toFloat()),
        )
    }
}

/**
 * Places the content with its left end, [firstMiddle] down, at the parent's origin (the offset above puts that on the
 * anchor), turned [rotation] degrees about that point.
 */
private fun Modifier.anchoredAt(firstMiddle: Float, rotation: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, -firstMiddle.roundToInt()) {
                rotationZ = rotation
                transformOrigin = TransformOrigin(0f, if (placeable.height == 0) 0f else firstMiddle / placeable.height)
            }
        }
    },
)

/**
 * Draw's tools (SPEC §15.5): the four tools as connected toggles – only the chosen one with its name, the others their
 * icons with a tooltip –, then the three sizes and the six inks (none for the eraser, which takes whole marks).
 */
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DrawTools(choice: DrawChoice) {
    val tools = listOf(
        Triple(MarkTool.PEN, R.drawable.ic_ink_pen, R.string.tool_pen),
        Triple(MarkTool.HIGHLIGHTER, R.drawable.ic_ink_highlighter, R.string.tool_highlighter),
        Triple(MarkTool.TEXT, R.drawable.ic_title, R.string.tool_text),
        Triple(MarkTool.ERASER, R.drawable.ic_ink_eraser, R.string.tool_eraser),
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            tools.forEachIndexed { index, (tool, icon, label) ->
                val chosen = choice.tool == tool
                val name = stringResource(label)
                // The chosen tool takes the rest of the row; the weight on a box straight in the row – given to the tooltip's
                // box it never reached the row, and the chosen one's full width pushed the others off (owner's test of S9a)
                Box(if (chosen) Modifier.weight(1f) else Modifier) {
                // The icons alone have their name as a tooltip (SPEC §15.5)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { if (!chosen) PlainTooltip { Text(name) } },
                    state = rememberTooltipState(),
                ) {
                ToggleButton(
                    checked = chosen,
                    onCheckedChange = { if (it) choice.tool = tool },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        tools.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = (if (chosen) Modifier.fillMaxWidth() else Modifier.width(56.dp)).semantics {
                        role = Role.RadioButton
                        contentDescription = name
                    },
                ) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                    if (chosen) Text(name, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
                }
                }
            }
        }
        if (choice.tool != MarkTool.ERASER) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val sizes = listOf(MarkSize.THIN to R.string.content_description_size_small, MarkSize.MEDIUM to R.string.content_description_size_medium, MarkSize.THICK to R.string.content_description_size_large)
                sizes.forEachIndexed { index, (size, label) ->
                    val chosen = choice.size == size
                    val name = stringResource(label)
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, CircleShape)
                            .selectable(selected = chosen, role = Role.RadioButton) { choice.size = size }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size((6 + index * 5).dp).background(if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    }
                }
                Box(Modifier.width(8.dp))
                val names = listOf(R.string.color_black, R.string.color_white, R.string.color_red, R.string.color_yellow, R.string.color_green, R.string.color_blue)
                MarkColors.ALL.forEachIndexed { index, ink ->
                    val chosen = choice.color == ink
                    val name = stringResource(names[index])
                    val ring = MaterialTheme.colorScheme.primary
                    val gap = MaterialTheme.colorScheme.surfaceContainer
                    Box(
                        Modifier
                            .size(40.dp)
                            .selectable(selected = chosen, role = Role.RadioButton) { choice.color = ink }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        // The chosen ink ringed 2 dp in primary after a 2 dp gap (SPEC); every ink with a 1 dp outline
                        if (chosen) Box(Modifier.size(40.dp).border(2.dp, ring, CircleShape).padding(2.dp).border(2.dp, gap, CircleShape))
                        Box(Modifier.size(32.dp).background(Color(ink), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    }
                }
            }
        }
    }
}
