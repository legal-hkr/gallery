package sh.hnet.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {
    private val photo = PxSize(4000, 3000)

    private fun near(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-6)
        assertEquals(expected.y, actual.y, 1e-6)
    }

    @Test
    fun noEditIsTheIdentity() {
        val m = EditGeometry.matrix(photo, Orientation.NORMAL, EditState())
        near(Pt(10.0, 20.0), m.map(Pt(10.0, 20.0)))
    }

    @Test
    fun exifTurnsTheStoredPhotoUpright() {
        // Stored 4000 × 3000 shown turned 90° clockwise: the stored top-left lands at the shown top-right
        val m = EditGeometry.matrix(photo, Orientation.of(6), EditState())
        near(Pt(3000.0, 0.0), m.map(Pt(0.0, 0.0)))
        near(Pt(0.0, 4000.0), m.map(Pt(4000.0, 3000.0)))
        // As the viewer's Orientation maps it back
        val shown = Pt(100.0, 250.0)
        val back = Orientation.of(6).toSource(Vec(100f, 250f), Dim(4000f, 3000f))
        near(shown, m.map(Pt(back.x.toDouble(), back.y.toDouble())))
    }

    @Test
    fun aQuarterCounterClockwise() {
        val turned = EditGeometry.rotate(EditState())
        // The top-right corner goes to the top-left; the result is 3000 wide, 4000 high
        val m = EditGeometry.matrix(photo, Orientation.NORMAL, turned)
        near(Pt(0.0, 0.0), m.map(Pt(4000.0, 0.0)))
        assertEquals(PxSize(3000, 4000), EditGeometry.output(photo, turned))
    }

    @Test
    fun aTurnAfterAMirrorStaysWhereTheEyeExpects() {
        // Mirror then turn left: the photo's top-right (after the mirror, top-left) goes to the bottom-left
        val state = EditGeometry.rotate(EditGeometry.flip(EditState()))
        val m = EditGeometry.matrix(photo, Orientation.NORMAL, state)
        near(Pt(0.0, 4000.0), m.map(Pt(4000.0, 0.0)))
    }

    @Test
    fun theCropTurnsWithThePhoto() {
        val state = EditState(crop = Crop(0.0, 0.0, 0.5, 0.5))
        // The top-left quarter, turned left, is the bottom-left quarter
        assertEquals(Crop(0.0, 0.5, 0.5, 1.0), EditGeometry.rotate(state).crop)
        assertEquals(Crop(0.5, 0.0, 1.0, 0.5), EditGeometry.flip(state).crop)
    }

    @Test
    fun straighteningShrinksTheCropInside() {
        val turned = EditGeometry.turned(photo, EditState())
        assertTrue(EditGeometry.inside(Crop.WHOLE, 0.0, turned))
        assertFalse(EditGeometry.inside(Crop.WHOLE, 5.0, turned))
        val state = EditGeometry.withAngle(EditState(), 5.0, turned)
        assertTrue(EditGeometry.inside(state.crop, 5.0, turned))
        assertTrue(state.crop.width > 0.8)
        assertEquals(0.5, state.crop.centerX, 1e-6)
    }

    @Test
    fun ratios() {
        val turned = EditGeometry.turned(photo, EditState())
        val square = EditGeometry.withRatio(EditState(), CropRatio.SQUARE, false, turned)
        assertEquals(square.crop.width * 4000, square.crop.height * 3000, 1e-3)
        assertEquals(3000.0, square.crop.height * 3000, 1e-3)
        val portrait = EditGeometry.withRatio(EditState(), CropRatio.SIXTEEN_NINE, true, turned)
        assertEquals(9.0 / 16, portrait.crop.width * 4000 / (portrait.crop.height * 3000), 1e-6)
    }

    @Test
    fun aCornerDraggedKeepsTheRatioAndStaysInside() {
        val turned = EditGeometry.turned(photo, EditState())
        val square = EditGeometry.withRatio(EditState(), CropRatio.SQUARE, false, turned)
        val dragged = EditGeometry.drag(square, 2, 2.0, 2.0, turned)
        assertTrue(EditGeometry.inside(dragged.crop, 0.0, turned))
        assertEquals(dragged.crop.width * 4000, dragged.crop.height * 3000, 1e-3)
    }

    @Test
    fun undoRedo() {
        val a = EditState(quarters = 1)
        val b = EditState(quarters = 2)
        var h = EditHistory(EditState()).push(a).push(b)
        h = h.undo()
        assertEquals(a, h.present)
        assertTrue(h.canRedo)
        h = h.redo()
        assertEquals(b, h.present)
        h = h.undo().push(EditState(flipped = true))
        assertFalse(h.canRedo)
    }

    @Test
    fun copyNames() {
        assertEquals("IMG_1-edytowane.jpg", editedName("IMG_1.jpg", "-edytowane", listOf("IMG_1.jpg")))
        assertEquals("IMG_1-edytowane (2).jpg", editedName("IMG_1.jpg", "-edytowane", listOf("img_1-EDYTOWANE.jpg")))
    }
}

class EditingMoveTest {
    private val photo = PxSize(4000, 3000)

