package sh.hnet.gallery.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import kotlin.math.roundToInt
import sh.hnet.gallery.model.Mark
import sh.hnet.gallery.model.MarkColors
import sh.hnet.gallery.model.Markup
import sh.hnet.gallery.model.PxSize
import sh.hnet.gallery.model.Stroke
import sh.hnet.gallery.model.TextMark

/**
 * Draw's marks painted (S9a, LOGIKA §20.3–§20.5): the same code for the editor's preview and the saved result, so what
 * is seen is what is saved. The marks are in the upright photo's pixels; [map] takes those to the canvas's.
 */
object MarkupPainter {
    private val typeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /** A text's widest line over the letters' height in the font it is drawn in (`TextMark.aspect`). */
    fun aspectOf(text: String): Double {
        val paint = textPaint(100f)
        return (text.split('\n').maxOf { paint.measureText(it.ifEmpty { " " }) } / 100f).toDouble()
    }

    /** The marks in their inks. */
    fun draw(canvas: Canvas, marks: List<Mark>, upright: PxSize, map: Matrix) {
        for (mark in marks) canvas.withMatrix(map) {
            when (mark) {
                is Stroke -> drawPath(pathOf(mark, upright), strokePaint(mark, upright).apply {
                    color = mark.color
                    if (mark.highlighter) alpha = (MarkColors.HIGHLIGHTER_ALPHA * 255).roundToInt()
                })
                is TextMark -> drawText(this, mark, upright, textPaint(mark.heightPx(upright).toFloat()).apply { color = mark.color })
            }
        }
    }

    /**
     * The marks into an Ultra HDR photo's gain map at [neutral] – "no boost" (D36), one value per channel: ink is plain
     * ink, not lit with the sky around it. An RGB map takes each channel's own value (they can differ – review of S9b;
     * the highlighter part-way); a one-channel map ([alphaOnly]) takes the first as its value – the highlighter through
     * two passes: the map's own value down to 60 %, then 40 % of the neutral one added.
     */
    fun drawNeutral(canvas: Canvas, marks: List<Mark>, upright: PxSize, map: Matrix, neutral: FloatArray, alphaOnly: Boolean) {
        fun byte(c: Int) = (neutral[c.coerceAtMost(neutral.lastIndex)].coerceIn(0f, 1f) * 255).roundToInt()
        val value = byte(0)
        val part = MarkColors.HIGHLIGHTER_ALPHA
        for (mark in marks) canvas.withMatrix(map) {
            val highlighter = mark is Stroke && mark.highlighter
            fun paint(base: Paint): List<Paint> = when {
                !alphaOnly -> listOf(base.apply {
                    color = Color.rgb(byte(0), byte(1), byte(2))
                    if (highlighter) alpha = (part * 255).roundToInt()
                })
                !highlighter -> listOf(base.apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                    color = Color.argb(value, 0, 0, 0)
                })
                else -> listOf(
                    Paint(base).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                        color = Color.argb((part * 255).roundToInt(), 0, 0, 0)
                    },
                    Paint(base).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        color = Color.argb((part * value).roundToInt(), 0, 0, 0)
                    },
                )
            }
            when (mark) {
                is Stroke -> {
                    val path = pathOf(mark, upright)
                    paint(strokePaint(mark, upright)).forEach { drawPath(path, it) }
                }
                is TextMark -> paint(textPaint(mark.heightPx(upright).toFloat())).forEach { drawText(this, mark, upright, it) }
            }
        }
    }

    /** A stroke smoothed: through the middles of its points by quadratic curves, its ends as drawn. */
    private fun pathOf(stroke: Stroke, upright: PxSize): Path {
        val points = stroke.points.map { Markup.toPx(it, upright) }
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        if (points.size == 1) {
            // A dot: a line of no length, drawn by the round cap
            path.lineTo(points[0].x.toFloat(), points[0].y.toFloat())
            return path
        }
        for (i in 1 until points.lastIndex) {
            val p = points[i]
            val next = points[i + 1]
            path.quadTo(p.x.toFloat(), p.y.toFloat(), ((p.x + next.x) / 2).toFloat(), ((p.y + next.y) / 2).toFloat())
        }
        path.lineTo(points.last().x.toFloat(), points.last().y.toFloat())
        return path
    }

    private fun strokePaint(stroke: Stroke, upright: PxSize) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke.widthPx(upright).toFloat()
        // The highlighter's tip is flat, the pen's round
        strokeCap = if (stroke.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@MarkupPainter.typeface
        textSize = size
        textAlign = Paint.Align.LEFT
    }

    private fun drawText(canvas: Canvas, text: TextMark, upright: PxSize, paint: Paint) {
        val center = Markup.toPx(text.center, upright)
        val h = text.heightPx(upright)
        val line = h * TextMark.LINE
        val left = center.x - h * text.aspect / 2
        val top = center.y - line * text.lines / 2
        // Turned about the block's middle (and mirrored there when written on a mirrored photo, so it read right); each
        // line left-aligned, its letters' middle half a line into its own
        canvas.withRotation(text.rotation.toFloat(), center.x.toFloat(), center.y.toFloat()) {
            withScale(if (text.flipped) -1f else 1f, 1f, center.x.toFloat(), center.y.toFloat()) {
                val metrics = paint.fontMetrics
                text.text.split('\n').forEachIndexed { i, row ->
                    val middle = top + line / 2 + i * line
                    drawText(row, left.toFloat(), (middle - (metrics.ascent + metrics.descent) / 2).toFloat(), paint)
                }
            }
        }
    }

    /** Whether a bitmap's map is one channel (the Ultra HDR gain map of most phones). */
    fun alphaOnly(bitmap: Bitmap): Boolean = bitmap.config == Bitmap.Config.ALPHA_8
}
