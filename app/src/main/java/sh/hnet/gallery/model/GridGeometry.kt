package sh.hnet.gallery.model

import kotlin.math.roundToInt

/**
 * Where every entry of a grid lies (LOGIKA §3, the pinch's morph): full-width headers and square
 * tiles in lines, `gap` apart, as `LazyVerticalGrid` with fixed columns lays them out – so the pinch
 * can move each tile between its place at one step and at the next.
 *
 * @param tops Each entry's top in content pixels
 * @param lefts Each entry's left
 * @param sizes Each entry's width and height (a header spans the width)
 */
class GridGeometry private constructor(
    val keys: List<String>,
    private val tops: FloatArray,
    private val lefts: FloatArray,
    private val widths: FloatArray,
    private val heights: FloatArray,
    val height: Float,
    val cell: Float,
) {
    // Built from the entries' keys by name: inside an apply on the map, `keys` would be the map's own (empty) key set – the bug that left the pinch's layer blank
    private val indexOf: Map<String, Int> = keys.withIndex().associate { (i, key) -> key to i }

    fun index(key: String): Int? = indexOf[key]

    fun box(index: Int) = Box(lefts[index], tops[index], lefts[index] + widths[index], tops[index] + heights[index])

    fun isHeader(index: Int): Boolean = widths[index] > cell + 0.5f

    /** The entries any part of which lies between [from] and [to] (content pixels), in order. */
    fun between(from: Float, to: Float): IntRange {
        if (keys.isEmpty()) return IntRange.EMPTY
        // The first entry ending below [from], then on while entries start above [to]
        var low = 0
        var high = keys.lastIndex
        while (low < high) {
            val mid = (low + high) / 2
            if (tops[mid] + heights[mid] < from) low = mid + 1 else high = mid
        }
        var last = low
        while (last + 1 <= keys.lastIndex && tops[last + 1] <= to) last++
        return low..last
    }

    /**
     * Where the grid stands when the content's [y] (pixels from its very top) is at the top of the screen: the
     * grid's index of the entry there ([leadItems] items before the entries) and how far into it, never negative.
     */
    fun scrollTo(y: Float, leadItems: Int): Pair<Int, Int> {
        val start = tops.firstOrNull() ?: 0f
        if (y < start || keys.isEmpty()) return 0 to y.coerceAtLeast(0f).roundToInt()
        // The last entry starting at or above y: its line is the first on screen
        var low = 0
        var high = keys.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (tops[mid] <= y) low = mid else high = mid - 1
        }
        return (low + leadItems) to (y - tops[low]).roundToInt()
    }

    companion object {
        /**
         * The layout of [entries] in [columns] columns across [width] pixels, [gap] between cells and
         * lines, headers [headerHeight] high, after [lead] pixels of other items (a card) and their gap.
         * In whole pixels as `LazyVerticalGrid` lays them out (`GridCells.Fixed`): the cells share the
         * width less the gaps, the first ones a pixel wider for what doesn't divide, and a line is as
         * high as its widest cell – fractions here drifted from the real grid line by line, and the
         * grid jumped when it took over from the pinch's layer (owner's phone test, 19 Nov 2026).
         * A day's header with its place under it (LOGIKA §17.5) is [placedHeight] high, [placed] tells which.
         */
        fun of(entries: List<TimelineEntry>, columns: Int, width: Int, gap: Int, headerHeight: Int, lead: Int = 0, placedHeight: Int = headerHeight, placed: (TimelineEntry) -> Boolean = { false }): GridGeometry {
            val inner = width - gap * (columns - 1)
            val slot = inner / columns
            val wider = inner % columns
            val cellWidths = IntArray(columns) { slot + if (it < wider) 1 else 0 }
            val cellLefts = IntArray(columns).also { for (c in 1 until columns) it[c] = it[c - 1] + cellWidths[c - 1] + gap }
            val n = entries.size
            val tops = FloatArray(n)
            val lefts = FloatArray(n)
            val widths = FloatArray(n)
            val heights = FloatArray(n)
            var y = if (lead > 0) lead + gap else 0
            var column = 0
            // The open line's height: its widest cell
            var line = 0
            for ((i, entry) in entries.withIndex()) {
                if (entry is TimelineEntry.Tile) {
                    if (column == columns) {
                        y += line + gap
                        column = 0
                        line = 0
                    }
                    tops[i] = y.toFloat()
                    lefts[i] = cellLefts[column].toFloat()
                    widths[i] = cellWidths[column].toFloat()
                    heights[i] = cellWidths[column].toFloat()
                    line = maxOf(line, cellWidths[column])
                    column++
                } else {
                    // A header closes the tiles' line before it and takes a line of its own
                    if (line > 0) y += line + gap
                    tops[i] = y.toFloat()
                    lefts[i] = 0f
                    widths[i] = width.toFloat()
                    val high = if (placed(entry)) placedHeight else headerHeight
                    heights[i] = high.toFloat()
                    y += high + gap
                    column = 0
                    line = 0
                }
            }
            val height = if (line > 0) y + line else (y - gap).coerceAtLeast(0)
            return GridGeometry(entries.map { it.key }, tops, lefts, widths, heights, height.toFloat(), slot.toFloat())
        }
    }
}

