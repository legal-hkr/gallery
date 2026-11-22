package sh.hnet.gallery.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A plane's affine map, as Android's `Matrix` holds it: x' = sx·x + kx·y + tx, y' = ky·x + sy·y + ty. Pure, so the
 * editor's geometry is tested on the JVM; the UI and the saver hand its values to a `Matrix`.
 */
data class Affine(val sx: Double, val kx: Double, val tx: Double, val ky: Double, val sy: Double, val ty: Double) {
    fun map(p: Pt): Pt = Pt(sx * p.x + kx * p.y + tx, ky * p.x + sy * p.y + ty)

    /** This map, then [next]. */
    fun then(next: Affine): Affine = Affine(
        next.sx * sx + next.kx * ky, next.sx * kx + next.kx * sy, next.sx * tx + next.kx * ty + next.tx,
        next.ky * sx + next.sy * ky, next.ky * kx + next.sy * sy, next.ky * tx + next.sy * ty + next.ty,
    )

    fun inverse(): Affine {
        val det = sx * sy - kx * ky
        val isx = sy / det
        val ikx = -kx / det
        val iky = -ky / det
        val isy = sx / det
        return Affine(isx, ikx, -(isx * tx + ikx * ty), iky, isy, -(iky * tx + isy * ty))
    }

    /** The values in `Matrix.setValues` order. */
    fun values(): FloatArray = floatArrayOf(sx.toFloat(), kx.toFloat(), tx.toFloat(), ky.toFloat(), sy.toFloat(), ty.toFloat(), 0f, 0f, 1f)

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, x, 0.0, 1.0, y)

        fun scale(s: Double) = Affine(s, 0.0, 0.0, 0.0, s, 0.0)

        /** A turn by [degrees] clockwise on the screen (y down) about [c]. */
        fun rotate(degrees: Double, c: Pt): Affine {
            val r = Math.toRadians(degrees)
            val cs = cos(r)
            val sn = sin(r)
            return translate(-c.x, -c.y).then(Affine(cs, -sn, 0.0, sn, cs, 0.0)).then(translate(c.x, c.y))
        }
    }
}

/** A point in pixels (the editor's maps are in Double: a 50 MP photo's pixels need it). */
data class Pt(val x: Double, val y: Double)

/** A size in pixels. */
data class PxSize(val width: Double, val height: Double) {
    constructor(width: Int, height: Int) : this(width.toDouble(), height.toDouble())
}

/** A crop as shares (0–1) of the turned photo's width and height (SPEC §15.2). */
data class Crop(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    companion object {
        val WHOLE = Crop(0.0, 0.0, 1.0, 1.0)
    }
}

/** The crop's ratios (SPEC §15.2), a width over a height; [ORIGINAL] is the turned photo's own. */
enum class CropRatio(val wide: Int, val high: Int) {
    FREE(0, 0), ORIGINAL(0, 0), SQUARE(1, 1), FOUR_THREE(4, 3), THREE_TWO(3, 2), SIXTEEN_NINE(16, 9),
}

/**
 * What an edit does to a photo's geometry (LOGIKA §18.2): quarter turns counter-clockwise, then a mirror across, then
 * a straightening turn, then the crop – a list of changes, not pixels. [portrait] swaps a ratio's sides.
 */
data class EditState(
    val quarters: Int = 0,
    val flipped: Boolean = false,
    val angle: Double = 0.0,
    val crop: Crop = Crop.WHOLE,
    val ratio: CropRatio = CropRatio.FREE,
    val portrait: Boolean = false,
    /**
     * The crop as set – the whole photo, a ratio's, a dragged one – before straightening shrank it: at every angle the
     * crop is the largest of its shape about its middle that fits, so it grows back as the angle comes back (owner's
     * test of S7: shrunk step by step from the last, it never grew again). Null: the crop itself.
     */
    val wanted: Crop? = null,
    /** Draw's marks (S9a), in the upright photo's shares: turns and crops carry them, Undo takes them as any step. */
    val marks: List<Mark> = emptyList(),
) {
    /** Anything to save: the crop as set is bookkeeping, and a ratio alone that left the crop as it was changes nothing. */
    val changed: Boolean get() = copy(wanted = null, ratio = CropRatio.FREE, portrait = false) != EditState()

    /** The crop the angle is fitted from. */
    val base: Crop get() = wanted ?: crop
}

