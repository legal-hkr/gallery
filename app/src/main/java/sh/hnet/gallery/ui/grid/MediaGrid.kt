package sh.hnet.gallery.ui.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.R
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.DayPlaces
import sh.hnet.gallery.model.GridGeometry
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Morphs
import sh.hnet.gallery.model.PinchSteps
import sh.hnet.gallery.model.Selection
import sh.hnet.gallery.model.Timeline
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.model.lerp
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.Thumb
import sh.hnet.gallery.ui.components.Thumbnails
import sh.hnet.gallery.ui.components.placeData
import sh.hnet.gallery.ui.components.placeLanguage
import sh.hnet.gallery.ui.components.settlementOf
import sh.hnet.gallery.ui.viewer.mediaOrigin

/** Where the grid should stand after a tap in the month view: the item tapped, at its height (SPEC §4.4). */
private data class Anchor(val id: Long, val y: Float, val months: Boolean)

/**
 * A change of step under way, started by a pinch crossing a threshold ([PinchSteps]): the tile under
 * the fingers and where in it they touched ([fy], 0–1 down the tile), the fingers' place then
 * ([pivot]), and how far from [from] to [to] ([progress], run through by the motion scheme).
 */
private class Morph(val anchor: String, val fy: Float, val pivot: Offset, val from: GridStep, val to: GridStep) {
    val progress = Animatable(0f)
}

/** What the grid settles on after a change of step: the step, and the content's place at the top of the screen as the layer left it. */
private data class Settle(val step: GridStep, val y: Float)

/**
 * The photo grid of every list of items (SPEC §4.4, §4.5): day or month headers spanning the
 * width, square tiles, the pinch between steps. A pinch changes the step when it crosses a
 * threshold ([PinchSteps], the owner, 19 Nov 2026, as ComfyChair's workflow canvas); the change
 * then runs through with the motion scheme's spatial motion, every tile visible in either step
 * moving and resizing from its place in one to its place in the other, the tile under the fingers
 * staying under them. Crossing the next threshold meanwhile starts the next change once this one
 * is done. There is no standard component for it: a gesture over `LazyVerticalGrid`, taking two
 * fingers before the grid scrolls with them, and a layer drawing the tiles meanwhile from [GridGeometry].
 *
 * @param days The timeline by day, @param months by month: both, as the pinch moves between them
 * @param today The phone's today, for "Today" and "Yesterday"
 * @param top Items before the entries (cards that scroll away with the grid)
 * @param onOpen A tap on a tile outside the month view
 */
