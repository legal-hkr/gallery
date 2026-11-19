package sh.hnet.gallery.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPS = 0.01f

class ZoomMathTest {
    private val portrait = Dim(1080f, 2400f)
    private val landscape = Dim(2400f, 1080f)
    /** A 50 MP photo, 4:3 landscape. */
    private val photo = Dim(8160f, 6120f)

    @Test
    fun maxScaleReachesThePixels() {
        // Every photo zooms to 10× (the owner), here past one image pixel per screen pixel (7.56×)
        assertEquals(10f, ZoomMath.maxScale(portrait, photo), EPS)
        assertEquals(10f, ZoomMath.maxScale(portrait, Dim(800f, 600f)), EPS)
        // A larger one goes on to its pixels: 16 320 px on 1080
        assertEquals(16320f / 1080f, ZoomMath.maxScale(portrait, Dim(16320f, 12240f)), EPS)
    }

    @Test
    fun doubleTapFillsTheScreen() {
        // A 4:3 landscape photo in a portrait screen: filled when its height meets the screen's
        val fitted = ZoomMath.fitted(portrait, photo)
        assertEquals(2400f / fitted.height, ZoomMath.fillScale(portrait, photo), EPS)
        // A photo of the screen's own shape already fills it: 2× instead
        assertEquals(2f, ZoomMath.fillScale(portrait, Dim(1080f * 4, 2400f * 4)), EPS)
    }

    @Test
    fun theImageStaysOnScreen() {
        val fitted = ZoomMath.fitted(portrait, photo)
        // Fitted: no room to move
        assertEquals(Vec.ZERO, ZoomMath.constrain(Vec(500f, 500f), 1f, portrait, photo))
        // 2×: as far as the image is wider than the screen
        val c = ZoomMath.constrain(Vec(5000f, -5000f), 2f, portrait, photo)
        assertEquals((fitted.width * 2 - 1080f) / 2, c.x, EPS)
        assertEquals(0f, c.y, EPS)
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val focus = Vec(900f, 300f)
        val offset = ZoomMath.offsetFor(Vec.ZERO, 1f, 2f, focus, portrait)
        // The same container point maps to the same image point before and after
        val before = ZoomMath.visibleRegion(1f, Vec.ZERO, portrait, photo)
        val after = ZoomMath.visibleRegion(2f, offset, portrait, photo)
        assertTrue(after.width < before.width)
        assertEquals(Vec(-(900f - 540f), -(300f - 1200f)), offset)
    }

    @Test
    fun turningKeepsTheCentreAndThePixelSize() {
        // Zoomed to 3× in portrait, moved right
        val (scale, offset) = ZoomMath.resized(3f, Vec(-400f, 0f), portrait, landscape, photo)
        val fitPortrait = ZoomMath.fitScale(portrait, photo)
        val fitLandscape = ZoomMath.fitScale(landscape, photo)
        // An image pixel keeps its size on screen
        assertEquals(3f * fitPortrait, scale * fitLandscape, EPS)
        // The same image point at the centre
        val centreBefore = ZoomMath.visibleRegion(3f, Vec(-400f, 0f), portrait, photo)
        val centreAfter = ZoomMath.visibleRegion(scale, offset, landscape, photo)
        assertEquals((centreBefore.left + centreBefore.right) / 2, (centreAfter.left + centreAfter.right) / 2, 1f)
        // Fitted stays fitted
        assertEquals(1f to Vec.ZERO, ZoomMath.resized(1f, Vec.ZERO, portrait, landscape, photo))
    }

    @Test
    fun visibleRegionWhenFittedIsTheWholeImage() {
        val all = ZoomMath.visibleRegion(1f, Vec.ZERO, portrait, photo)
        assertEquals(0f, all.left, 0.5f)
        assertEquals(0f, all.top, 0.5f)
        assertEquals(8160f, all.right, 0.5f)
        assertEquals(6120f, all.bottom, 0.5f)
    }

    @Test
    fun theEdgeHandsTheDragToThePager() {
        val excess = (ZoomMath.fitted(portrait, photo).width * 2 - 1080f) / 2
        // At the left edge (the image moved fully right), a drag to the right goes to the pager; to the left it pans
        assertTrue(ZoomMath.atEdge(Vec(excess, 0f), 2f, portrait, photo, dx = 10f))
        assertTrue(!ZoomMath.atEdge(Vec(excess, 0f), 2f, portrait, photo, dx = -10f))
        // Not wider than the screen: every sideways drag is the pager's
        assertTrue(ZoomMath.atEdge(Vec.ZERO, 1f, portrait, photo, dx = -10f))
    }

    @Test
    fun samplingGivesAtLeastOnePixelPerScreenPixel() {
        val fit = ZoomMath.fitScale(portrait, photo)
        // Fitted: 0.13 screen px per image px → 4× sampling (the next, 8×, would be too coarse)
        assertEquals(4, ZoomMath.sampleFor(1f, fit))
        assertEquals(1, ZoomMath.sampleFor(ZoomMath.maxScale(portrait, photo), fit))
        assertEquals(2, ZoomMath.sampleFor(3f, fit))
    }
}

