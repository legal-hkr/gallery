package sh.hnet.gallery.data

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import sh.hnet.gallery.model.Adjustments
import sh.hnet.gallery.model.GainMath
import sh.hnet.gallery.model.GainParams

/**
 * An Ultra HDR photo's gain map made again for its adjusted base (S10b, LOGIKA §21.6): at the map's own resolution, the
 * HDR picture from the base before the adjustments and the old map, adjusted up to the photo's headroom ([GainMath]),
 * divided by the adjusted base – the new boosts, and their range as the new map's parameters.
 */
object GainRecompute {
    fun paramsOf(map: Gainmap) = GainParams(
        ratioMin = map.ratioMin.map(Float::toDouble).toDoubleArray(),
        ratioMax = map.ratioMax.map(Float::toDouble).toDoubleArray(),
        gamma = map.gamma.map(Float::toDouble).toDoubleArray(),
        epsilonSdr = map.epsilonSdr.map(Float::toDouble).toDoubleArray(),
        epsilonHdr = map.epsilonHdr.map(Float::toDouble).toDoubleArray(),
    )

    /** The base's linear values for its 8-bit codes, in its colour space. */
    fun linearTable(space: ColorSpace): DoubleArray {
        val eotf = (space as? ColorSpace.Rgb ?: ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb).eotf
        return DoubleArray(256) { eotf.applyAsDouble(it / 255.0) }
    }

    /**
     * [out]'s light averaged over each cell of a [width] × [height] grid – the gain map's –, in linear light: averaging
     * the encoded values first made fine bright detail (sparkles, stars) far too dark, and its boosts wrong (review of
     * S10b). Row by row, so the full picture is never copied. Three floats per cell.
     */
    fun linearMean(out: Bitmap, width: Int, height: Int, linear: DoubleArray): FloatArray {
        val sums = DoubleArray(width * height * 3)
        val counts = IntArray(width * height)
        val row = IntArray(out.width)
        for (y in 0 until out.height) {
            out.getPixels(row, 0, out.width, 0, y, out.width, 1)
            val cy = min(height - 1, y * height / out.height)
            for (x in 0 until out.width) {
                val cell = cy * width + min(width - 1, x * width / out.width)
                val color = row[x]
                val i = cell * 3
                sums[i] += linear[(color shr 16) and 0xFF]
                sums[i + 1] += linear[(color shr 8) and 0xFF]
                sums[i + 2] += linear[color and 0xFF]
                counts[cell]++
            }
        }
        return FloatArray(sums.size) { i -> (sums[i] / counts[i / 3].coerceAtLeast(1)).toFloat() }
    }

    /**
     * [gain] (the old map, laid out as the result) made again in place for [before] → [after] (the result's base over
     * the map's cells, linear – [linearMean] –, without and with the adjustments, in [space]); the new parameters, from
     * [template]'s.
     */
    fun apply(gain: Bitmap, before: FloatArray, after: FloatArray, template: Gainmap, adjust: Adjustments, space: ColorSpace): GainParams {
        val p = paramsOf(template)
        val rgb = space as? ColorSpace.Rgb ?: ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
        val srgb = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) as ColorSpace.Rgb
        val toSrgb = GainMath.between(rgb.transform, srgb.transform)
        val fromSrgb = GainMath.between(srgb.transform, rgb.transform)
        val single = gain.config == Bitmap.Config.ALPHA_8
        // Black and white must leave the lights without a tint: one boost for the three channels
        val alike = single || adjust.saturation == -100
        val w = gain.width
        val h = gain.height
        val gainBytes = ByteBuffer.allocate(gain.byteCount).also { gain.copyPixelsToBuffer(it) }.array()
        val gainRow = gain.rowBytes
        // First pass: the new boosts (as logarithms) and their range per channel
        val logs = FloatArray(w * h * 3)
        val lo = DoubleArray(3) { Double.MAX_VALUE }
        val hi = DoubleArray(3) { -Double.MAX_VALUE }
        val base = DoubleArray(3)
        val adjusted = DoubleArray(3)
        val gains = DoubleArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val b = (y * w + x) * 3
                for (c in 0..2) {
                    base[c] = before[b + c].toDouble()
                    adjusted[c] = after[b + c].toDouble()
                }
                if (single) {
                    val value = (gainBytes[y * gainRow + x].toInt() and 0xFF) / 255.0
                    for (c in 0..2) gains[c] = GainMath.decode(value, 0, p)
                } else {
                    val g = y * gainRow + x * 4
                    for (c in 0..2) gains[c] = GainMath.decode((gainBytes[g + c].toInt() and 0xFF) / 255.0, c, p)
                }
                val boosts = GainMath.newGains(base, adjusted, gains, p, adjust, toSrgb, fromSrgb, alike)
                val i = (y * w + x) * 3
                for (c in 0..2) {
                    val l = ln(boosts[c])
                    logs[i + c] = l.toFloat()
                    if (l < lo[c]) lo[c] = l
                    if (l > hi[c]) hi[c] = l
                }
            }
        }
        // The new range – one for all channels when the map has one, or the lights must stay untinted
        val ranges = Array(3) { c ->
            if (alike) GainMath.range(exp(lo.min()), exp(hi.max())) else GainMath.range(exp(lo[c]), exp(hi[c]))
        }
        // Second pass: the boosts encoded into the map
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                if (single) {
                    val value = GainMath.encode(exp(logs[i].toDouble()), ranges[0].first, ranges[0].second, p.gamma[0])
                    gainBytes[y * gainRow + x] = (value * 255).roundToInt().coerceIn(0, 255).toByte()
                } else {
                    val g = y * gainRow + x * 4
                    for (c in 0..2) {
                        val value = GainMath.encode(exp(logs[i + c].toDouble()), ranges[c].first, ranges[c].second, p.gamma[c])
                        gainBytes[g + c] = (value * 255).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
        }
        gain.copyPixelsFromBuffer(ByteBuffer.wrap(gainBytes))
        return GainParams(
            ratioMin = DoubleArray(3) { c -> ranges[c].first },
            ratioMax = DoubleArray(3) { c -> ranges[c].second },
            gamma = p.gamma,
            epsilonSdr = p.epsilonSdr,
            epsilonHdr = p.epsilonHdr,
        )
    }
}