    @Test
    fun aHalfCropMovesDownAndStopsAtTheEdgeWithoutDrifting() {
        val turned = EditGeometry.turned(photo, EditState())
        val start = EditState(crop = Crop(0.25, 0.25, 0.75, 0.75))
        val moved = EditGeometry.move(start, 0.0, 0.9, turned)
        assertEquals(0.25, moved.crop.left, 1e-6)
        assertEquals(1.0, moved.crop.bottom, 1e-3)
        val up = EditGeometry.move(start, 0.0, -0.9, turned)
        assertEquals(0.0, up.crop.top, 1e-3)
        assertEquals(0.25, up.crop.left, 1e-6)
    }

    @Test
    fun movingStraightenedStaysInside() {
        val turned = EditGeometry.turned(photo, EditState())
        val start = EditGeometry.withAngle(EditState(crop = Crop(0.3, 0.3, 0.7, 0.7)), 10.0, turned)
        for (d in listOf(0.05, 0.1, 0.2, 0.4)) {
            val moved = EditGeometry.move(start, 0.0, d, turned)
            assertTrue(EditGeometry.inside(moved.crop, 10.0, turned))
            assertEquals(start.crop.width, moved.crop.width, 1e-9)
        }
    }
}

/** The owner's test of S7: a full-width crop didn't move, the crop slowed past an edge and rode an arc sideways. */
class EditingSlideTest {
    private val photo = PxSize(4000, 3000)
    private val turned = EditGeometry.turned(photo, EditState())

    @Test
    fun aFullWidthCropMovesUpAndDownWhateverTheFingerDoesSideways() {
        val start = EditState(crop = Crop(0.0, 0.25, 1.0, 0.75))
        val moved = EditGeometry.move(start, 0.03, 0.1, turned)
        assertEquals(0.0, moved.crop.left, 1e-9)
        assertEquals(0.35, moved.crop.top, 1e-6)
    }

    @Test
    fun pastAnEdgeItStopsThereAndGoesOnAlongIt() {
        val start = EditState(crop = Crop(0.4, 0.4, 0.6, 0.6))
        // The finger far past the right edge and a little down: right at the edge, down all the way the finger went
        val moved = EditGeometry.move(start, 0.9, 0.1, turned)
        assertEquals(1.0, moved.crop.right, 1e-6)
        assertEquals(0.5, moved.crop.top, 1e-6)
    }

    @Test
    fun sidewaysWithoutAnArc() {
        val start = EditState(crop = Crop(0.4, 0.4, 0.6, 0.6))
        for (dx in listOf(-0.5, -0.3, -0.1, 0.1, 0.3, 0.5)) {
            val moved = EditGeometry.move(start, dx, 0.0, turned)
            assertEquals(0.4, moved.crop.top, 1e-9)
        }
    }

    @Test
    fun straightenedItSlidesAlongTheSlantedEdge() {
        val start = EditGeometry.withAngle(EditState(crop = Crop(0.4, 0.4, 0.6, 0.6)), 10.0, turned)
        val moved = EditGeometry.move(start, 0.0, 0.6, turned)
        assertTrue(EditGeometry.inside(moved.crop, 10.0, turned))
        assertTrue(moved.crop.top > start.crop.top + 0.1)
    }

    @Test
    fun aFreeCornerPastTheEdgeKeepsTheOtherAxis() {
        val start = EditState(crop = Crop(0.2, 0.2, 0.6, 0.6))
        val dragged = EditGeometry.drag(start, 2, 1.5, 0.8, turned)
        assertEquals(1.0, dragged.crop.right, 1e-6)
        assertEquals(0.8, dragged.crop.bottom, 1e-6)
        assertEquals(0.2, dragged.crop.left, 1e-9)
    }

    @Test
    fun aRatioCornerStaysOnItsDiagonal() {
        val square = EditGeometry.withRatio(EditState(crop = Crop(0.2, 0.2, 0.6, 0.6)), CropRatio.SQUARE, false, turned)
        val dragged = EditGeometry.drag(square, 2, 2.0, 0.7, turned)
        assertEquals(dragged.crop.width * 4000, dragged.crop.height * 3000, 1e-3)
        assertTrue(EditGeometry.inside(dragged.crop, 0.0, turned))
        assertEquals(square.crop.left, dragged.crop.left, 1e-9)
        assertEquals(square.crop.top, dragged.crop.top, 1e-9)
    }
}

/** The owner's test of S7: straightening shrank the crop step by step from the last, so it never grew back. */
class EditingStraightenTest {
    private val photo = PxSize(4000, 3000)
    private val turned = EditGeometry.turned(photo, EditState())

    @Test
    fun theCropGrowsBackAsTheAngleComesBack() {
        var state = EditState()
        for (a in listOf(2.0, 5.0, 10.0, 15.0)) state = EditGeometry.withAngle(state, a, turned)
        val tilted = state.crop
        for (a in listOf(10.0, 5.0, 2.0, 0.0)) state = EditGeometry.withAngle(state, a, turned)
        assertEquals(Crop.WHOLE, state.crop)
        // The same angle the other way is the same size, and as large as straight from the start
        val other = EditGeometry.withAngle(state, -15.0, turned)
        assertEquals(tilted.width, other.crop.width, 1e-6)
        assertEquals(EditGeometry.withAngle(EditState(), 15.0, turned).crop.width, tilted.width, 1e-6)
        // Nothing left to save once back at 0°
        assertFalse(state.changed)
    }

