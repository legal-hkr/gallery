package sh.hnet.gallery.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGeometryTest {
    private fun tile(id: Long) = TimelineEntry.Tile(
        MediaItem(id, false, "image/jpeg", id, "", 0, "DCIM/Camera/", "external_primary", 1, 1, 1, 0, false, false, 0, false, 1),
        LocalDate.of(2026, 11, 19),
    )

    private val entries = listOf(TimelineEntry.Day(LocalDate.of(2026, 11, 19))) + (1L..5L).map { tile(it) } +
        TimelineEntry.Day(LocalDate.of(2026, 11, 18)) + tile(6)

    @Test
    fun headersAndLinesAsTheGridLaysThemOut() {
        val g = GridGeometry.of(entries, columns = 4, width = 400, gap = 0, headerHeight = 50)
        assertEquals(Box(0f, 0f, 400f, 50f), g.box(0))
        assertTrue(g.isHeader(0))
        // Four tiles in the first line, the fifth on the next
        assertEquals(Box(300f, 50f, 400f, 150f), g.box(4))
        assertEquals(Box(0f, 150f, 100f, 250f), g.box(5))
        // The next day's header after the open line
        assertEquals(250f, g.box(6).top)
        assertEquals(Box(0f, 300f, 100f, 400f), g.box(7))
        assertEquals(400f, g.height)
        assertFalse(g.isHeader(7))
    }

    @Test
    fun gapsBetweenCellsAndLines() {
        val g = GridGeometry.of(entries, columns = 4, width = 406, gap = 2, headerHeight = 50)
        assertEquals(100f, g.cell)
        assertEquals(Box(102f, 52f, 202f, 152f), g.box(2))
        assertEquals(154f, g.box(5).top)
    }

    @Test
    fun wholePixelsAsLazyVerticalGrid() {
        // 1080 px, 7 columns, 3 px gaps: 1062 px of cells, 151 each and the first five 152 (as GridCells.Fixed)
        val g = GridGeometry.of(entries, columns = 7, width = 1080, gap = 3, headerHeight = 50)
        assertEquals(Box(0f, 53f, 152f, 205f), g.box(1))
        assertEquals(Box(155f, 53f, 307f, 205f), g.box(2))
        // The day's line is as high as its widest cell; the next header after it and the gap
        assertEquals(53f + 152f + 3f, g.box(6).top)
        // A card before the entries: they start after it and its gap
        assertEquals(103f, GridGeometry.of(entries, 7, 1080, 3, 50, lead = 100).box(0).top)
    }

    @Test
    fun whereTheGridStands() {
        val g = GridGeometry.of(entries, columns = 4, width = 400, gap = 0, headerHeight = 50)
        // 60 px down: 10 px into the first line of tiles (any of its items puts the line first), after a card item or not
        assertEquals(4 to 10, g.scrollTo(60f, leadItems = 0))
        assertEquals(5 to 10, g.scrollTo(60f, leadItems = 1))
        assertEquals(0 to 0, g.scrollTo(0f, leadItems = 0))
        assertEquals(6 to 0, g.scrollTo(250f, leadItems = 0))
    }

    @Test
    fun entriesInARange() {
        val g = GridGeometry.of(entries, columns = 4, width = 400, gap = 0, headerHeight = 50)
        assertEquals(1..5, g.between(60f, 200f))
        assertEquals(0..0, g.between(0f, 40f))
        assertEquals(6..7, g.between(260f, 1000f))
    }

    @Test
    fun pinchStepsByThresholds() {
        // Below the threshold nothing changes, the zoom goes on counting
        assertEquals(null to 1.2f, PinchSteps.track(GridStep.MEDIUM, 1.2f))
        // Spread past it: the larger step, counting again from 1
        assertEquals(GridStep.LARGE to 1f, PinchSteps.track(GridStep.MEDIUM, 1.3f))
        assertEquals(GridStep.SMALL to 1f, PinchSteps.track(GridStep.MEDIUM, 0.79f))
        assertEquals(GridStep.MONTHS to 1f, PinchSteps.track(GridStep.SMALL, 0.7f))
        // At the ends the zoom doesn't build up where there is no step: the way back starts at once
        assertEquals(GridStep.HUGE to 1f, PinchSteps.track(GridStep.LARGE, 1.3f))
        assertEquals(null to 1f, PinchSteps.track(GridStep.HUGE, 3f))
        assertEquals(GridStep.LARGE to 1f, PinchSteps.track(GridStep.HUGE, 0.75f))
        assertEquals(null to 1f, PinchSteps.track(GridStep.MONTHS, 0.2f))
    }
}

class MorphsTest {
    private fun tile(id: Long, day: Int) = TimelineEntry.Tile(
        MediaItem(id, false, "image/jpeg", id, "", 0, "DCIM/Camera/", "external_primary", 1, 1, 1, 0, false, false, 0, false, 1),
        LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
    )

    /** 100 days of 5 photos on a 1080 × 2400 px screen. */
    private val entries = (0 until 100).flatMap { d -> listOf(TimelineEntry.Day(LocalDate.of(2026, 1, 1).plusDays(d.toLong()))) + (0 until 5).map { tile(d * 5L + it, d) } }
    private val four = GridGeometry.of(entries, 4, 1080, 5, 132)
    private val five = GridGeometry.of(entries, 5, 1080, 5, 132)

    @Test
    fun theLayerFillsTheScreenInTheMiddleOfTheList() {
        val anchor = "250"
        val box = four.box(four.index(anchor)!!)
        val pieces = Morphs.pieces(four, five, anchor, pivotY = 1200f, fy = 0.5f, viewport = 2400f, t = 0f)
        assertTrue(pieces.size > 12)
        // At t = 0 the anchor tile is where the fingers hold it
        val anchorPiece = pieces.first { four.keys[it.index] == anchor }
        assertEquals(1200f - box.height / 2, anchorPiece.box.top, 0.5f)
        // Pieces cover the screen from top to bottom
        assertTrue(pieces.minOf { it.box.top } <= 0f)
        assertTrue(pieces.maxOf { it.box.bottom } >= 2400f)
    }

    @Test
    fun atTheTopNoGapAbove() {
        val pieces = Morphs.pieces(four, five, "1", pivotY = 300f, fy = 0.5f, viewport = 2400f, t = 0.5f)
        assertEquals(0f, pieces.minOf { it.box.top }, 0.5f)
    }
}
