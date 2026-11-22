package sh.hnet.gallery.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val order = listOf(10L, 11L, 12L, 13L, 14L)

    @Test
    fun aDragSelectsTheRunAndShrinksBack() {
        // From the second to the fourth, forward
        assertEquals(setOf(11L, 12L, 13L), Selection.run(emptySet(), order, anchor = 1, current = 3, select = true))
        // Backward from the fourth to the second, with an earlier choice kept outside the run
        assertEquals(setOf(10L, 11L, 12L, 13L), Selection.run(setOf(10L), order, anchor = 3, current = 1, select = true))
        // Moved back to the first tile: only it
        assertEquals(setOf(11L), Selection.run(emptySet(), order, anchor = 1, current = 1, select = true))
        // A run that clears: the first tile was selected
        assertEquals(setOf(10L, 14L), Selection.run(order.toSet(), order, anchor = 1, current = 3, select = false))
        // Past the end: clamped
        assertEquals(setOf(13L, 14L), Selection.run(emptySet(), order, anchor = 3, current = 9, select = true))
    }

    @Test
    fun aHeaderSelectsItsDayOrClearsIt() {
        assertEquals(setOf(1L, 2L, 3L), Selection.toggleGroup(setOf(1L), listOf(2L, 3L)))
        assertEquals(setOf(1L), Selection.toggleGroup(setOf(1L, 2L, 3L), listOf(2L, 3L)))
        val tile = { id: Long, day: Int -> TimelineEntry.Tile(MediaItem(id, false, "image/jpeg", id, "", 0, "DCIM/Camera/", "external_primary", 1, 1, 1, 0, false, false, 0, false, 1), LocalDate.of(2026, 11, day)) }
        val entries = listOf(TimelineEntry.Day(LocalDate.of(2026, 11, 19)), tile(1, 19), tile(2, 19), TimelineEntry.Day(LocalDate.of(2026, 11, 18)), tile(3, 18))
        assertEquals(mapOf("d2026-11-19" to listOf(1L, 2L), "d2026-11-18" to listOf(3L)), Selection.groups(entries))
    }

    @Test
    fun daysLeftInTrash() {
        val now = 1_000_000_000_000L
        // Milliseconds, as the index keeps DATE_EXPIRES (owner's test of S4a: seconds taken for milliseconds gave 20 786 042 days)
        assertEquals(30, TrashDays.left(now + 30 * 86_400_000L + 60_000, now))
        assertEquals(0, TrashDays.left(now + 3_600_000, now))
        assertEquals(0, TrashDays.left(now - 10_000, now))
    }

    @Test
    fun fileNames() {
        assertEquals("Zachód słońca" to ".jpg", FileNames.split("Zachód słońca.jpg"))
        assertEquals("archive.tar" to ".gz", FileNames.split("archive.tar.gz"))
        assertEquals(".hidden" to "", FileNames.split(".hidden"))
        assertEquals(FileNames.Problem.Empty, FileNames.problem("  ", ".jpg", emptyList()))
        assertEquals(FileNames.Problem.Characters(":?"), FileNames.problem("a:b?c:", ".jpg", emptyList()))
        assertEquals(FileNames.Problem.Exists, FileNames.problem("IMG_1", ".jpg", listOf("img_1.JPG")))
        assertNull(FileNames.problem("IMG_2", ".jpg", listOf("IMG_1.jpg")))
    }
}

class CollectionsTest {
    private fun item(id: Long, path: String = "DCIM/Camera/", video: Boolean = false, favorite: Boolean = false, trashed: Boolean = false, expires: Long = 0) =
        MediaItem(id, video, if (video) "video/mp4" else "image/jpeg", id, "", 0, path, "external_primary", 1, 1, 1, 0, favorite, trashed, expires, false, 1)

    @Test
    fun collectionsTakeEveryFolderButNotTrash() {
        assertTrue(CollectionKind.FAVORITES.holds(item(1, path = "Download/", favorite = true)))
        assertFalse(CollectionKind.FAVORITES.holds(item(2, favorite = true, trashed = true)))
        assertTrue(CollectionKind.VIDEOS.holds(item(3, path = "Movies/Screen recordings/", video = true)))
        assertTrue(CollectionKind.SCREENSHOTS.holds(item(4, path = "Pictures/Screenshots/")))
        assertFalse(CollectionKind.SCREENSHOTS.holds(item(5)))
        assertEquals(CollectionKind.VIDEOS, CollectionKind.of("VIDEOS"))
    }

    @Test
    fun trashLatestFirst() {
        val order = TrashOrder.of(listOf(item(1, trashed = true, expires = 100), item(2), item(3, trashed = true, expires = 300)))
        assertEquals(listOf(3L, 1L), order.map { it.id })
    }

    @Test
    fun aTypeAgainstItsNameIsFound() {
        val types = mapOf("mov" to "video/quicktime", "mp4" to "video/mp4", "jpg" to "image/jpeg")
        // MediaStore's scanner typed a QuickTime file by its container
        assertEquals("video/quicktime", FileNames.mistyped("TEST_TR3.mov", "video/mp4", types::get))
        assertEquals("video/quicktime", FileNames.mistyped("A.MOV", "video/mp4", types::get))
        // As it should be, unknown, without an extension, or of another kind: left alone
        assertNull(FileNames.mistyped("a.mp4", "video/mp4", types::get))
        assertNull(FileNames.mistyped("a.xyz", "video/mp4", types::get))
        assertNull(FileNames.mistyped("README", "video/mp4", types::get))
        assertNull(FileNames.mistyped("a.jpg", "video/mp4", types::get))
        // A file truly other than its name (HEIC named .jpg) keeps the type its bytes have
        assertNull(FileNames.mistyped("b.jpg", "image/heic", (types + ("jpg" to "image/jpeg"))::get))
    }
}
