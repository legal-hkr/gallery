package sh.hnet.gallery.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Draw's tools (SPEC §15.5): the pen, the highlighter (translucent ink), text and the eraser of whole marks. */
enum class MarkTool { PEN, HIGHLIGHTER, TEXT, ERASER }

/** A mark's thickness as a share of the photo's shorter side, so it looks alike on 2 MP and 50 MP (LOGIKA §20.3). */
enum class MarkSize(val share: Double) {
    THIN(0.004),
    MEDIUM(0.008),
    THICK(0.016),
}

/** The inks: part of the photo, not of the interface, so fixed values (SPEC §15.5) – black, white, red, yellow, green, blue. */
object MarkColors {
    val ALL: List<Int> = listOf(0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5).map { it.toInt() }

    /** The highlighter's ink: the colour, about 40 % opaque, one opacity over the whole stroke. */
    const val HIGHLIGHTER_ALPHA = 0.4f
}

/** A mark on the photo, in the coordinates of the photo standing upright (shares 0–1), so turns and crops carry it (LOGIKA §20.3). */
sealed interface Mark {
    val id: Long
}

/** A line of ink: its points, smoothed when drawn. */
data class Stroke(override val id: Long, val highlighter: Boolean, val color: Int, val size: MarkSize, val points: List<Pt>) : Mark {
    /** Its width in the upright photo's pixels; the highlighter's is three times the pen's. */
    fun widthPx(upright: PxSize): Double = size.share * min(upright.width, upright.height) * (if (highlighter) 3 else 1)
}

/**
 * Text typed on the photo (SPEC §15.5, D33): its lines, ink, the letters' height as a share of the shorter side, the
 * middle of its block (shares) – what it turns and grows about (owner's test of S9a) –, its turn in degrees, and its
 * widest line's width over the letters' height (measured with the font when typed). Lines are left-aligned, [LINE]
 * letter heights apart. Typing starts where the tap was: that is the first line's left end ([Markup.textAnchor]).
 */
data class TextMark(override val id: Long, val text: String, val color: Int, val height: Double, val center: Pt, val rotation: Double, val aspect: Double) : Mark {
    fun heightPx(upright: PxSize): Double = height * min(upright.width, upright.height)

    val lines: Int get() = text.count { it == '\n' } + 1

    companion object {
        /** From one line's middle to the next's, in letter heights. */
        const val LINE = 1.2
    }
}

object Markup {
    /** A point in shares of the upright photo to its pixels, and back. */
    fun toPx(p: Pt, upright: PxSize) = Pt(p.x * upright.width, p.y * upright.height)

    fun toShare(p: Pt, upright: PxSize) = Pt(p.x / upright.width, p.y / upright.height)

    /** A finger's points thinned: a point closer than [least] to the last one kept adds nothing but weight. */
    fun thin(points: List<Pt>, least: Double): List<Pt> {
        if (points.size < 3) return points
        val kept = ArrayList<Pt>(points.size)
        kept.add(points.first())
        for (i in 1 until points.lastIndex) {
            val last = kept.last()
            if (hypot(points[i].x - last.x, points[i].y - last.y) >= least) kept.add(points[i])
        }
        kept.add(points.last())
        return kept
    }

    /** Whether a finger at [at] (upright pixels) with [radius] touches [mark] – the eraser takes whole marks (SPEC §15.5). */
    fun hit(mark: Mark, at: Pt, radius: Double, upright: PxSize): Boolean = when (mark) {
        is Stroke -> {
            val reach = mark.widthPx(upright) / 2 + radius
            val points = mark.points.map { toPx(it, upright) }
            if (points.size == 1) hypot(points[0].x - at.x, points[0].y - at.y) <= reach
            else points.zipWithNext().any { (a, b) -> distanceToSegment(at, a, b) <= reach }
        }
        is TextMark -> {
            val c = toPx(mark.center, upright)
            val h = mark.heightPx(upright)
            val w = h * mark.aspect
            val tall = h * TextMark.LINE * mark.lines
            // The point turned back into the text's own frame
            val r = Math.toRadians(-mark.rotation)
            val dx = at.x - c.x
            val dy = at.y - c.y
            val x = dx * cos(r) - dy * sin(r)
            val y = dx * sin(r) + dy * cos(r)
            abs(x) <= w / 2 + radius && abs(y) <= tall / 2 + radius
        }
    }

    /** The marks the eraser leaves at [at]. */
    fun erase(marks: List<Mark>, at: Pt, radius: Double, upright: PxSize): List<Mark> = marks.filterNot { hit(it, at, radius, upright) }

    /** The topmost text under a finger, to edit or to move (the last drawn lies on top). */
    fun textAt(marks: List<Mark>, at: Pt, radius: Double, upright: PxSize): TextMark? =
        marks.asReversed().firstOrNull { it is TextMark && hit(it, at, radius, upright) } as? TextMark

    /**
     * Where a text's typing starts – its first line's left end at the letters' middle, upright pixels – from its block's
     * middle: the block's top left corner half a line in, turned with it. [textCenter] is the way back; the anchor stays
     * put while the text is typed, the middle moves with what is typed.
     */
    fun textAnchor(center: Pt, heightPx: Double, aspect: Double, lines: Int, rotation: Double): Pt {
        val line = heightPx * TextMark.LINE
        return turn(center, -heightPx * aspect / 2, -line * lines / 2 + line / 2, rotation)
    }

    fun textCenter(anchor: Pt, heightPx: Double, aspect: Double, lines: Int, rotation: Double): Pt {
        val line = heightPx * TextMark.LINE
        return turn(anchor, heightPx * aspect / 2, line * lines / 2 - line / 2, rotation)
    }

    /** [p] moved by ([dx], [dy]) of a frame turned [rotation] degrees. */
    private fun turn(p: Pt, dx: Double, dy: Double, rotation: Double): Pt {
        val r = Math.toRadians(rotation)
        return Pt(p.x + dx * cos(r) - dy * sin(r), p.y + dx * sin(r) + dy * cos(r))
    }

    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val length = vx * vx + vy * vy
        val t = if (length == 0.0) 0.0 else (((p.x - a.x) * vx + (p.y - a.y) * vy) / length).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + vx * t), p.y - (a.y + vy * t))
    }

    /**
     * The gain map's value meaning "no boost" (D36): ink drawn over an Ultra HDR photo is plain ink, not lit up with
     * the sky around it. A gain G is stored as ((log G − log min) / (log max − log min)) ^ gamma (Ultra HDR, Android's
     * `Gainmap`); for G = 1 that is (−log min / (log max − log min)) ^ gamma, 0 when the map never dims (min = 1).
     */
    fun neutralGain(ratioMin: Float, ratioMax: Float, gamma: Float): Float {
        val lo = ln(ratioMin.toDouble())
        val hi = ln(ratioMax.toDouble())
        if (hi <= lo) return 0f
        val share = ((0.0 - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return share.pow(gamma.toDouble()).toFloat()
    }
}