@Composable
fun MediaGrid(
    days: Timeline,
    months: Timeline,
    step: GridStep,
    onStepChange: (GridStep) -> Unit,
    facts: Map<Long, Fact>,
    state: LazyGridState,
    today: LocalDate,
    modifier: Modifier = Modifier,
    top: LazyGridScope.() -> Unit = {},
    topCount: Int = 0,
    selection: SelectionState? = null,
    bottomPadding: Dp = 0.dp,
    /** Picking several (SPEC §17.4): the circles show before anything is chosen, a tap always toggles. */
    alwaysSelecting: Boolean = false,
    /** Picking: items of the types asked for; the others are dimmed and do nothing (SPEC §17.4). */
    pickable: (MediaItem) -> Boolean = { true },
    /** A long press other than selecting (the picker's look at an item, SPEC §17.4). */
    onHold: ((MediaItem) -> Unit)? = null,
    onOpen: (MediaItem) -> Unit = {},
) {
    val timeline = if (step.months) months else days
    // Selecting (SPEC §5.3): the tiles in reading order, and each header's tiles
    val order = remember(timeline) { timeline.entries.mapNotNull { (it as? TimelineEntry.Tile)?.item?.id } }
    val orderIndex = remember(order) { order.withIndex().associate { (i, id) -> id to i } }
    // A header's circle takes only the tiles that can be picked (SPEC §17.4)
    val groups = remember(timeline) {
        val blocked = timeline.entries.mapNotNullTo(HashSet()) { (it as? TimelineEntry.Tile)?.item?.takeIf { item -> !pickable(item) }?.id }
        Selection.groups(timeline.entries).mapValues { (_, ids) -> if (blocked.isEmpty()) ids else ids.filter { it !in blocked } }
    }
    var drag by remember { mutableStateOf<DragRun?>(null) }
    val haptics = LocalHapticFeedback.current
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var morph by remember { mutableStateOf<Morph?>(null) }
    var settle by remember { mutableStateOf<Settle?>(null) }
    val scope = rememberCoroutineScope()
    val stepNow by rememberUpdatedState(step)
    val onStepChangeNow by rememberUpdatedState(onStepChange)
    val motion = MaterialTheme.motionScheme
    val resolver = LocalContext.current.contentResolver

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Tablets get more columns at every step (SPEC §23)
        val widthFactor = when {
            maxWidth >= 840.dp -> 2f
            maxWidth >= 600.dp -> 1.5f
            else -> 1f
        }
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight.toFloat()
        fun columnsOf(s: GridStep) = (s.columns * widthFactor).toInt()
        fun gapOf(s: GridStep) = if (s.months) 1.dp else 2.dp
        val columns = columnsOf(step)
        val gap = gapOf(step)
        val corner = if (step.months) 2.dp else 6.dp
        val cellPx = with(density) { ((maxWidth - gap * (columns - 1)) / columns).toPx() }
        // The headers' height as the grid draws them (font scale included), for the layer's geometry
        var headerPx by remember { mutableIntStateOf(with(density) { 44.dp.roundToPx() }) }
        // A day's header with its place under it (LOGIKA §17.5), as last measured
        var placedPx by remember { mutableIntStateOf(with(density) { 64.dp.roundToPx() }) }
        val places = placeData()
        val language = placeLanguage()
        val dayPlaces by produceState(emptyMap<String, IntArray>(), days, facts, places) {
            val data = places
            value = if (data == null) emptyMap() else withContext(Dispatchers.Default) { DayPlaces.of(days.entries) { id -> data.settlementOf(facts[id]) } }
        }
        // The lines in words, for the grid and the pinch's layer alike
        val resources = LocalResources.current
        val placeLines = remember(dayPlaces, places, language) {
            dayPlaces.mapValues { (_, ids) ->
                val names = ids.map { places?.settlementName(it, language).orEmpty() }
                if (names.size == 2) resources.getString(R.string.label_two_places, names[0], names[1]) else names[0]
            }
        }
        // The items before the entries (the partial-access card), as last measured: the layer's layouts start after them
        var leadPx by remember { mutableIntStateOf(0) }
        val lead = if (topCount > 0) leadPx else 0
        // Each step's layout, made when a pinch first needs it
        val geometries = remember(days, months, widthPx, headerPx, placedPx, dayPlaces, widthFactor, lead) { HashMap<GridStep, GridGeometry>() }
        fun geometry(s: GridStep): GridGeometry = geometries.getOrPut(s) {
            GridGeometry.of((if (s.months) months else days).entries, columnsOf(s), widthPx, with(density) { gapOf(s).roundToPx() }, headerPx, lead, placedPx) { it.key in dayPlaces }
        }

        /**
         * Reads the thumbnails of the tiles a change of step will show, at the new step's size: the grid then takes over
         * with them in memory instead of reading them once on screen.
         */
        suspend fun prefetch(change: Morph) {
            val from = geometry(change.from)
            val to = geometry(change.to)
            val fromEntries = (if (change.from.months) months else days).entries
            val toEntries = (if (change.to.months) months else days).entries
            val px = to.cell.toInt()
            coroutineScope {
                for (piece in Morphs.pieces(from, to, change.anchor, change.pivot.y, change.fy, heightPx, 1f)) {
                    val entry = if (piece.inFrom) fromEntries[piece.index] else toEntries[piece.index]
                    if (entry is TimelineEntry.Tile) launch { Thumbnails.load(resolver, entry.item, px, crop = true) }
                }
            }
        }

        /** The tile under a point of the grid, if any (a header or a gap: none). */
        fun tileUnder(point: Offset): Long? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            point.x >= info.offset.x && point.x < info.offset.x + info.size.width && point.y >= info.offset.y && point.y < info.offset.y + info.size.height
        }?.let { (it.key as? String)?.toLongOrNull() }

        /** The drag's run reaching the tile under the finger (SPEC §5.3). */
        fun dragTo(run: DragRun, point: Offset) {
            val id = tileUnder(point) ?: return
            val index = orderIndex[id] ?: return
            selection?.selected = Selection.run(run.base, order, run.anchor, index, run.select)
        }

        // Near the top or bottom 64 dp the grid scrolls by itself while a drag selects, faster closer to the edge (SPEC §5.3)
        val edgePx = with(density) { 64.dp.toPx() }
        val fastestPx = with(density) { 24.dp.toPx() }
        LaunchedEffect(drag) {
            val run = drag ?: return@LaunchedEffect
            while (true) {
                withFrameNanos { }
                if (!run.pointer.isSpecified) continue
                val y = run.pointer.y
                val speed = when {
                    y < edgePx -> -fastestPx * (1f - (y / edgePx).coerceIn(0f, 1f))
                    y > heightPx - edgePx -> fastestPx * (1f - ((heightPx - y) / edgePx).coerceIn(0f, 1f))
                    else -> 0f
                }
                if (speed != 0f) {
                    state.scrollBy(speed)
                    dragTo(run, run.pointer)
                }
            }
        }

        /** The card's height before the entries, when it and the first entry are on screen. */
        fun measureLead() {
            if (topCount == 0) return
            val items = state.layoutInfo.visibleItemsInfo
            val card = items.firstOrNull { it.index == 0 } ?: return
            val first = items.firstOrNull { it.index == topCount } ?: return
            leadPx = first.offset.y - card.offset.y - with(density) { gap.roundToPx() }
        }

        /** The tile under a point of the grid, or the nearest one, and how far down in it the point is (0–1). */
        fun tileAt(point: Offset): Pair<String, Float>? = state.layoutInfo.visibleItemsInfo
            .filter { (it.key as? String)?.toLongOrNull() != null }
            .minByOrNull { info ->
                val cx = info.offset.x + info.size.width / 2f
                val cy = info.offset.y + info.size.height / 2f
                abs(cx - point.x) + abs(cy - point.y)
            }?.let { info ->
                (info.key as String) to ((point.y - info.offset.y) / info.size.height).coerceIn(0f, 1f)
            }

        // After a tap in the month view: the tapped tile back at its height, once the entries are those of the new step
        LaunchedEffect(timeline, columns) {
            val target = anchor ?: return@LaunchedEffect
            if (timeline.months != target.months) return@LaunchedEffect
            val index = timeline.indexOf[target.id]
            if (index != null) {
                state.scrollToItem(index + topCount)
                state.scrollBy(-(target.y - cellPx / 2f))
            }
            anchor = null
        }
        // After a pinch: the grid at its new step with the anchor tile where the layer left it, then the layer goes
        LaunchedEffect(settle, timeline, columns) {
            val target = settle ?: return@LaunchedEffect
            if (step != target.step || timeline.months != target.step.months) return@LaunchedEffect
            // Straight to where the layer's last frame showed the content (the anchor tile then lies where the layer had it).
            // Scrolling to the anchor and then back by its top went wrong where the list's end kept the anchor from the top
            val (index, offset) = geometry(target.step).scrollTo(target.y, topCount)
            state.scrollToItem(index, offset)
            withFrameNanos { }
            morph = null
            settle = null
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            // Room under the last line for the selection's toolbar (as Contacts')
            contentPadding = PaddingValues(bottom = bottomPadding),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoom = 1f
                        var pinching = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2) {
                                pinching = true
                                zoom *= event.calculateZoom()
                                // A change under way (until the grid shows its result) runs through first; the zoom meanwhile
                                // goes on counting, so a pinch already past the next threshold starts the next change after it
                                if (morph == null) {
                                    val (target, rest) = PinchSteps.track(stepNow, zoom)
                                    zoom = rest
                                    if (target != null) {
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        measureLead()
                                        tileAt(centroid)?.let { (key, fy) ->
                                            val change = Morph(key, fy, centroid, stepNow, target)
                                            morph = change
                                            scope.launch {
                                                // The new step's thumbnails read meanwhile, so the grid shows them sharp when it takes over
                                                val prefetch = launch { prefetch(change) }
                                                change.progress.animateTo(1f, motion.defaultSpatialSpec())
                                                prefetch.cancel()
                                                val geo = geometry(target)
                                                val index = geo.index(change.anchor)
                                                val y = if (index != null) -Morphs.offset(geo, index, change.pivot.y, change.fy, heightPx) else 0f
                                                settle = Settle(target, y)
                                                onStepChangeNow(target)
                                            }
                                        }
                                    }
                                }
                            }
                            // Two fingers belong to the pinch, also while one of them lifts first: the grid doesn't scroll with them
                            if (pinching) event.changes.forEach { it.consume() }
                        }
                    }
                }
                // A drag after a long press selects a run (SPEC §5.3): read before the grid scrolls with the finger
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val run = drag
                            if (run != null && event.changes.size == 1) {
                                val change = event.changes.first()
                                run.pointer = change.position
                                dragTo(run, change.position)
                                change.consume()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        drag = null
                    }
                }
                // The layer draws the grid while a change of step is under way
                .graphicsLayer { alpha = if (morph != null) 0f else 1f },
        ) {
            top()
            items(
                timeline.entries,
                key = { it.key },
                span = { if (it is TimelineEntry.Tile) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
                contentType = { it::class },
            ) { entry ->
                when (entry) {
                    is TimelineEntry.Day, is TimelineEntry.Month -> {
                        val group = groups[entry.key].orEmpty()
                        val pick = if ((alwaysSelecting || selection?.active == true) && selection != null && group.isNotEmpty()) {
                            HeaderPick(selected = selection.selected.containsAll(group)) { selection.selected = Selection.toggleGroup(selection.selected, group) }
                        } else null
                        val place = placeLines[entry.key]
                        val measured = Modifier.onSizeChanged { if (it.height > 0) { if (place != null) placedPx = it.height else headerPx = it.height } }
                        if (entry is TimelineEntry.Day) DayHeader(entry.date, today, measured, pick, place) else MonthHeader((entry as TimelineEntry.Month).month, today, measured, pick)
                    }
                    is TimelineEntry.Tile -> Tile(
                        entry.item,
                        facts[entry.item.id],
                        cellPx.toInt(),
                        corner,
                        marks = !step.months,
                        selecting = alwaysSelecting || selection?.active == true,
                        selected = selection?.selected?.contains(entry.item.id) == true,
                        enabled = pickable(entry.item),
                        onLongClick = if (onHold != null) ({
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onHold(entry.item)
                        }) else if (selection == null) null else ({
                            // Selecting starts with this tile, or a long press while selecting flips it; keeping the finger down drags a run
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val base = selection.selected
                            val select = entry.item.id !in base
                            selection.selected = if (select) base + entry.item.id else base - entry.item.id
                            orderIndex[entry.item.id]?.let { index -> drag = DragRun(index, base, select) }
                        }),
                        onClick = {
                            if (!pickable(entry.item)) {
                                // Not of a type asked for: nothing (SPEC §17.4)
                            } else if (alwaysSelecting || selection?.active == true) {
                                // While selecting a tap toggles, it never opens the viewer (SPEC §5.2)
                                selection?.toggle(entry.item.id)
                            } else if (step.months) {
                                // A tile of the month view opens step 2 at its day, not the viewer (SPEC §4.4)
                                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.key }
                                val y = info?.let { it.offset.y + it.size.height / 2f } ?: 0f
                                anchor = Anchor(entry.item.id, y, months = false)
                                onStepChange(GridStep.MEDIUM)
                            } else {
                                onOpen(entry.item)
                            }
                        },
                    )
                }
            }
        }

        morph?.let { change -> MorphLayer(change, ::geometry, days, months, today, heightPx, placeLines) }
    }
}