    @Test
    fun aDraggedCropNeverGrowsPastWhatWasSet() {
        val tilted = EditGeometry.withAngle(EditState(), 10.0, turned)
        val dragged = EditGeometry.drag(tilted, 2, 0.6, 0.6, turned)
        val straight = EditGeometry.withAngle(dragged, 0.0, turned)
        assertEquals(dragged.crop, straight.crop)
    }
}

/** Two fingers zoom and move the photo under a frame that stays (owner, 20 Nov 2026, as iOS). */
class EditingZoomTest {
    private val photo = PxSize(4000, 3000)
    private val turned = EditGeometry.turned(photo, EditState())

    @Test
    fun closerShrinksTheCropAboutTheFingers() {
        val zoomed = EditGeometry.zoom(EditState(), 0.5, 0.5, 2.0, 0.0, 0.0, turned).crop
        assertEquals(Crop(0.25, 0.25, 0.75, 0.75).left, zoomed.left, 1e-9)
        assertEquals(0.75, zoomed.bottom, 1e-9)
        // About a point off the middle: that point stays where it was in the crop
        val off = EditGeometry.zoom(EditState(), 0.2, 0.3, 2.0, 0.0, 0.0, turned).crop
        assertEquals(0.2, off.left + (0.2 - 0.0) / 1.0 * off.width, 1e-9)
        assertEquals(0.5, off.width, 1e-9)
    }

    @Test
    fun theShapeStays() {
        val start = EditState(crop = Crop(0.1, 0.2, 0.5, 0.4))
        val zoomed = EditGeometry.zoom(start, 0.3, 0.3, 1.7, 0.0, 0.0, turned).crop
        assertEquals(start.crop.width / start.crop.height, zoomed.width / zoomed.height, 1e-9)
    }

    @Test
    fun fartherStopsAtThePhotoPushedBackIn() {
        // Near the top-left corner, twice as far: grown about the fingers, pushed back in, the whole photo at most
        val zoomed = EditGeometry.zoom(EditState(crop = Crop(0.0, 0.0, 0.5, 0.5)), 0.1, 0.1, 0.5, 0.0, 0.0, turned).crop
        assertEquals(Crop.WHOLE, zoomed)
        val far = EditGeometry.zoom(EditState(crop = Crop(0.25, 0.25, 0.75, 0.75)), 0.5, 0.5, 0.01, 0.0, 0.0, turned).crop
        assertEquals(Crop.WHOLE, far)
    }

    @Test
    fun neverSmallerThanTheSmallest() {
        val zoomed = EditGeometry.zoom(EditState(), 0.5, 0.5, 1000.0, 0.0, 0.0, turned).crop
        assertTrue(zoomed.height * 3000 >= 0.05 * 3000 - 1e-6)
    }

    @Test
    fun straightenedStaysInside() {
        val tilted = EditGeometry.withAngle(EditState(), 12.0, turned)
        for (factor in listOf(0.2, 0.7, 1.3, 3.0)) {
            val zoomed = EditGeometry.zoom(tilted, 0.1, 0.9, factor, 0.0, 0.0, turned)
            assertTrue(EditGeometry.inside(zoomed.crop, 12.0, turned))
        }
    }

    @Test
    fun theFingersMoveThePhotoSoTheCropGoesTheOtherWay() {
        val start = EditState(crop = Crop(0.25, 0.25, 0.75, 0.75))
        val moved = EditGeometry.zoom(start, 0.5, 0.5, 1.0, 0.1, 0.0, turned).crop
        assertEquals(0.15, moved.left, 1e-9)
        assertEquals(0.25, moved.top, 1e-9)
    }
}

/** Review before v0.1.0: Original on a portrait photo, and a ratio alone isn't an edit. */
class EditingRatioTest {
    @Test
    fun originalKeepsThePhotosOwnShape() {
        val portrait = PxSize(3000, 4000)
        assertEquals(0.75, EditGeometry.ratioOf(EditState(ratio = CropRatio.ORIGINAL), portrait)!!, 1e-9)
        assertEquals(4.0 / 3, EditGeometry.ratioOf(EditState(ratio = CropRatio.ORIGINAL, portrait = true), portrait)!!, 1e-9)
        assertEquals(4.0 / 3, EditGeometry.ratioOf(EditState(ratio = CropRatio.ORIGINAL), PxSize(4000, 3000))!!, 1e-9)
    }

    @Test
    fun aRatioThatLeavesTheCropIsNoChange() {
        val chosen = EditGeometry.withRatio(EditState(), CropRatio.FOUR_THREE, false, PxSize(4000, 3000))
        assertEquals(Crop.WHOLE, chosen.crop)
        assertFalse(chosen.changed)
    }
}
