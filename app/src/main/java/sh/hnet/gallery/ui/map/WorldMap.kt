package sh.hnet.gallery.ui.map

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withTranslation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.model.Border
import sh.hnet.gallery.model.MapPoint
import sh.hnet.gallery.model.Mercator
import sh.hnet.gallery.model.PlaceData
import sh.hnet.gallery.model.PlaceLanguage

/**
 * Where the map looks (SPEC §13.3): its centre on the unit square ([Mercator]) and the world's width in pixels. The
 * world never gets narrower than the view, nor wider than [MAX_WORLD] (about region level: the data goes no further).
 */
@Stable
class MapState(x: Double = 0.5, y: Double = 0.5, world: Double = 0.0) {
    var x by mutableDoubleStateOf(x)
        private set
    var y by mutableDoubleStateOf(y)
        private set
    var world by mutableDoubleStateOf(world)
        private set
    var size by mutableStateOf(IntSize.Zero)
        internal set

    val pxPerDegree: Double get() = world / 360.0

    fun screenX(px: Double): Float = ((px - x) * world + size.width / 2.0).toFloat()

    fun screenY(py: Double): Float = ((py - y) * world + size.height / 2.0).toFloat()

    /** Centre on [lat], [lon] showing about [degrees] of longitude across the view. */
    fun show(lat: Double, lon: Double, degrees: Double) {
        x = Mercator.x(lon)
        y = Mercator.y(lat)
        world = if (size.width > 0) size.width * 360.0 / degrees else 0.0
        clamp()
    }

