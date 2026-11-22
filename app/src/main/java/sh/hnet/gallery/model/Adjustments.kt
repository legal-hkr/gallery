package sh.hnet.gallery.model

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Adjust's seven adjustments (SPEC §15.3), in the chips' order. */
enum class Adjust { EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, SATURATION, WARMTH, SHARPNESS }

/**
 * The adjustments of an edit (SPEC §15.3, LOGIKA §21.2): exposure in tenths of a stop (−20…20, so −2…+2 EV by 0.1),
 * the rest −100…100; zero is no change. Whole numbers, so states compare exactly (history, "changed").
 */
data class Adjustments(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    val sharpness: Int = 0,
) {
    val neutral: Boolean get() = this == NONE

    operator fun get(adjust: Adjust): Int = when (adjust) {
        Adjust.EXPOSURE -> exposure
        Adjust.CONTRAST -> contrast
        Adjust.HIGHLIGHTS -> highlights
        Adjust.SHADOWS -> shadows
        Adjust.SATURATION -> saturation
        Adjust.WARMTH -> warmth
        Adjust.SHARPNESS -> sharpness
    }

    /** One adjustment set, kept within its range. */
    fun with(adjust: Adjust, value: Int): Adjustments {
        val v = value.coerceIn(rangeOf(adjust))
        return when (adjust) {
            Adjust.EXPOSURE -> copy(exposure = v)
            Adjust.CONTRAST -> copy(contrast = v)
            Adjust.HIGHLIGHTS -> copy(highlights = v)
            Adjust.SHADOWS -> copy(shadows = v)
            Adjust.SATURATION -> copy(saturation = v)
            Adjust.WARMTH -> copy(warmth = v)
            Adjust.SHARPNESS -> copy(sharpness = v)
        }
    }

    /** The exposure in stops. */
    val ev: Double get() = exposure / 10.0

    companion object {
        val NONE = Adjustments()

        fun rangeOf(adjust: Adjust): IntRange = if (adjust == Adjust.EXPOSURE) -20..20 else -100..100

        /**
         * An adjustment's value as its chip shows it (SPEC §15.3: "Światła −20", "Ekspozycja +0,3"): signed, the
         * typographic minus, exposure in stops with the language's decimal separator.
         */
        fun valueText(adjust: Adjust, value: Int, locale: Locale): String {
            val sign = when {
                value > 0 -> "+"
                value < 0 -> "−"
                else -> ""
            }
            val magnitude = kotlin.math.abs(value)
            if (adjust != Adjust.EXPOSURE) return sign + magnitude
            val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            return "$sign${magnitude / 10}$separator${magnitude % 10}"
        }
    }
}

/**
 * Filters (SPEC §15.4, LOGIKA §21.4, D41): presets of the adjustments. Choosing one sets all seven to its values (D38);
 * the chosen one stays marked while they are tuned further.
 */
enum class Filter(val preset: Adjustments) {
    NONE(Adjustments.NONE),
    WARM(Adjustments(warmth = 35, saturation = 10)),
    COOL(Adjustments(warmth = -35, saturation = -5)),
    BLACK_WHITE(Adjustments(saturation = -100, contrast = 15)),
    FADED(Adjustments(contrast = -30, shadows = 25, saturation = -25)),
    VIVID(Adjustments(saturation = 35, contrast = 15, shadows = 10)),
}

/**
 * The tone math of the adjustments, in linear light (LOGIKA §21.2) – the same formulas, line for line, as the shader in
 * `data/AdjustShader` that draws them (kept here to be tested). Sharpness works on neighbours, so it is the shader's
 * alone. A colour is linear sRGB (extended: wide-gamut colours go below 0 and over 1).
 */
object Tone {
    /** Rec. 709 luminance. */
    private val LUMA = doubleArrayOf(0.2126, 0.7152, 0.0722)

    /** Where the soft knee starts: brightened light reaches white along a curve, not cut off at it. */
    const val KNEE = 0.8

    fun luminance(c: DoubleArray): Double = c[0] * LUMA[0] + c[1] * LUMA[1] + c[2] * LUMA[2]

    /**
     * Values up to [KNEE] as they are; above it towards 1 without reaching it, slowly enough that brighter stays
     * told apart (1.2 → 0.93, 1.6 → 0.96, 3.2 → 0.98) – a curve that met white fast was a cut again, only smoother.
     */
    fun knee(m: Double): Double {
        if (m <= KNEE) return m
        val x = (m - KNEE) / (1 - KNEE)
        return KNEE + (1 - KNEE) * x / (1 + x)
    }

    /** [c] (linear) with [a]'s adjustments; a new array. */
    fun apply(c: DoubleArray, a: Adjustments): DoubleArray {
        val m0 = max(c[0], max(c[1], c[2]))
        val lin = c.copyOf()
        // Exposure: light times 2^EV, as an aperture
        val gain = 2.0.pow(a.ev)
        for (i in 0..2) lin[i] *= gain
        // Warmth: towards yellow or blue, as a white balance
        val w = a.warmth / 100.0
        lin[0] *= 1 + 0.2 * w
        lin[2] *= 1 - 0.2 * w
        // Contrast around middle grey, then highlights and shadows, all on the luminance (hues kept)
        val l = max(luminance(lin), 1e-6)
        val lc = 0.18 * (l / 0.18).pow(1 + 0.5 * a.contrast / 100.0)
        val p = sqrt(l.coerceIn(0.0, 1.0))
        val wh = smoothstep(0.5, 1.0, p)
        val ws = 1 - smoothstep(0.0, 0.5, p)
        val lt = lc * 2.0.pow(a.highlights / 100.0 * wh + 1.2 * a.shadows / 100.0 * ws)
        for (i in 0..2) lin[i] *= lt / l
        // Saturation around the luminance: −100 is grey
        val l2 = luminance(lin)
        val s = 1 + a.saturation / 100.0
        for (i in 0..2) lin[i] = l2 + (lin[i] - l2) * s
        // The knee, only for what was brightened: a pixel as bright as before stays as it was
        val m = max(lin[0], max(lin[1], lin[2]))
        if (m > m0 && m > KNEE) {
            val from = m0.coerceIn(0.0, 1.0)
            val target = from + knee(m) - knee(from)
            for (i in 0..2) lin[i] *= target / m
        }
        // Not clamped: a wide-gamut colour is below 0 in sRGB's primaries, and stays so
        return lin
    }

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
