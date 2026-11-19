package sh.hnet.gallery.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A size in pixels, as floats (pure Kotlin: the model has no Android or Compose types). */
data class Dim(val width: Float, val height: Float) {
    val empty: Boolean get() = width <= 0f || height <= 0f
}

/** A point or a shift in pixels. */
data class Vec(val x: Float, val y: Float) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(f: Float) = Vec(x * f, y * f)

    companion object {
        val ZERO = Vec(0f, 0f)
    }
}

/** A rect in pixels: left, top, right, bottom. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val empty: Boolean get() = width <= 0f || height <= 0f

    fun intersect(o: Box) = Box(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))
}

/**
 * The viewer's zoom (LOGIKA §13.2) as numbers: the image is drawn fitted and centred in the
 * container, then scaled by `scale` about the container's centre and moved by `offset`. Scale 1 is
 * fitted; the image never leaves the screen; a turn of the phone keeps the point at the centre and
 * the size of an image pixel on screen.
 */
object ZoomMath {
    /** Screen pixels per image pixel when fitted. */
    fun fitScale(container: Dim, image: Dim): Float =
        if (container.empty || image.empty) 0f else min(container.width / image.width, container.height / image.height)

    fun fitted(container: Dim, image: Dim): Dim {
        val fit = fitScale(container, image)
        return Dim(image.width * fit, image.height * fit)
    }

    /** The largest scale (SPEC §6.1): one image pixel per screen pixel, but at least 3 × fitted. */
    fun maxScale(container: Dim, image: Dim): Float {
        val fit = fitScale(container, image)
        return if (fit <= 0f) MIN_MAX_SCALE else max(1f / fit, MIN_MAX_SCALE)
    }

    /**
     * The double tap's scale (owner, 19 Nov 2026, over SPEC §6.1's 2.5×): the image fills the screen;
     * an image of the screen's own shape, which fills it fitted already, gets [DOUBLE_TAP_SCALE].
     */
    fun fillScale(container: Dim, image: Dim): Float {
        val fitted = fitted(container, image)
        if (fitted.empty) return 1f
        val fill = max(container.width / fitted.width, container.height / fitted.height)
        return (if (fill < 1.1f) DOUBLE_TAP_SCALE else fill).coerceAtMost(maxScale(container, image))
    }

    /** The offset kept so the image stays on screen: it moves only as far as it is larger than the container. */
    fun constrain(offset: Vec, scale: Float, container: Dim, image: Dim): Vec {
        val fitted = fitted(container, image)
        if (fitted.empty) return Vec.ZERO
        val excessX = max(fitted.width * scale - container.width, 0f) / 2f
        val excessY = max(fitted.height * scale - container.height, 0f) / 2f
        return Vec(offset.x.coerceIn(-excessX, excessX), offset.y.coerceIn(-excessY, excessY))
    }

    /** The offset that keeps the image point under [focus] (container coordinates) in place when the scale goes from [scale] to [newScale]. */
    fun offsetFor(offset: Vec, scale: Float, newScale: Float, focus: Vec, container: Dim): Vec {
        val change = newScale / scale
        val fromCenter = focus - Vec(container.width / 2f, container.height / 2f)
        return offset * change + fromCenter * (1f - change)
    }

    /**
     * The zoom after the container changed size (the phone turned, SPEC §6.8): fitted stays fitted;
     * zoomed keeps the image point at the centre at the centre and the size of an image pixel on
     * screen, within the new limits. The offset in pixels comes out the same, before the new bounds.
     */
    fun resized(scale: Float, offset: Vec, old: Dim, new: Dim, image: Dim): Pair<Float, Vec> {
        if (scale <= 1f || old.empty || new.empty || image.empty) return 1f to Vec.ZERO
        val oldFit = fitScale(old, image)
        val newFit = fitScale(new, image)
        val newScale = (scale * oldFit / newFit).coerceIn(1f, maxScale(new, image))
        if (newScale <= 1f) return 1f to Vec.ZERO
        return newScale to constrain(offset, newScale, new, image)
    }

    /**
     * Whether the zoomed image's edge is reached in the direction of a sideways drag ([dx] > 0: to the
     * right, showing its left part): the rest of that drag moves the pager (the owner, 19 Nov 2026).
     */
    fun atEdge(offset: Vec, scale: Float, container: Dim, image: Dim, dx: Float): Boolean {
        val fitted = fitted(container, image)
        val excessX = max(fitted.width * scale - container.width, 0f) / 2f
        if (excessX <= 0f) return true
        return if (dx > 0f) offset.x >= excessX - 0.5f else offset.x <= -excessX + 0.5f
    }

