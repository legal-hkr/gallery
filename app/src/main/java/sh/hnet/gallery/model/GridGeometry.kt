package sh.hnet.gallery.model

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

    companion object {
        /**
         * The layout of [entries] in [columns] columns across [width] pixels, [gap] between cells and
         * lines, headers [headerHeight] high.
         */
        fun of(entries: List<TimelineEntry>, columns: Int, width: Float, gap: Float, headerHeight: Float): GridGeometry {
            val cell = (width - gap * (columns - 1)) / columns
            val n = entries.size
            val tops = FloatArray(n)
            val lefts = FloatArray(n)
            val widths = FloatArray(n)
            val heights = FloatArray(n)
            var y = 0f
            var column = 0
            var lineOpen = false
            for ((i, entry) in entries.withIndex()) {
                if (entry is TimelineEntry.Tile) {
                    if (column == columns) {
                        y += cell + gap
                        column = 0
                    }
                    tops[i] = y
                    lefts[i] = column * (cell + gap)
                    widths[i] = cell
                    heights[i] = cell
                    column++
                    lineOpen = true
                } else {
                    // A header closes the tiles' line before it and takes a line of its own
                    if (lineOpen) y += cell + gap
                    tops[i] = y
                    lefts[i] = 0f
                    widths[i] = width
                    heights[i] = headerHeight
                    y += headerHeight + gap
                    column = 0
                    lineOpen = false
                }
            }
            val height = if (lineOpen) y + cell else (y - gap).coerceAtLeast(0f)
            return GridGeometry(entries.map { it.key }, tops, lefts, widths, heights, height, cell)
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
