package sh.hnet.gallery.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {
    private val pl = Locale.forLanguageTag("pl")

    @Test
    fun shortVideosLoop() {
        assertTrue(Playback.loops(5_000))
        assertTrue(Playback.loops(10_000))
        assertFalse(Playback.loops(10_001))
        assertFalse(Playback.loops(0))
    }

    @Test
    fun frameLength() {
        assertEquals(33L, Playback.frameMs(30f))
        assertEquals(16L, Playback.frameMs(60f))
        assertEquals(41L, Playback.frameMs(null))
        assertEquals(41L, Playback.frameMs(0f))
    }

    @Test
    fun speedsAndFps() {
        assertEquals("0,25", Playback.speedText(0.25f, pl))
        assertEquals("1", Playback.speedText(1f, pl))
        assertEquals("1,5", Playback.speedText(1.5f, pl))
        assertEquals("29,97", Playback.fpsText(29.97f, pl))
        assertEquals(listOf(0.25f, 0.5f, 1f, 1.5f, 2f), Playback.SPEEDS)
    }

    @Test
    fun videoLocation() {
        assertEquals(40.758 to -73.9855, Playback.iso6709("+40.7580-073.9855/"))
        assertEquals(49.2992 to 19.9496, Playback.iso6709("+49.2992+019.9496+0012.000/"))
        assertNull(Playback.iso6709("nonsense"))
        assertNull(Playback.iso6709(null))
    }
}