/** The editor's geometry (LOGIKA §18.3–§18.4): every map from the stored file to the result goes through here. */
object EditGeometry {
    const val MAX_ANGLE = 45.0

    /** The smallest crop side, a share of the turned photo's shorter side. */
    private const val LEAST = 0.05

    /** The stored photo's size upright (its EXIF orientation applied). */
    fun upright(stored: PxSize, exif: Orientation): PxSize = if (exif.degrees % 180 == 0) stored else PxSize(stored.height, stored.width)

    /** The photo upright (its EXIF orientation applied) and then turned by the edit's quarters. */
    fun turned(upright: PxSize, state: EditState): PxSize = if (state.quarters % 2 == 0) upright else PxSize(upright.height, upright.width)

    /** A quarter turn counter-clockwise: the crop turns with the photo; after a mirror the turn counts the other way. */
    fun rotate(state: EditState): EditState {
        fun turn(c: Crop) = Crop(c.top, 1 - c.right, c.bottom, 1 - c.left)
        val quarters = if (state.flipped) (state.quarters + 3) % 4 else (state.quarters + 1) % 4
        return state.copy(quarters = quarters, crop = turn(state.crop), wanted = state.wanted?.let(::turn), portrait = if (state.ratio.wide != state.ratio.high) !state.portrait else state.portrait)
    }

    /** A mirror across: the crop mirrors with the photo, the straightening turns the other way. */
    fun flip(state: EditState): EditState {
        fun mirror(c: Crop) = Crop(1 - c.right, c.top, 1 - c.left, c.bottom)
        return state.copy(flipped = !state.flipped, angle = -state.angle, crop = mirror(state.crop), wanted = state.wanted?.let(::mirror))
    }

    /** The map from the stored file's pixels to the result's (its EXIF orientation, the edit, the crop). */
    fun matrix(stored: PxSize, exif: Orientation, state: EditState): Affine {
        val upright = upright(stored, exif)
        // Stored → upright: the turn clockwise, then the mirror (as Orientation.toSource undoes them)
        var m = when (exif.degrees) {
            90 -> Affine(0.0, -1.0, stored.height, 1.0, 0.0, 0.0)
            180 -> Affine(-1.0, 0.0, stored.width, 0.0, -1.0, stored.height)
            270 -> Affine(0.0, 1.0, 0.0, -1.0, 0.0, stored.width)
            else -> Affine.IDENTITY
        }
        if (exif.mirrored) m = m.then(Affine(-1.0, 0.0, upright.width, 0.0, 1.0, 0.0))
        // The quarters counter-clockwise, one at a time on a frame that turns with them
        var w = upright.width
        var h = upright.height
        repeat(state.quarters % 4) {
            m = m.then(Affine(0.0, 1.0, 0.0, -1.0, 0.0, w))
            val t = w
            w = h
            h = t
        }
        if (state.flipped) m = m.then(Affine(-1.0, 0.0, w, 0.0, 1.0, 0.0))
        m = m.then(Affine.rotate(state.angle, Pt(w / 2, h / 2)))
        return m.then(Affine.translate(-state.crop.left * w, -state.crop.top * h))
    }

    /** The result's size in pixels. */
    fun output(upright: PxSize, state: EditState): PxSize {
        val t = turned(upright, state)
        return PxSize(max(1.0, state.crop.width * t.width).roundToInt(), max(1.0, state.crop.height * t.height).roundToInt())
    }

