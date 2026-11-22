package sh.hnet.gallery.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimTest {
    private val minute = Trim.whole(60_000)

    @Test
    fun handlesStayInTheVideoAndApart() {
        assertFalse(minute.changed)
        val a = minute.withStart(3_200)
        assertTrue(a.changed)
        assertEquals(3_200, a.startMs)
        // Never before the start of the video, nor past the end less a second
        assertEquals(0, minute.withStart(-500).startMs)
        assertEquals(59_000, minute.withStart(70_000).startMs)
        val b = a.withEnd(18_700)
        assertEquals(15_500, b.lengthMs)
        // The end kept a second after the start, and within the video
        assertEquals(4_200, b.withEnd(3_500).endMs)
        assertEquals(60_000, b.withEnd(90_000).endMs)
        assertEquals(17_700, b.withStart(18_000).startMs)
    }

    @Test
    fun aVideoShorterThanASecondCanOnlyBeWhole() {
        val short = Trim.whole(600)
        assertEquals(0, short.withStart(300).startMs)
        assertEquals(600, short.withEnd(100).endMs)
    }

    @Test
    fun stripPlacesAndTimes() {
        assertEquals(30_000, Trim.msAt(164f, 328f, 60_000))
        assertEquals(0, Trim.msAt(-10f, 328f, 60_000))
        assertEquals(60_000, Trim.msAt(400f, 328f, 60_000))
        assertEquals(82f, Trim.xOf(15_000, 328f, 60_000), 0.001f)
        assertEquals(listOf(7_500L, 22_500L, 37_500L, 52_500L), Trim.frameTimes(60_000, 4))
    }

    @Test
    fun timesToTheTenth() {
        val pl = Locale.forLanguageTag("pl")
        assertEquals("0:03,2", Trim.timeText(3_200, pl))
        assertEquals("0:03.2", Trim.timeText(3_249, Locale.US))
        assertEquals("0:03.3", Trim.timeText(3_250, Locale.US))
        assertEquals("1:00,0", Trim.timeText(59_960, pl))
        assertEquals("1:02:03,4", Trim.timeText(3_723_400, pl))
    }

    @Test
    fun historyOfTrims() {
        var h = History(minute).push(minute.withStart(1_000)).push(minute.withStart(1_000).withEnd(10_000))
        h = h.undo()
        assertEquals(1_000, h.present.startMs)
        assertEquals(60_000, h.present.endMs)
        h = h.undo()
        assertEquals(minute, h.present)
        assertTrue(h.canRedo)
        assertEquals(10_000, h.redo().redo().present.endMs)
    }
}