class TilesTest {
    @Test
    fun tilesCoverTheVisiblePartAndStopAtTheImage() {
        val image = Dim(1300f, 700f)
        val tiles = Tiles.covering(Box(0f, 0f, 1300f, 700f), 1, image)
        // 3 columns (512, 512, 276) × 2 rows (512, 188)
        assertEquals(6, tiles.size)
        assertEquals(Box(1024f, 512f, 1300f, 700f), tiles.last().region)
        // At 2× sampling a tile spans 1024 image pixels
        assertEquals(2, Tiles.covering(Box(0f, 0f, 1300f, 700f), 2, image).size)
        // Only what is visible
        assertEquals(listOf(1 to 0), Tiles.covering(Box(600f, 100f, 900f, 400f), 1, image).map { it.col to it.row })
    }
}

class OrientationTest {
    /** A stored image of 400 × 300, displayed per its EXIF orientation. */
    private val source = Dim(400f, 300f)

    @Test
    fun displayedSize() {
        assertEquals(source, Orientation.of(1).displayed(source))
        assertEquals(Dim(300f, 400f), Orientation.of(6).displayed(source))
        assertEquals(Dim(300f, 400f), Orientation.of(8).displayed(source))
    }

    @Test
    fun displayedCornersComeFromTheRightStoredCorners() {
        // 6: turned 90° clockwise – the displayed top-left corner is the stored bottom-left
        assertEquals(Vec(0f, 300f), Orientation.of(6).toSource(Vec(0f, 0f), source))
        // 8: turned 270° – the displayed top-left is the stored top-right
        assertEquals(Vec(400f, 0f), Orientation.of(8).toSource(Vec(0f, 0f), source))
        // 3: upside down
        assertEquals(Vec(400f, 300f), Orientation.of(3).toSource(Vec(0f, 0f), source))
        // 2: mirrored
        assertEquals(Vec(400f, 0f), Orientation.of(2).toSource(Vec(0f, 0f), source))
        // 1: as stored
        assertEquals(Vec(10f, 20f), Orientation.of(1).toSource(Vec(10f, 20f), source))
    }

    @Test
    fun aDisplayedRectMapsToAStoredRect() {
        // The displayed top strip of an image turned 90° is the stored left strip
        assertEquals(Box(0f, 0f, 100f, 300f), Orientation.of(6).toSource(Box(0f, 0f, 300f, 100f), source))
    }
}

class ViewerListTest {
    private fun item(id: Long, trashed: Boolean = false) = MediaItem(id, false, "image/jpeg", id, "", 0, "DCIM/Camera/", "external_primary", 1, 1, 1, 0, false, trashed, 0, false, 1)

    @Test
    fun deletedAndTrashedDropOutUndoBringsBack() {
        val opened = listOf(1L, 2L, 3L)
        assertEquals(listOf(1L, 3L), ViewerList.pages(opened, mapOf(1L to item(1), 2L to item(2, trashed = true), 3L to item(3))))
        assertEquals(listOf(1L, 2L, 3L), ViewerList.pages(opened, mapOf(1L to item(1), 2L to item(2), 3L to item(3))))
    }

    @Test
    fun theNextOneFollowsAGoneItem() {
        val opened = listOf(1L, 2L, 3L)
        assertEquals(2L, ViewerList.follow(2L, opened, listOf(1L, 2L, 3L)))
        assertEquals(3L, ViewerList.follow(2L, opened, listOf(1L, 3L)))
        // The last one gone: the previous
        assertEquals(2L, ViewerList.follow(3L, opened, listOf(1L, 2L)))
        assertNull(ViewerList.follow(1L, listOf(1L), emptyList()))
    }
}

class ExifTextTest {
    private val pl = Locale.forLanguageTag("pl")

    @Test
    fun cameraValues() {
        assertEquals("f/2,8", ExifText.aperture(2.8, pl))
        assertEquals("f/4", ExifText.aperture(4.0, pl))
        assertEquals("1/250 s", ExifText.exposure(0.004, pl))
        assertEquals("2,5 s", ExifText.exposure(2.5, pl))
        assertEquals("113 mm", ExifText.focalLength(113.0, pl))
        assertEquals("6,9 mm", ExifText.focalLength(6.9, pl))
        assertEquals("12,5", ExifText.megapixels(4080, 3072, pl))
        assertEquals("49,29920", ExifText.coordinate(49.2992, pl))
    }

    @Test
    fun exifDate() {
        assertEquals("2026:11:19 14:32:00" to "+01:00", ExifText.exifDate(2026, 11, 19, 14, 32, 60))
        assertEquals("2026:06:01 08:05:00" to "-05:30", ExifText.exifDate(2026, 6, 1, 8, 5, -330))
    }
}