    /** Around [points] with some margin; the whole world when there are none. */
    fun fit(points: List<MapPoint>) {
        if (points.isEmpty() || size.width == 0) {
            showWorld()
            return
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = max(maxX - minX, 1e-4) * 1.3
        val spanY = max(maxY - minY, 1e-4) * 1.3
        x = (minX + maxX) / 2
        y = (minY + maxY) / 2
        world = min(size.width / spanX, size.height / spanY)
        clamp()
    }

    /** The whole world, as wide as the view. */
    fun showWorld() {
        x = 0.5
        y = 0.5
        world = 0.0
        clamp()
    }

    fun panBy(dx: Float, dy: Float) {
        x -= dx / world
        y -= dy / world
        clamp()
    }

    /** Zoom by [factor] keeping the map's point under [focus] where it is. */
    fun zoomBy(factor: Float, focus: Offset) {
        val px = x + (focus.x - size.width / 2.0) / world
        val py = y + (focus.y - size.height / 2.0) / world
        world *= factor
        clamp()
        x = px - (focus.x - size.width / 2.0) / world
        y = py - (focus.y - size.height / 2.0) / world
        clamp()
    }

    internal fun clamp() {
        if (size.width == 0) return
        val least = max(size.width, 1).toDouble()
        world = world.coerceIn(least, MAX_WORLD)
        x = if (world <= size.width) 0.5 else x.coerceIn(size.width / 2.0 / world, 1 - size.width / 2.0 / world)
        y = if (world <= size.height) 0.5 else y.coerceIn(size.height / 2.0 / world, 1 - size.height / 2.0 / world)
    }

    companion object {
        const val MAX_WORLD = 1_048_576.0
    }
}

/**
 * The map drawn by the app (SPEC §13.3, LOGIKA §17.5): countries from the place data on `surfaceContainerLowest`
 * over a `surfaceContainerHigh` sea, coasts and borders in `outlineVariant`, regions dashed once they are useful,
 * country and region names where they fit. Borders become paths in the unit square once per country and level and
 * are only moved and scaled after. [interactive]: pinch, drag with a fling, double tap in, two-finger tap out.
 */
@Composable
fun WorldMap(
    state: MapState,
    data: PlaceData,
    language: PlaceLanguage,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onTap: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // Each kind of name its own type, as printed maps (owner's test of S6b2): countries in spaced capitals (true small
    // capitals depend on the font having them, and without them the system quietly writes lower case), regions in
    // italics, towns plain beside their dots
    // and the order by lightness, not hue (the owner: countries stand out more): countries bold in the strongest
    // `onSurface`, regions in the lighter `outline`, towns plain `onSurface`
    val countryStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    val regionStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.outline, fontStyle = FontStyle.Italic)
    val locale = LocalConfiguration.current.locales[0]
    // The base map drawn as vectors on every frame, the way the GPU does it cheaply (owner's phone tests of S6b2:
    // the first vector map was slow, a picture drawn off the main thread and then tiles loaded in visibly). What made
    // the first one slow was ours: lines 1 dp wide at a scale new every frame, which the system turns into outlines on
    // the processor again each frame, and dashes cut on the processor too; and paths made while a frame was drawn.
    // Now borders are hairlines (one screen pixel at any scale, drawn by the GPU as they are), solid, and paths are
    // made off the main thread – a place not made yet at its level shows a coarser one meanwhile
    val store = remember(data) { MapPaths(data) }
    LaunchedEffect(store) { store.build() }
    val land = remember(scheme.surfaceContainerLowest) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scheme.surfaceContainerLowest.toArgb(); style = Paint.Style.FILL } }
    val countryLine = remember(scheme.outline) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scheme.outline.toArgb(); style = Paint.Style.STROKE; strokeWidth = 0f } }
    val regionLine = remember(scheme.outlineVariant) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scheme.outlineVariant.toArgb(); style = Paint.Style.STROKE; strokeWidth = 0f } }
    val labels = remember(data, language) { HashMap<Long, TextLayoutResult>() }
    val anchors = remember(data) { HashMap<Long, Offset>() }
    // Boxes and the labels' order (largest first) once, not on every frame
    val countryBoxes = remember(data) { Array(data.countryCount) { data.countryBox(it) } }
    val regionBoxes = remember(data) { Array(data.regionCount) { data.regionBox(it) } }
    val countryOrder = remember(data) { countryBoxes.indices.sortedByDescending { area(countryBoxes[it]) } }
    // Towns to fill the land (owner's test of S6b2: it stood empty), the largest first; read once, off the main thread
    val cities by produceState(emptyList<City>(), data) {
        value = withContext(Dispatchers.Default) {
            buildList {
                for (i in 0 until data.settlementCount) {
                    val people = data.settlementPopulation(i)
                    if (people >= CITY_LEAST && data.townOf(i) == i) add(City(i, Mercator.x(data.settlementLon(i)), Mercator.y(data.settlementLat(i)), people))
                }
            }.sortedByDescending { it.people }
        }
    }
    val cityStyle = MaterialTheme.typography.bodySmall.copy(color = scheme.onSurface)
    val cityLabels = remember(data, language) { HashMap<Int, TextLayoutResult>() }
    val regionOrder = remember(data) { regionBoxes.indices.sortedByDescending { area(regionBoxes[it]) } }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    var fling by remember { mutableStateOf<Job?>(null) }
    var lastTap by remember { mutableStateOf(0L to Offset.Zero) }

    fun animateZoom(factor: Float, focus: Offset) {
        fling?.cancel()
        fling = scope.launch {
            val progress = Animatable(1f)
            var applied = 1f
            progress.animateTo(factor) {
                state.zoomBy(value / applied, focus)
                applied = value
            }
        }
    }

    val gestures = if (!interactive) Modifier else Modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fling?.cancel()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var moved = false
            var travelled = 0f
            var zoomed = 1f
            var pointers = 1
            var centroid = down.position
            var last = down.uptimeMillis
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                pointers = max(pointers, pressed)
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                event.calculateCentroid(useCurrent = false).takeIf { it != Offset.Unspecified }?.let { centroid = it }
                if (!moved) {
                    travelled += pan.getDistance()
                    zoomed *= zoom
                    if (travelled > slop || abs(1 - zoomed) > 0.05f) moved = true
                }
                if (moved) {
                    state.zoomBy(zoom, centroid)
                    state.panBy(pan.x, pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                event.changes.firstOrNull()?.let {
                    last = it.uptimeMillis
                    if (pressed == 1) tracker.addPosition(it.uptimeMillis, it.position)
                }
            } while (event.changes.any { it.pressed })
            when {
                // Two fingers tapped together: out (SPEC §13.3)
                !moved && pointers >= 2 && last - down.uptimeMillis < 400 -> animateZoom(0.5f, centroid)
                !moved && pointers == 1 -> {
                    val (time, where) = lastTap
                    if (down.uptimeMillis - time < 300 && (down.position - where).getDistance() < slop * 4) {
                        lastTap = 0L to Offset.Zero
                        animateZoom(2f, down.position)
                    } else {
                        lastTap = down.uptimeMillis to down.position
                        onTap()
                    }
                }
                moved && pointers == 1 -> {
                    val velocity = tracker.calculateVelocity()
                    fling = scope.launch {
                        var previous = Offset.Zero
                        AnimationState(Offset.VectorConverter, Offset.Zero, Offset(velocity.x, velocity.y)).animateDecay(exponentialDecay(frictionMultiplier = 2f)) {
                            state.panBy(value.x - previous.x, value.y - previous.y)
                            previous = value
                        }
                    }
                }
            }
        }
    }

    Canvas(modifier.onSizeChanged { state.size = it; state.clamp() }.then(gestures)) {
        if (state.size.width == 0) return@Canvas
        drawRect(scheme.surfaceContainerHigh)
        val world = state.world
        val perDegree = state.pxPerDegree
        val view = viewBox(state)
        // Land, country borders, then regions from where they are useful; coarser paths while finer ones are made
        store.version
        val level = LEVELS.indexOfFirst { it * perDegree <= 1.5 }.let { if (it < 0) LEVELS.lastIndex else it }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.withTranslation((state.size.width / 2.0 - state.x * world).toFloat(), (state.size.height / 2.0 - state.y * world).toFloat()) {
                scale(world.toFloat(), world.toFloat())
                for (c in 0 until data.countryCount) {
                    if (!view.meets(countryBoxes[c])) continue
                    val path = store.country(c, level) ?: continue
                    drawPath(path, land)
                    drawPath(path, countryLine)
                }
                if (perDegree >= REGIONS_FROM) {
                    for (r in 0 until data.regionCount) {
                        if (!view.meets(regionBoxes[r])) continue
                        drawPath(store.region(r) ?: continue, regionLine)
                    }
                }
            }
        }
        // Names where they fit, the largest places first, none over another
        val taken = ArrayList<Rect>()
        fun label(kind: Int, index: Int, box: IntArray, name: () -> String, border: () -> Border) {
            val wide = (box[2] - box[0]) / 1e4 * perDegree
            val anchor = anchors.getOrPut(key(kind, index, 0)) { anchorOf(border(), box) }
            val text = labels.getOrPut(key(kind, index, 0)) { if (kind == 0) measurer.measure(name().uppercase(locale), countryStyle) else measurer.measure(name(), regionStyle) }
            if (text.size.width > wide * 0.8) return
            val cx = state.screenX(anchor.x.toDouble())
            val cy = state.screenY(anchor.y.toDouble())
            val rect = Rect(cx - text.size.width / 2f, cy - text.size.height / 2f, cx + text.size.width / 2f, cy + text.size.height / 2f)
            if (rect.right < 0 || rect.left > size.width || rect.bottom < 0 || rect.top > size.height) return
            if (taken.any { it.overlaps(rect) }) return
            taken.add(rect)
            drawText(text, topLeft = rect.topLeft)
        }
        for (c in countryOrder) if (view.meets(countryBoxes[c])) label(0, c, countryBoxes[c], { data.countryName(c, language) }) { data.countryBorder(c, 0) }
        if (perDegree >= REGION_NAMES_FROM) {
            for (r in regionOrder) if (view.meets(regionBoxes[r])) label(1, r, regionBoxes[r], { data.regionName(r, language) }) { data.regionBorder(r, 2) }
        }
        // Towns: a dot and the name beside it, smaller ones as the map comes closer, none over another name
        val least = CITY_STEPS.firstOrNull { perDegree < it.first }?.second ?: CITY_LEAST
        val dot = with(density) { 2.5.dp.toPx() }
        val gap = with(density) { 4.dp.toPx() }
        var drawn = 0
        for (city in cities) {
            if (city.people < least || drawn >= CITIES_MOST) break
            val cx = state.screenX(city.x)
            val cy = state.screenY(city.y)
            if (cx < 0 || cy < 0 || cx > size.width || cy > size.height) continue
            val text = cityLabels.getOrPut(city.index) { measurer.measure(data.settlementName(city.index, language), cityStyle) }
            val rect = Rect(cx - dot, cy - text.size.height / 2f, cx + dot + gap + text.size.width, cy + text.size.height / 2f)
            if (rect.right > size.width || taken.any { it.overlaps(rect) }) continue
            taken.add(rect)
            drawCircle(scheme.onSurfaceVariant, dot, Offset(cx, cy))
            drawText(text, topLeft = Offset(cx + dot + gap, cy - text.size.height / 2f))
            drawn++
        }
    }
}

