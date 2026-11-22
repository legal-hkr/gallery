package sh.hnet.gallery.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {
    private val pl = Locale.forLanguageTag("pl")

    @Test
    fun rangesAndSteps() {
        val a = Adjustments().with(Adjust.EXPOSURE, 3)
        assertEquals(0.3, a.ev, 1e-12)
        assertEquals(20, a.with(Adjust.EXPOSURE, 99).exposure)
        assertEquals(-100, a.with(Adjust.SHADOWS, -150).shadows)
        assertEquals(42, a.with(Adjust.WARMTH, 42)[Adjust.WARMTH])
        assertTrue(Adjustments().neutral)
        assertFalse(a.neutral)
    }

    @Test
    fun chipValues() {
        assertEquals("+0,3", Adjustments.valueText(Adjust.EXPOSURE, 3, pl))
        assertEquals("−1.5", Adjustments.valueText(Adjust.EXPOSURE, -15, Locale.US))
        assertEquals("−20", Adjustments.valueText(Adjust.HIGHLIGHTS, -20, pl))
        assertEquals("+7", Adjustments.valueText(Adjust.SHARPNESS, 7, pl))
        assertEquals("0", Adjustments.valueText(Adjust.CONTRAST, 0, pl))
    }

    @Test
    fun adjustmentsAndFiltersAreChangesButTheFilterAloneIsNot() {
        assertTrue(EditState(adjust = Adjustments(contrast = 5)).changed)
        assertTrue(EditState(adjust = Filter.WARM.preset, filter = Filter.WARM).changed)
        // Warm chosen, then every value tuned back to zero: nothing left to save
        assertFalse(EditState(filter = Filter.WARM).changed)
        assertEquals(Adjustments(saturation = -100, contrast = 15), Filter.BLACK_WHITE.preset)
        assertTrue(Filter.NONE.preset.neutral)
    }

    @Test
    fun zeroChangesNothing() {
        val c = doubleArrayOf(0.05, 0.4, 0.95)
        val out = Tone.apply(c, Adjustments())
        for (i in 0..2) assertEquals(c[i], out[i], 1e-9)
    }

    @Test
    fun oneStopDoublesTheLight() {
        val out = Tone.apply(doubleArrayOf(0.1, 0.2, 0.3), Adjustments(exposure = 10))
        assertEquals(0.2, out[0], 1e-9)
        assertEquals(0.4, out[1], 1e-9)
        assertEquals(0.6, out[2], 1e-9)
    }

    @Test
    fun brightenedLightBendsBelowWhite() {
        // One stop on bright pixels: a hard cut would make both 1.0 (1.2 and 1.6); the knee keeps them under white, apart
        val out = Tone.apply(doubleArrayOf(0.6, 0.6, 0.6), Adjustments(exposure = 10))
        assertEquals(0.8 + 0.2 * 2.0 / 3.0, out[0], 1e-9)
        val brighter = Tone.apply(doubleArrayOf(0.8, 0.8, 0.8), Adjustments(exposure = 10))
        assertEquals(0.8 + 0.2 * 4.0 / 5.0, brighter[0], 1e-9)
        assertTrue(brighter[0] < 1.0)
        // Darkened pixels are never bent
        val darker = Tone.apply(doubleArrayOf(0.95, 0.95, 0.95), Adjustments(exposure = -10))
        assertEquals(0.475, darker[0], 1e-9)
    }

    @Test
    fun blackAndWhiteIsGreyOfTheSameLuminance() {
        val c = doubleArrayOf(0.6, 0.2, 0.1)
        val out = Tone.apply(c, Adjustments(saturation = -100))
        assertEquals(out[0], out[1], 1e-9)
        assertEquals(out[1], out[2], 1e-9)
        assertEquals(Tone.luminance(c), out[0], 1e-9)
    }

    @Test
    fun warmthAndShadowsGoTheirWay() {
        val warm = Tone.apply(doubleArrayOf(0.3, 0.3, 0.3), Adjustments(warmth = 50))
        assertTrue(warm[0] > warm[2])
        val lifted = Tone.apply(doubleArrayOf(0.02, 0.02, 0.02), Adjustments(shadows = 50))
        assertTrue(lifted[0] > 0.02)
        // Shadows leave the highlights as they were
        val bright = Tone.apply(doubleArrayOf(0.9, 0.9, 0.9), Adjustments(shadows = 50))
        assertEquals(0.9, bright[0], 1e-6)
    }
}
