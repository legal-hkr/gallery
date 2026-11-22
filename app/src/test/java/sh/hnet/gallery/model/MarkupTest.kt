package sh.hnet.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupTest {
    private val upright = PxSize(4000, 3000)
    private val red = MarkColors.ALL[2]

    private fun stroke(vararg points: Pair<Double, Double>, size: MarkSize = MarkSize.MEDIUM, highlighter: Boolean = false) =
        Stroke(1, highlighter, red, size, points.map { (x, y) -> Pt(x, y) })

    @Test
    fun thicknessFollowsThePhotosShorterSide() {
        assertEquals(24.0, stroke(0.1 to 0.1).widthPx(upright), 1e-9)
        assertEquals(1.6, stroke(0.1 to 0.1).widthPx(PxSize(200, 300)), 1e-9)
        assertEquals(72.0, stroke(0.1 to 0.1, highlighter = true).widthPx(upright), 1e-9)
    }

    @Test
    fun theEraserTakesAStrokeItTouches() {
        // A line across at 0.5 of the height: 1500 px, 24 px wide
        val line = stroke(0.1 to 0.5, 0.9 to 0.5)
        assertTrue(Markup.hit(line, Pt(2000.0, 1510.0), 5.0, upright))
        assertFalse(Markup.hit(line, Pt(2000.0, 1540.0), 5.0, upright))
        // Past the line's end it misses
        assertFalse(Markup.hit(line, Pt(3800.0, 1500.0), 5.0, upright))
        assertEquals(0, Markup.erase(listOf(line), Pt(2000.0, 1500.0), 5.0, upright).size)
    }

    @Test
    fun aTurnedTextIsHitInItsOwnFrame() {
        // 300 px high (0.1 of 3000), four times as wide, turned 90° about its middle: 1200 px tall, 360 px (a line) wide
        val text = TextMark(2, "Hello", red, 0.1, Pt(0.5, 0.5), 90.0, 4.0)
        assertTrue(Markup.hit(text, Pt(2000.0, 1500.0 + 550.0), 0.0, upright))
        assertFalse(Markup.hit(text, Pt(2000.0 + 250.0, 1500.0), 0.0, upright))
        assertEquals(text, Markup.textAt(listOf(stroke(0.0 to 0.0), text), Pt(2000.0, 1400.0), 0.0, upright))
        // Two lines: the block twice a line tall
        val two = text.copy(text = "Hello\nworld", rotation = 0.0)
        assertTrue(Markup.hit(two, Pt(2000.0, 1500.0 + 340.0), 0.0, upright))
        assertFalse(Markup.hit(two, Pt(2000.0, 1500.0 + 380.0), 0.0, upright))
        assertNull(Markup.textAt(listOf(text), Pt(100.0, 100.0), 0.0, upright))
    }

    @Test
    fun aFingersPointsAreThinned() {
        val points = (0..100).map { Pt(it * 0.5, 0.0) }
        val thinned = Markup.thin(points, 5.0)
        assertEquals(Pt(0.0, 0.0), thinned.first())
        assertEquals(Pt(50.0, 0.0), thinned.last())
        assertTrue(thinned.size in 10..12)
    }

    @Test
    fun noBoostInTheGainMap() {
        assertEquals(0f, Markup.neutralGain(1f, 4f, 1f), 1e-6f)
        assertEquals(1f / 3f, Markup.neutralGain(0.5f, 4f, 1f), 1e-4f)
        assertEquals(1f / 9f, Markup.neutralGain(0.5f, 4f, 2f), 1e-4f)
    }

    @Test
    fun marksAreAChangeAndTurnWithThePhoto() {
        val marked = EditState(marks = listOf(stroke(0.2 to 0.2)))
        assertTrue(marked.changed)
        // Marks are in upright coordinates: a turn leaves them as they are, the map from upright places them
        assertEquals(marked.marks, EditGeometry.rotate(marked).marks)
        val turned = EditGeometry.rotate(marked)
        val map = EditGeometry.matrix(upright, Orientation.NORMAL, turned)
        // The upright point (800, 600) after a quarter turn counter-clockwise: x = 600, y = 4000 − 800
        val p = map.map(Markup.toPx(Pt(0.2, 0.2), upright))
        assertEquals(600.0, p.x, 1e-6)
        assertEquals(3200.0, p.y, 1e-6)
    }

    @Test
    fun anchorAndCenterAreEachOthersWayBack() {
        // 100 px letters, 3 wide, two lines (120 px apart): the anchor 150 px left and 60 px up from the middle
        assertPt(Pt(850.0, 940.0), Markup.textAnchor(Pt(1000.0, 1000.0), 100.0, 3.0, 2, 0.0))
        // Turned 90°: left becomes up, up becomes right
        assertPt(Pt(1060.0, 850.0), Markup.textAnchor(Pt(1000.0, 1000.0), 100.0, 3.0, 2, 90.0))
        val anchor = Pt(300.0, 400.0)
        val center = Markup.textCenter(anchor, 80.0, 5.0, 3, 37.0)
        assertPt(anchor, Markup.textAnchor(center, 80.0, 5.0, 3, 37.0))
    }

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-6)
        assertEquals(expected.y, actual.y, 1e-6)
    }
}