/** What the view shows, in the data's units (1e-4°), for leaving out what's beyond it. */
private class ViewBox(val west: Int, val south: Int, val east: Int, val north: Int) {
    fun meets(box: IntArray): Boolean = box[0] <= east && box[2] >= west && box[1] <= north && box[3] >= south
}

private fun DrawScope.viewBox(state: MapState): ViewBox {
    val world = state.world
    val left = state.x - size.width / 2.0 / world
    val right = state.x + size.width / 2.0 / world
    val top = state.y - size.height / 2.0 / world
    val bottom = state.y + size.height / 2.0 / world
    return ViewBox(
        (Mercator.lon(left.coerceIn(0.0, 1.0)) * 1e4).toInt() - 1,
        (Mercator.lat(bottom.coerceIn(0.0, 1.0)) * 1e4).toInt() - 1,
        (Mercator.lon(right.coerceIn(0.0, 1.0)) * 1e4).toInt() + 1,
        (Mercator.lat(top.coerceIn(0.0, 1.0)) * 1e4).toInt() + 1,
    )
}

/**
 * The map's paths (the unit square, once per place and level), made off the main thread: the whole world's coarsest
 * at once, the rest as a frame asks for them. A frame takes what is made – for a country, the finest made up to the
 * level asked – and [version] moves with every batch made, for the frame to take the new ones.
 */