    /** Whether the crop lies inside the straightened photo (every corner, turned back, within the photo). */
    fun inside(crop: Crop, angle: Double, turned: PxSize): Boolean {
        if (crop.left < -1e-9 || crop.top < -1e-9 || crop.right > 1 + 1e-9 || crop.bottom > 1 + 1e-9) return false
        val w = turned.width
        val h = turned.height
        val back = Affine.rotate(-angle, Pt(w / 2, h / 2))
        for (x in doubleArrayOf(crop.left, crop.right)) for (y in doubleArrayOf(crop.top, crop.bottom)) {
            val p = back.map(Pt(x * w, y * h))
            if (p.x < -1e-6 * w || p.y < -1e-6 * h || p.x > w * (1 + 1e-6) || p.y > h * (1 + 1e-6)) return false
        }
        return true
    }

    /** The crop shrunk about its middle (then about the photo's, if its middle lies outside) until it fits (SPEC §15.2). */
    fun fit(crop: Crop, angle: Double, turned: PxSize): Crop {
        if (inside(crop, angle, turned)) return crop
        fun scaled(c: Crop, cx: Double, cy: Double, s: Double) = Crop(cx - c.width * s / 2, cy - c.height * s / 2, cx + c.width * s / 2, cy + c.height * s / 2)
        for ((cx, cy) in listOf(crop.centerX to crop.centerY, 0.5 to 0.5)) {
            var low = 0.0
            var high = 1.0
            repeat(40) {
                val mid = (low + high) / 2
                if (inside(scaled(crop, cx, cy, mid), angle, turned)) low = mid else high = mid
            }
            if (low > LEAST) return scaled(crop, cx, cy, low)
        }
        return scaled(crop, 0.5, 0.5, 0.0)
    }

    /** The ratio as a width over a height in pixels, or null for a free crop. */
    fun ratioOf(state: EditState, turned: PxSize): Double? {
        val r = when (state.ratio) {
            CropRatio.FREE -> return null
            // The photo's own shape as it lies, the other way only when swapped (review before v0.1.0: a portrait photo's
            // Original came out landscape)
            CropRatio.ORIGINAL -> return (turned.width / turned.height).let { if (state.portrait) 1 / it else it }
            else -> state.ratio.wide.toDouble() / state.ratio.high
        }
        return if (state.portrait == (r > 1)) 1 / r else r
    }

    /** A ratio chosen: the largest crop of it about the present crop's middle, inside the photo. */
    fun withRatio(state: EditState, ratio: CropRatio, portrait: Boolean, turned: PxSize): EditState {
        val next = state.copy(ratio = ratio, portrait = portrait)
        val r = ratioOf(next, turned) ?: return next
        val cx = state.crop.centerX * turned.width
        val cy = state.crop.centerY * turned.height
        var halfW = min(cx, turned.width - cx)
        var halfH = min(cy, turned.height - cy)
        if (halfW / halfH > r) halfW = halfH * r else halfH = halfW / r
        val crop = Crop((cx - halfW) / turned.width, (cy - halfH) / turned.height, (cx + halfW) / turned.width, (cy + halfH) / turned.height)
        return next.copy(crop = fit(crop, state.angle, turned), wanted = crop)
    }

    /**
     * A new straightening angle: the crop as set ([EditState.base]), shrunk to the largest of its shape that fits the
     * straightened photo – growing back as the angle comes back.
     */
    fun withAngle(state: EditState, angle: Double, turned: PxSize): EditState {
        val a = angle.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        val base = state.base
        return state.copy(angle = a, crop = fit(base, a, turned), wanted = base)
    }