/**
 * The change of step's layer (LOGIKA §3): every entry visible at either step, between its place at
 * one and at the other ([Morphs.pieces]). Tiles show the thumbnail already in memory (nothing is read
 * meanwhile); headers of the same day move with their tiles, those of only one step fade.
 */
@Composable
private fun MorphLayer(change: Morph, geometry: (GridStep) -> GridGeometry, days: Timeline, months: Timeline, today: LocalDate, viewport: Float, placeLines: Map<String, String>) {
    val t = change.progress.value
    val from = geometry(change.from)
    val to = geometry(change.to)
    val pieces = Morphs.pieces(from, to, change.anchor, change.pivot.y, change.fy, viewport, t)
    val fromEntries = (if (change.from.months) months else days).entries
    val toEntries = (if (change.to.months) months else days).entries
    val density = LocalDensity.current
    val corner = lerp(if (change.from.months) 2f else 6f, if (change.to.months) 2f else 6f, t).dp
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (piece in pieces) {
            val entry = if (piece.inFrom) fromEntries[piece.index] else toEntries[piece.index]
            key(entry.key) {
                val box = piece.box
                val place = Modifier
                    .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                    .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                    .graphicsLayer { alpha = piece.alpha }
                when (entry) {
                    is TimelineEntry.Tile -> MorphTile(entry.item, place.clip(RoundedCornerShape(corner)))
                    is TimelineEntry.Day -> DayHeader(entry.date, today, place, place = placeLines[entry.key])
                    is TimelineEntry.Month -> MonthHeader(entry.month, today, place)
                }
            }
        }
    }
}