@Stable
private class MapPaths(private val data: PlaceData) {
    var version by mutableIntStateOf(0)
        private set
    private val made = ConcurrentHashMap<Long, Path>()
    private val asked = Channel<Long>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    /** A country's path at [level], else the finest made below it; null until its coarsest is made. */
    fun country(index: Int, level: Int): Path? {
        made[key(0, index, level)]?.let { return it }
        ask(key(0, index, level))
        for (l in level - 1 downTo 0) made[key(0, index, l)]?.let { return it }
        return null
    }

    fun region(index: Int): Path? = made[key(1, index, 2)] ?: run {
        ask(key(1, index, 2))
        null
    }

    private fun ask(key: Long) {
        if (pending.add(key)) asked.trySend(key)
    }

    /** Makes the world's coarsest paths, then what frames ask for, in batches; runs while the map is shown. */
    suspend fun build() {
        withContext(Dispatchers.Default) {
            for (c in 0 until data.countryCount) made[key(0, c, 0)] = pathOf(data.countryBorder(c, 0))
        }
        version++
        while (true) {
            val first = asked.receive()
            val batch = ArrayList<Long>().apply { add(first) }
            while (true) batch.add(asked.tryReceive().getOrNull() ?: break)
            withContext(Dispatchers.Default) {
                for (key in batch) {
                    val kind = (key shr 40).toInt()
                    val level = ((key shr 32) and 0xFF).toInt()
                    val index = (key and 0xFFFFFFFFL).toInt()
                    made[key] = pathOf(if (kind == 0) data.countryBorder(index, level) else data.regionBorder(index, level))
                }
            }
            version++
        }
    }
}

/** A border as a path on the unit square; holes by the even–odd rule, as the data's rings are. */
private fun pathOf(border: Border): Path = Path().apply {
    fillType = Path.FillType.EVEN_ODD
    for (ring in border.rings) {
        for (i in 0 until ring.size / 2) {
            val px = Mercator.x(ring[i * 2] / 1e4).toFloat()
            val py = Mercator.y(ring[i * 2 + 1] / 1e4).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

/** Where a place's name goes: the middle of its largest ring's box (a country's main land, not its islands). */
private fun anchorOf(border: Border, box: IntArray): Offset {
    val ring = border.rings.maxByOrNull { r ->
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (i in 0 until r.size / 2) {
            minX = min(minX, r[i * 2]); maxX = max(maxX, r[i * 2]); minY = min(minY, r[i * 2 + 1]); maxY = max(maxY, r[i * 2 + 1])
        }
        (maxX - minX).toLong() * (maxY - minY)
    }
    if (ring == null || ring.isEmpty()) return Offset(Mercator.x((box[0] + box[2]) / 2e4).toFloat(), Mercator.y((box[1] + box[3]) / 2e4).toFloat())
    var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
    for (i in 0 until ring.size / 2) {
        minX = min(minX, ring[i * 2]); maxX = max(maxX, ring[i * 2]); minY = min(minY, ring[i * 2 + 1]); maxY = max(maxY, ring[i * 2 + 1])
    }
    return Offset(Mercator.x((minX + maxX) / 2e4).toFloat(), Mercator.y((minY + maxY) / 2e4).toFloat())
}

/** A town on the map: its index, its point on the unit square, its people. */
private class City(val index: Int, val x: Double, val y: Double, val people: Long)

/** Towns of this many people and more are read for the map (the closest view's least). */
private const val CITY_LEAST = 30_000L

/**
 * Below so many pixels a degree, towns from so many people: the world shows countries alone (owner's test of S6b2: a
 * step lower than first built, which was too dense).
 */
private val CITY_STEPS = listOf(5.0 to Long.MAX_VALUE, 15.0 to 5_000_000L, 40.0 to 1_000_000L, 100.0 to 300_000L, 300.0 to 100_000L)

/** At most so many towns named at once. */
private const val CITIES_MOST = 40

private fun area(box: IntArray): Long = (box[2] - box[0]).toLong() * (box[3] - box[1])

private fun key(kind: Int, index: Int, level: Int): Long = (kind.toLong() shl 40) or (level.toLong() shl 32) or index.toLong()

/** The data's simplification at each level, in degrees (tools/places/build.py). */
private val LEVELS = doubleArrayOf(0.05, 0.01, 0.003)

/** Regions are drawn from this many pixels a degree on (a large region some hundreds of pixels across). */
private const val REGIONS_FROM = 40.0

private const val REGION_NAMES_FROM = 80.0