    /**
     * A corner dragged to [x], [y] (shares of the turned photo), the opposite one held. Free: each axis on its own, so
     * at an edge the corner stops there and goes on along it; with a ratio the corner moves on the ratio's diagonal, as
     * far as stays inside. [corner]: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
     */
    fun drag(state: EditState, corner: Int, x: Double, y: Double, turned: PxSize): EditState {
        val c = state.crop
        if (!inside(c, state.angle, turned)) return state.copy(crop = fit(c, state.angle, turned))
        val (ax, ay) = when (corner) {
            0 -> c.right to c.bottom
            1 -> c.left to c.bottom
            2 -> c.left to c.top
            else -> c.right to c.top
        }
        val signX = if (corner == 1 || corner == 2) 1 else -1
        val signY = if (corner >= 2) 1 else -1
        // The smallest side, in shares of each axis
        val leastPx = LEAST * min(turned.width, turned.height)
        val leastX = leastPx / turned.width
        val leastY = leastPx / turned.height
        fun rect(px: Double, py: Double) = Crop(min(ax, px), min(ay, py), max(ax, px), max(ay, py))
        // The corner never crosses to the anchor's other side, nor nearer it than the smallest side
        val tx = if (signX > 0) max(x, ax + leastX) else min(x, ax - leastX)
        val ty = if (signY > 0) max(y, ay + leastY) else min(y, ay - leastY)
        val ratio = ratioOf(state, turned)
        if (ratio == null) {
            val px = if (signX > 0) c.right else c.left
            val py = if (signY > 0) c.bottom else c.top
            val (bx, by) = slide(px, py, tx, ty, turned) { a, b -> inside(rect(a, b), state.angle, turned) }
            return state.copy(crop = rect(bx, by), wanted = null)
        }
        // On the ratio's diagonal: the width in pixels decides, the height follows
        var w = abs(tx - ax) * turned.width
        var h = abs(ty - ay) * turned.height
        if (w / h > ratio) h = w / ratio else w = h * ratio
        fun sized(width: Double) = rect(ax + signX * width / turned.width, ay + signY * width / ratio / turned.height)
        if (inside(sized(w), state.angle, turned)) return state.copy(crop = sized(w), wanted = null)
        // Smaller always fits (the anchor's corner is inside, the region is convex): the largest that does
        var low = leastPx
        var high = w
        repeat(40) {
            val mid = (low + high) / 2
            if (inside(sized(mid), state.angle, turned)) low = mid else high = mid
        }
        return state.copy(crop = sized(low), wanted = null)
    }

    /**
     * The crop moved by [dx], [dy] (shares): each axis as far as it stays inside, so at an edge it stops in that axis
     * and goes on along it (owner's test of S7: limited along the whole move, a crop of the full width didn't move up
     * or down at all, slowed past an edge, and rode an arc when moved sideways).
     */
    fun move(state: EditState, dx: Double, dy: Double, turned: PxSize): EditState {
        val c = state.crop
        if (!inside(c, state.angle, turned)) return state.copy(crop = fit(c, state.angle, turned))
        fun at(x: Double, y: Double) = Crop(c.left + x, c.top + y, c.right + x, c.bottom + y)
        val (x, y) = slide(0.0, 0.0, dx, dy, turned) { a, b -> inside(at(a, b), state.angle, turned) }
        return state.copy(crop = at(x, y), wanted = null)
    }

    /**
     * Two fingers on the photo (owner, 20 Nov 2026, as the Photos editor of iOS): the photo zoomed by [factor] (above 1:
     * closer) about ([fx], [fy]) – shares of the turned photo, the point between the fingers – and moved by the fingers'
     * [dx], [dy] (shares), under a frame that stays where it is. So the crop scales the other way about that point and
     * moves against the fingers, keeping its shape: as close as the smallest crop, as far as the photo reaches – pushed
     * back in where growing would take it past an edge – and never past the straightened photo's edge.
     */
    fun zoom(state: EditState, fx: Double, fy: Double, factor: Double, dx: Double, dy: Double, turned: PxSize): EditState {
        val c = state.crop
        if (!inside(c, state.angle, turned)) return state.copy(crop = fit(c, state.angle, turned))
        fun about(f: Double) = Crop(fx + (c.left - fx) * f, fy + (c.top - fy) * f, fx + (c.right - fx) * f, fy + (c.bottom - fy) * f)
        // Shifted back into the photo's square where it would stick out; wider than the photo it can't be
        fun pushedIn(k: Crop): Crop {
            val x = when {
                k.width > 1 -> 0.0
                k.left < 0 -> -k.left
                k.right > 1 -> 1 - k.right
                else -> 0.0
            }
            val y = when {
                k.height > 1 -> 0.0
                k.top < 0 -> -k.top
                k.bottom > 1 -> 1 - k.bottom
                else -> 0.0
            }
            return Crop(k.left + x, k.top + y, k.right + x, k.bottom + y)
        }
        val leastPx = LEAST * min(turned.width, turned.height)
        val smallest = leastPx / min(c.width * turned.width, c.height * turned.height)
        val wanted = max(1 / factor, min(smallest, 1.0))
        var crop = pushedIn(about(wanted))
        if (!inside(crop, state.angle, turned)) {
            // As far towards the wanted size as fits (the present crop does)
            var low = 0.0
            var high = 1.0
            repeat(40) {
                val mid = (low + high) / 2
                if (inside(pushedIn(about(1 + (wanted - 1) * mid)), state.angle, turned)) low = mid else high = mid
            }
            crop = pushedIn(about(1 + (wanted - 1) * low))
        }
        // The search's last hair past an edge cut off: the photo's own edge exactly
        crop = Crop(crop.left.coerceIn(0.0, 1.0), crop.top.coerceIn(0.0, 1.0), crop.right.coerceIn(0.0, 1.0), crop.bottom.coerceIn(0.0, 1.0))
        val zoomed = state.copy(crop = crop, wanted = null)
        return if (dx == 0.0 && dy == 0.0) zoomed else move(zoomed, -dx, -dy, turned)
    }