/** A tile in the pinch's layer: the largest thumbnail already in memory, else the empty container. */
@Composable
private fun MorphTile(item: MediaItem, modifier: Modifier) {
    when (val thumb = Thumbnails.peekAny(item)) {
        is Thumb.Image -> Image(thumb.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
        else -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer))
    }
}

/** A day's header (SPEC §4.3): "Today", "Yesterday", the date with the weekday, the year only when it isn't this one. */
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier, pick: HeaderPick? = null, place: String? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val text = when (date) {
        today -> stringResource(R.string.label_today)
        today.minusDays(1) -> stringResource(R.string.label_yesterday)
        else -> formatDate(stringResource(if (date.year == today.year) R.string.format_day else R.string.format_day_year), date, locale)
    }
    Header(text, modifier, pick, place)
}

/** A month's header in the month view (SPEC §4.4). */
@Composable
private fun MonthHeader(month: YearMonth, today: LocalDate, modifier: Modifier = Modifier, pick: HeaderPick? = null) {
    val locale = LocalConfiguration.current.locales[0]
    Header(formatDate(stringResource(if (month.year == today.year) R.string.format_month else R.string.format_month_year), month.atDay(1), locale), modifier, pick)
}

/** A header's circle while selecting (SPEC §5.3): its group all selected or not, and the toggle. */
private class HeaderPick(val selected: Boolean, val toggle: () -> Unit)

