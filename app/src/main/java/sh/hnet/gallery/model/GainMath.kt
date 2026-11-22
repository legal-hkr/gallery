package sh.hnet.gallery.model

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * An Ultra HDR gain map's parameters, per channel (Android's `Gainmap`): the boost's range, the encoding's gamma and the
 * offsets added to the SDR and HDR pictures.
 */
class GainParams(
    val ratioMin: DoubleArray,
    val ratioMax: DoubleArray,
    val gamma: DoubleArray,
    val epsilonSdr: DoubleArray,
    val epsilonHdr: DoubleArray,
) {
    /** The photo's headroom: its brightest boost, so the HDR picture's white. */
    val headroom: Double get() = max(1.0, ratioMax.max())
}

/**
 * Adjustments on an Ultra HDR photo (S10b, LOGIKA §21.6): the HDR picture made from the base and its gain map, adjusted
 * as the SDR one is but up to the photo's headroom, and the gain map made again so the adjusted base shows it. As the
 * platform applies a map at full HDR: HDR = (SDR + εsdr) · G − εhdr, G = exp(lerp(ln min, ln max, value^(1/γ))), in
 * the base's colour space made linear.
 */
object GainMath {
    /** The boost a map value (0–1) of channel [c] means. */
    fun decode(value: Double, c: Int, p: GainParams): Double {
        val share = value.coerceIn(0.0, 1.0).pow(1 / p.gamma[c])
        return exp(ln(p.ratioMin[c]) + share * (ln(p.ratioMax[c]) - ln(p.ratioMin[c])))
    }

    /** A boost to a map value (0–1) for the range [min]…[max] and [gamma]. */
    fun encode(boost: Double, min: Double, max: Double, gamma: Double): Double {
        val lo = ln(min)
        val hi = ln(max)
        if (hi <= lo) return 0.0
        return ((ln(boost) - lo) / (hi - lo)).coerceIn(0.0, 1.0).pow(gamma)
    }

    /**
     * One pixel's new boosts (per channel): [base] the base before the adjustments and [adjusted] after them, both
     * linear in the base's colour space; [gains] its boosts from the old map. [toSrgb] and [fromSrgb] (3×3, row-major)
     * take linear base colours to linear sRGB, where the adjustments are made, and back. [single]: one boost for all
     * channels (a one-channel map, or a black-and-white result – its lights must not tint).
     */
    fun newGains(base: DoubleArray, adjusted: DoubleArray, gains: DoubleArray, p: GainParams, adjust: Adjustments, toSrgb: DoubleArray, fromSrgb: DoubleArray, single: Boolean): DoubleArray {
        val hdr = DoubleArray(3) { c -> (base[c] + p.epsilonSdr[c]) * gains[c] - p.epsilonHdr[c] }
        val shown = mul(fromSrgb, Tone.apply(mul(toSrgb, hdr), adjust, p.headroom))
        val out = DoubleArray(3) { c -> ((shown[c] + p.epsilonHdr[c]) / max(adjusted[c] + p.epsilonSdr[c], 1e-6)).coerceIn(MIN_BOOST, MAX_BOOST) }
        if (!single) return out
        // The luminance with sRGB's weights, so in sRGB's primaries (the colours here are the base's – P3 on many phones)
        val hdrY = luminance(mul(toSrgb, DoubleArray(3) { c -> shown[c] + p.epsilonHdr[c] }))
        val sdrY = luminance(mul(toSrgb, DoubleArray(3) { c -> adjusted[c] + p.epsilonSdr[c] }))
        val one = (hdrY / max(sdrY, 1e-6)).coerceIn(MIN_BOOST, MAX_BOOST)
        return doubleArrayOf(one, one, one)
    }

    /** The new map's range from the boosts it holds; never empty, so encoding stays defined. */
    fun range(lowest: Double, highest: Double): Pair<Double, Double> {
        val lo = lowest.coerceIn(MIN_BOOST, MAX_BOOST)
        val hi = max(highest.coerceIn(MIN_BOOST, MAX_BOOST), lo * 1.001)
        return lo to hi
    }

    /** The 3×3 taking linear colours of the space whose XYZ transform is [from] to the one whose is [to] (column-major, as `ColorSpace.Rgb.getTransform`); row-major out. */
    fun between(from: FloatArray, to: FloatArray): DoubleArray = multiply(inverse(rowMajor(to)), rowMajor(from))

    fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }

    private fun luminance(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    private fun rowMajor(columnMajor: FloatArray) = DoubleArray(9) { i -> columnMajor[(i % 3) * 3 + i / 3].toDouble() }

    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun inverse(m: DoubleArray): DoubleArray {
        val (a, b, c, d, e) = m
        val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** Boosts kept sane: a map from 1/64 to 64 times. */
    const val MIN_BOOST = 1.0 / 64
    const val MAX_BOOST = 64.0
}
