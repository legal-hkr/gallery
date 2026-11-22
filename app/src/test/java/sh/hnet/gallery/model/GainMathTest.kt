package sh.hnet.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainMathTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    // A phone's usual map: no dimming, up to 4× (two stops), linear encoding, small offsets
    private val p = GainParams(
        ratioMin = doubleArrayOf(1.0, 1.0, 1.0),
        ratioMax = doubleArrayOf(4.0, 4.0, 4.0),
        gamma = doubleArrayOf(1.0, 1.0, 1.0),
        epsilonSdr = doubleArrayOf(1.0 / 64, 1.0 / 64, 1.0 / 64),
        epsilonHdr = doubleArrayOf(1.0 / 64, 1.0 / 64, 1.0 / 64),
    )

    @Test
    fun valuesAndBoostsBothWays() {
        assertEquals(1.0, GainMath.decode(0.0, 0, p), 1e-12)
        assertEquals(4.0, GainMath.decode(1.0, 0, p), 1e-12)
        assertEquals(2.0, GainMath.decode(0.5, 0, p), 1e-12)
        assertEquals(0.5, GainMath.encode(2.0, 1.0, 4.0, 1.0), 1e-12)
        assertEquals(0.25, GainMath.encode(2.0, 1.0, 4.0, 2.0), 1e-12)
        assertEquals(4.0, p.headroom, 0.0)
    }

    @Test
    fun withoutAdjustmentsTheBoostsStay() {
        val base = doubleArrayOf(0.5, 0.3, 0.1)
        val gains = doubleArrayOf(2.0, 2.0, 2.0)
        val out = GainMath.newGains(base, base, gains, p, Adjustments(), identity, identity, single = false)
        for (c in 0..2) assertEquals(2.0, out[c], 1e-9)
    }

    @Test
    fun aBrightenedSkyKeepsItsLightAboveTheBase() {
        // A bright pixel boosted 3×: one stop up, the base bends under SDR's white (S10a's knee) while the HDR picture
        // doubles up to the headroom – the new boost is bigger than the old, so the highlight is still there in HDR
        val base = doubleArrayOf(0.8, 0.8, 0.8)
        val adjusted = Tone.apply(base, Adjustments(exposure = 10))
        val out = GainMath.newGains(base, adjusted, doubleArrayOf(3.0, 3.0, 3.0), p, Adjustments(exposure = 10), identity, identity, single = true)
        assertTrue(adjusted[0] < 1.0)
        assertTrue(out[0] > 3.0)
        // and the HDR picture it makes is brighter than before, bent under the headroom
        val hdr = (adjusted[0] + p.epsilonSdr[0]) * out[0] - p.epsilonHdr[0]
        assertTrue(hdr > (0.8 + 1.0 / 64) * 3.0 - 1.0 / 64)
        assertTrue(hdr < p.headroom)
    }

    @Test
    fun blackAndWhiteBoostsEveryChannelAlike() {
        val base = doubleArrayOf(0.6, 0.2, 0.1)
        val bw = Adjustments(saturation = -100)
        val out = GainMath.newGains(base, Tone.apply(base, bw), doubleArrayOf(2.5, 1.5, 1.2), p, bw, identity, identity, single = true)
        assertEquals(out[0], out[1], 0.0)
        assertEquals(out[1], out[2], 0.0)
    }

    @Test
    fun rangeAndColourSpaces() {
        val (lo, hi) = GainMath.range(2.0, 2.0)
        assertEquals(2.0, lo, 0.0)
        assertTrue(hi > lo)
        assertEquals(GainMath.MAX_BOOST, GainMath.range(1.0, 1000.0).second, 0.0)
        // The same space both ways: no change
        val xyz = floatArrayOf(0.4f, 0.2f, 0.02f, 0.35f, 0.7f, 0.1f, 0.15f, 0.07f, 0.7f)
        val m = GainMath.between(xyz, xyz)
        for (i in 0..8) assertEquals(identity[i], m[i], 1e-6)
    }
}