/** Between two numbers by [t]. */
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Between two boxes by [t]. */
fun lerp(a: Box, b: Box, t: Float) = Box(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

/**
 * The pinch between the grid's steps by thresholds (the owner, 19 Nov 2026, as ComfyChair's workflow
 * canvas): the fingers don't move the tiles; spreading them [THRESHOLD] times apart (or closing them
 * as much) since the last change switches to the neighbouring step at once, and the change then runs
 * through by itself. The zoom counts again from 1 after each change, so going back needs the same
 * way back – the hysteresis that keeps a pinch near a threshold from flickering between two steps.
 */
object PinchSteps {
    const val THRESHOLD = 1.25f

    /**
     * Where the pinch stands after the fingers' [zoom] (since the last change) at [step]: the step it
     * changes to, or null, and the zoom to count on with. At the first or last step the zoom doesn't
     * build up beyond 1 in the direction there is no step, so the way back starts at once.
     */
    fun track(step: GridStep, zoom: Float): Pair<GridStep?, Float> {
        val steps = GridStep.entries
        val i = steps.indexOf(step)
        return when {
            // Spread: fewer, larger tiles
            zoom >= THRESHOLD && i > 0 -> steps[i - 1] to 1f
            zoom <= 1f / THRESHOLD && i < steps.lastIndex -> steps[i + 1] to 1f
            i == 0 -> null to zoom.coerceAtMost(1f)
            i == steps.lastIndex -> null to zoom.coerceAtLeast(1f)
            else -> null to zoom
        }
    }
}

/** One entry of the pinch's layer: where it is drawn now and how opaque. */
data class MorphPiece(val index: Int, val inFrom: Boolean, val box: Box, val alpha: Float)

object Morphs {
    /**
     * Where a layout lies on screen during a pinch: the anchor entry with the fingers ([pivotY]) at the
     * same place in it ([fy], 0–1 down the tile), kept from leaving a gap above the first entry or below the last.
     */
    fun offset(geo: GridGeometry, anchor: Int, pivotY: Float, fy: Float, viewport: Float): Float {
        val box = geo.box(anchor)
        val wanted = pivotY - fy * box.height - box.top
        val lowest = if (geo.height > viewport) viewport - geo.height else 0f
        return wanted.coerceIn(lowest, 0f)
    }

    /**
     * The pinch's layer between [from] and [to] at [t]: every entry visible (with a margin) in either,
     * between its two places; an entry of only one layout fades with the way.
     */
    fun pieces(from: GridGeometry, to: GridGeometry, anchor: String, pivotY: Float, fy: Float, viewport: Float, t: Float): List<MorphPiece> {
        val fromAnchor = from.index(anchor) ?: return emptyList()
        val toAnchor = to.index(anchor) ?: return emptyList()
        val fromOffset = offset(from, fromAnchor, pivotY, fy, viewport)
        val toOffset = offset(to, toAnchor, pivotY, fy, viewport)
        val margin = viewport / 4f
        val keys = LinkedHashSet<String>()
        for (i in from.between(-fromOffset - margin, -fromOffset + viewport + margin)) keys.add(from.keys[i])
        for (i in to.between(-toOffset - margin, -toOffset + viewport + margin)) keys.add(to.keys[i])
        val pieces = ArrayList<MorphPiece>(keys.size)
        for (key in keys) {
            val a = from.index(key)
            val b = to.index(key)
            val boxA = a?.let { from.box(it).shifted(fromOffset) }
            val boxB = b?.let { to.box(it).shifted(toOffset) }
            val piece = when {
                a != null && boxA != null && boxB != null -> MorphPiece(a, true, lerp(boxA, boxB, t), 1f)
                a != null && boxA != null -> MorphPiece(a, true, boxA, 1f - t)
                b != null && boxB != null -> MorphPiece(b, false, boxB, t)
                else -> continue
            }
            if (piece.alpha > 0.01f && piece.box.bottom >= 0f && piece.box.top <= viewport) pieces.add(piece)
        }
        return pieces
    }

    private fun Box.shifted(dy: Float) = Box(left, top + dy, right, bottom + dy)
}