    /**
     * From ([fx], [fy]) – which fits – towards ([tx], [ty]), each axis as far as it fits: x then y, and y then x (a
     * straightened photo's edge is slanted, so the order matters), whichever ends nearer the target. What fits is
     * convex, so along an axis it is one stretch from the start.
     */
    private fun slide(fx: Double, fy: Double, tx: Double, ty: Double, turned: PxSize, fits: (Double, Double) -> Boolean): Pair<Double, Double> {
        fun along(x0: Double, y0: Double, x1: Double, y1: Double): Pair<Double, Double> {
            if (fits(x1, y1)) return x1 to y1
            var low = 0.0
            var high = 1.0
            repeat(40) {
                val mid = (low + high) / 2
                if (fits(x0 + (x1 - x0) * mid, y0 + (y1 - y0) * mid)) low = mid else high = mid
            }
            return (x0 + (x1 - x0) * low) to (y0 + (y1 - y0) * low)
        }
        val xFirst = along(fx, fy, tx, fy).let { (x, y) -> along(x, y, x, ty) }
        val yFirst = along(fx, fy, fx, ty).let { (x, y) -> along(x, y, tx, y) }
        fun miss(p: Pair<Double, Double>) = (p.first - tx) * (p.first - tx) * turned.width * turned.width + (p.second - ty) * (p.second - ty) * turned.height * turned.height
        return if (miss(xFirst) <= miss(yFirst)) xFirst else yFirst
    }

    /** Snaps an angle near 0° to 0° (SPEC §15.2). */
    fun snap(angle: Double): Double = if (abs(angle) < 0.75) 0.0 else angle
}

/** Undo and redo within an editing session (SPEC §15.1): the states before and after, and the present one. */
class EditHistory(val present: EditState = EditState(), private val back: List<EditState> = emptyList(), private val forward: List<EditState> = emptyList()) {
    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = forward.isNotEmpty()

    /** A new state: the present one goes back, what was ahead is gone. */
    fun push(state: EditState): EditHistory = if (state == present) this else EditHistory(state, back + present, emptyList())

    fun undo(): EditHistory = if (back.isEmpty()) this else EditHistory(back.last(), back.dropLast(1), listOf(present) + forward)

    fun redo(): EditHistory = if (forward.isEmpty()) this else EditHistory(forward.first(), back + present, forward.drop(1))
}

/** The name of an edited copy (SPEC §15.6): the name, the suffix, a number when taken – "a-edytowane (2).jpg". */
fun editedName(name: String, suffix: String, taken: Collection<String>): String {
    val (base, extension) = FileNames.split(name)
    val used = taken.mapTo(HashSet()) { it.lowercase() }
    var n = 1
    while (true) {
        val candidate = if (n == 1) "$base$suffix$extension" else "$base$suffix ($n)$extension"
        if (candidate.lowercase() !in used) return candidate
        n++
    }
}
