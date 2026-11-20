package sh.hnet.gallery.model

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTest {
    private val ids = listOf(1L, 2L, 3L, 4L)

    @Test
    fun goesOnAndStartsAgainAfterTheLast() {
        assertEquals(2L, Slideshow.next(ids, 1L) { true })
        assertEquals(1L, Slideshow.next(ids, 4L) { true })
    }

    @Test
    fun skipsWhatIsGoneAndCountsFromAGoneSlidesPlace() {
        assertEquals(4L, Slideshow.next(ids, 2L) { it != 3L })
        // The current one deleted meanwhile: the one after its place
        assertEquals(3L, Slideshow.next(ids, 2L) { it != 2L })
        assertEquals(1L, Slideshow.next(ids, 1L) { it == 1L })
        assertNull(Slideshow.next(ids, 1L) { false })
    }

    @Test
    fun startsAtTheChosenOneOrTheFirstThere() {
        assertEquals(3L, Slideshow.first(ids, 3L) { true })
        assertEquals(2L, Slideshow.first(ids, 9L) { it != 1L })
    }

    @Test
    fun aVideoPlaysToItsEndOrFifteenSeconds() {
        assertEquals(5_000L, Slideshow.slideMs(false, 0, 5))
        assertEquals(8_000L, Slideshow.slideMs(true, 8_000, 5))
        assertEquals(15_000L, Slideshow.slideMs(true, 120_000, 5))
        assertEquals(15_000L, Slideshow.slideMs(true, 0, 5))
        assertEquals(5, Slideshow.seconds(7))
        assertEquals(20, Slideshow.seconds(20))
    }

    @Test
    fun kenBurnsNeverShowsPastThePhoto() {
        val random = Random(7)
        repeat(500) {
            val path = KenBurns.random(random)
            assertTrue(abs(path.from.scale - path.to.scale) > 0.08f)
            for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val pose = path.at(t)
                assertTrue(pose.scale in KenBurns.MIN_SCALE..KenBurns.MAX_SCALE)
                // Scaled about the middle by s, the photo reaches (s - 1) / 2 of its width past each side of the frame
                val room = (pose.scale - 1f) * 1000f / 2f
                assertTrue(abs(pose.shiftX(1000f)) <= room + 1e-3f)
                assertTrue(abs(pose.shiftY(1000f)) <= room + 1e-3f)
            }
        }
    }
}