/** A drag selecting a run (SPEC §5.3): the first tile's place in reading order, the selection before, the state the run takes. */
private class DragRun(val anchor: Int, val base: Set<Long>, val select: Boolean) {
    /** Where the finger is; unknown until it first moves (owner's test of S4a: taken as the top corner, the grid ran up selecting everything). */
    var pointer by mutableStateOf(Offset.Unspecified)
}

@Composable
private fun Header(text: String, modifier: Modifier, pick: HeaderPick? = null, place: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.semantics { heading() },
            )
            // The day's place (SPEC §4.3): one line, cut short rather than wrapped
            if (place != null) Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pick != null) {
            val label = stringResource(R.string.content_description_select_day)
            Box(
                Modifier.size(24.dp).clickable(onClickLabel = label, onClick = pick.toggle).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { SelectionMark(pick.selected) }
        }
    }
}

/** A date by a CLDR pattern in the app's language, its first letter capitalized (SPEC §4.3). */
fun formatDate(pattern: String, date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(date).replaceFirstChar { it.titlecase(locale) }

/** A video's length as the tile shows it: 0:42, 12:05, 1:02:33. */
fun formatDuration(ms: Long): String {
    val total = (ms + 500) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds) else "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * A square tile (SPEC §4.5): the thumbnail cut to the square, rounded corners, and in steps 1–3 the
 * marks: at the top end the video's length, a motion photo or RAW; at the bottom start a favorite.
 */
@Composable
private fun Tile(
    item: MediaItem,
    fact: Fact?,
    px: Int,
    corner: Dp,
    marks: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val cannot = stringResource(R.string.desc_cannot_pick)
    // The tile is where the viewer flies from and back to (SPEC §6.1)
    SelectableFrame(
        selecting && enabled,
        selected,
        corner,
        // The ripple keeps the tile's corners; a tile that can't be picked is dimmed as M3's disabled content
        modifier
            .aspectRatio(1f)
            .mediaOrigin(item.id.toString(), corner)
            .clip(RoundedCornerShape(corner))
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f).semantics { stateDescription = cannot }),
    ) {
        MediaThumbnail(item, px, Modifier.fillMaxSize())
        // Marks are hidden while selecting (the circle takes the corner) and on a tile that can't be picked
        if (!marks || selecting || !enabled) return@SelectableFrame
        when {
            item.video -> Mark(Modifier.align(Alignment.TopEnd)) {
                Text(formatDuration(item.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            fact?.motion == true -> Mark(Modifier.align(Alignment.TopEnd)) {
                Icon(painterResource(R.drawable.ic_motion_photos_on), contentDescription = stringResource(R.string.content_description_motion_photo), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
            }
            item.raw -> Mark(Modifier.align(Alignment.TopEnd)) {
                Text(stringResource(R.string.label_raw), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (item.favorite) {
            Mark(Modifier.align(Alignment.BottomStart)) {
                Icon(painterResource(R.drawable.ic_favorite_filled), contentDescription = stringResource(R.string.content_description_favorite), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A small opaque pill over the tile (SPEC §4.5): `surface-container`, 20 dp high, 10 dp corners. */
@Composable
internal fun Mark(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .padding(4.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