    /** The part of the image on screen, in image pixels, clipped to the image. */
    fun visibleRegion(scale: Float, offset: Vec, container: Dim, image: Dim): Box {
        val fit = fitScale(container, image)
        if (fit <= 0f || scale <= 0f) return Box(0f, 0f, 0f, 0f)
        val center = Vec(container.width / 2f, container.height / 2f)
        fun toImage(q: Vec): Vec {
            val fromCenter = (q - center - offset) * (1f / scale)
            return Vec(fromCenter.x / fit + image.width / 2f, fromCenter.y / fit + image.height / 2f)
        }
        val topLeft = toImage(Vec(0f, 0f))
        val bottomRight = toImage(Vec(container.width, container.height))
        return Box(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y).intersect(Box(0f, 0f, image.width, image.height))
    }

    /** The coarsest power-of-two sampling that still gives at least one decoded pixel per screen pixel. */
    fun sampleFor(scale: Float, fit: Float): Int {
        val screenPerImage = fit * scale
        if (screenPerImage <= 0f) return 1
        var sample = 1
        while (sample * 2 * screenPerImage <= 1f) sample *= 2
        return sample
    }

    const val MIN_MAX_SCALE = 3f
    const val DOUBLE_TAP_SCALE = 2f
}

/** One tile of the zoomed image (LOGIKA §13.2): its column and row at a sampling, and the part of the displayed image it covers. */
data class Tile(val sample: Int, val col: Int, val row: Int, val region: Box)

object Tiles {
    /** Size of a decoded tile in pixels. */
    const val SIZE = 512

    /** The tiles covering [visible] (image pixels) at [sample], each [SIZE] decoded pixels wide, clipped to the image. */
    fun covering(visible: Box, sample: Int, image: Dim): List<Tile> {
        if (visible.empty) return emptyList()
        val span = (SIZE * sample).toFloat()
        val first = floor(visible.left / span).toInt().coerceAtLeast(0)
        val last = (ceil(visible.right / span).toInt() - 1).coerceAtLeast(first)
        val top = floor(visible.top / span).toInt().coerceAtLeast(0)
        val bottom = (ceil(visible.bottom / span).toInt() - 1).coerceAtLeast(top)
        val tiles = ArrayList<Tile>()
        for (row in top..bottom) for (col in first..last) {
            val region = Box(col * span, row * span, min((col + 1) * span, image.width), min((row + 1) * span, image.height))
            if (!region.empty) tiles.add(Tile(sample, col, row, region))
        }
        return tiles
    }
}

/**
 * The EXIF orientation (1–8) as a turn clockwise and a mirror after it, as the platform applies it
 * when decoding a whole image; a region decoder doesn't, so the viewer maps its tiles itself.
 */
data class Orientation(val degrees: Int, val mirrored: Boolean) {
    /** The displayed image's size for a source (as stored) of [source]. */
    fun displayed(source: Dim): Dim = if (degrees % 180 == 0) source else Dim(source.height, source.width)

    /** A rect of the displayed image as the rect of the stored image it comes from. */
    fun toSource(rect: Box, source: Dim): Box {
        val a = toSource(Vec(rect.left, rect.top), source)
        val b = toSource(Vec(rect.right, rect.bottom), source)
        return Box(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    /** A point of the displayed image as the point of the stored image: undo the mirror, then the turn. */
    fun toSource(p: Vec, source: Dim): Vec {
        val shown = displayed(source)
        val x = if (mirrored) shown.width - p.x else p.x
        val y = p.y
        return when (degrees) {
            90 -> Vec(y, source.height - x)
            180 -> Vec(source.width - x, source.height - y)
            270 -> Vec(source.width - y, x)
            else -> Vec(x, y)
        }
    }

    companion object {
        val NORMAL = Orientation(0, false)

        fun of(exif: Int): Orientation = when (exif) {
            2 -> Orientation(0, true)
            3 -> Orientation(180, false)
            4 -> Orientation(180, true)
            5 -> Orientation(90, true)
            6 -> Orientation(90, false)
            7 -> Orientation(270, true)
            8 -> Orientation(270, false)
            else -> NORMAL
        }
    }
}
