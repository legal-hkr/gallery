package sh.hnet.gallery.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** An item taken at a local time in Warsaw (or at [offset] when given). */
private fun item(id: Long, at: String, offset: ZoneOffset? = null, path: String = "DCIM/Camera/", trashed: Boolean = false): MediaItem {
    val local = LocalDateTime.parse(at)
    val instant = if (offset != null) local.toInstant(offset) else local.atZone(WARSAW).toInstant()
    return MediaItem(id, video = false, mime = "image/jpeg", taken = instant.toEpochMilli(), name = "IMG_$id.jpg", bucketId = path.hashCode().toLong(), path = path, volume = "external_primary", size = 1, width = 1, height = 1, duration = 0, favorite = false, trashed = trashed, expires = 0, ours = false, generation = id)
}

class TimelineFoldersTest {
    @Test
    fun cameraFolders() {
        assertTrue(TimelineFolders.isCamera("DCIM/Camera/"))
        assertTrue(TimelineFolders.isCamera("DCIM/camera/"))
        assertTrue(TimelineFolders.isCamera("DCIM/100ANDRO/"))
        assertTrue(TimelineFolders.isCamera("DCIM/100MEDIA/"))
        assertTrue(TimelineFolders.isCamera("DCIM/101CANON/"))
        assertFalse(TimelineFolders.isCamera("DCIM/Camera/Sub/"))
        assertFalse(TimelineFolders.isCamera("DCIM/099ANDRO/"))
        assertFalse(TimelineFolders.isCamera("DCIM/100AND/"))
        assertFalse(TimelineFolders.isCamera("Pictures/Camera/"))
        assertFalse(TimelineFolders.isCamera("DCIM/Screenshots/"))
    }

    @Test
    fun screenshotFolders() {
        assertTrue(TimelineFolders.isScreenshots("Pictures/Screenshots/"))
        assertTrue(TimelineFolders.isScreenshots("DCIM/Screenshots/"))
        assertFalse(TimelineFolders.isScreenshots("Pictures/Screenshots/Old/"))
        assertFalse(TimelineFolders.isScreenshots("Download/Screenshots/"))
    }

    @Test
    fun choiceOverridesDefaults() {
        val camera = FolderKey("external_primary", "DCIM/Camera/")
        val whatsapp = FolderKey("external_primary", "Pictures/WhatsApp/")
        assertTrue(TimelineFolders.shown(camera, emptySet(), emptySet()))
        assertFalse(TimelineFolders.shown(camera, emptySet(), setOf(camera.key)))
        assertFalse(TimelineFolders.shown(whatsapp, emptySet(), emptySet()))
        assertTrue(TimelineFolders.shown(whatsapp, setOf(whatsapp.key), emptySet()))
        // The same path on an SD card is a folder of its own
        assertTrue(TimelineFolders.shown(FolderKey("1234-5678", "DCIM/Camera/"), emptySet(), setOf(camera.key)))
    }

    @Test
    fun folderKeyRoundTrip() {
        val key = FolderKey("external_primary", "Pictures/A:B/")
        assertEquals(key, FolderKey.of(key.key))
        assertNull(FolderKey.of("nocolon"))
    }
}

class TimelineTest {
    @Test
    fun groupsByDayNewestFirst() {
        val items = listOf(item(3, "2026-11-18T20:00"), item(2, "2026-11-18T08:00"), item(1, "2026-11-17T23:59")).sortedWith(MediaItem.NEWEST_FIRST)
        val timeline = Timeline.build(items, months = false, zone = WARSAW) { null }
        assertEquals(listOf("d2026-11-18", "3", "2", "d2026-11-17", "1"), timeline.entries.map { it.key })
        assertEquals(mapOf(3L to 1, 2L to 2, 1L to 4), timeline.indexOf)
        assertFalse(timeline.months)
    }

    @Test
    fun groupsByMonth() {
        val items = listOf(item(3, "2026-11-01T10:00"), item(2, "2026-10-31T10:00"), item(1, "2025-10-15T10:00")).sortedWith(MediaItem.NEWEST_FIRST)
        val timeline = Timeline.build(items, months = true, zone = WARSAW) { null }
        assertEquals(listOf("m2026-11", "3", "m2026-10", "2", "m2025-10", "1"), timeline.entries.map { it.key })
        assertEquals(YearMonth.of(2025, 10), timeline.entries.last().month)
    }

    @Test
    fun photoOwnZoneDecidesTheDay() {
        // 18:30 in New York on 17 Nov is 00:30 on 18 Nov in Warsaw: the photo's own offset keeps it on the 17th
        val newYork = item(1, "2026-11-17T18:30", offset = ZoneOffset.ofHours(-5))
        assertEquals(LocalDate.of(2026, 11, 18), Timeline.dateOf(newYork, WARSAW, null))
        assertEquals(LocalDate.of(2026, 11, 17), Timeline.dateOf(newYork, WARSAW, -300))
    }

    @Test
    fun dayReachedTwiceIsOneGroup() {
        // Taken later but on an earlier local day (flying west): it joins the 17th, after the 18th's items
        val tokyo = item(1, "2026-11-18T09:00", offset = ZoneOffset.ofHours(9))
        val newYork = item(2, "2026-11-17T22:00", offset = ZoneOffset.ofHours(-5))
        val home = item(3, "2026-11-17T08:00")
        val items = listOf(tokyo, newYork, home).sortedWith(MediaItem.NEWEST_FIRST)
        val offsets = mapOf(1L to 540, 2L to -300)
        val timeline = Timeline.build(items, months = false, zone = WARSAW) { offsets[it] }
        assertEquals(listOf("d2026-11-18", "1", "d2026-11-17", "2", "3"), timeline.entries.map { it.key })
    }

    @Test
    fun nearestStep() {
        assertEquals(GridStep.HUGE, GridStep.nearest(2.2f))
        assertEquals(GridStep.LARGE, GridStep.nearest(2.6f))
        assertEquals(GridStep.MEDIUM, GridStep.nearest(4.4f))
        assertEquals(GridStep.SMALL, GridStep.nearest(5.9f))
        assertEquals(GridStep.MONTHS, GridStep.nearest(6.1f))
        assertEquals(GridStep.MEDIUM, GridStep.of("nonsense"))
    }
}

class IndexMergeTest {
    private val a = item(1, "2026-11-01T10:00")
    private val b = item(2, "2026-11-02T10:00")
    private val c = item(3, "2026-11-03T10:00")

    @Test
    fun addsChangesAndDeletes() {
        val current = listOf(b, a)
        val bTrashed = b.copy(trashed = true, generation = 99)
        val merged = IndexMerge.merge(current, listOf(c, bTrashed), present = setOf(2, 3))
        assertEquals(listOf(3L, 2L), merged.map { it.id })
        assertTrue(merged.first { it.id == 2L }.trashed)
    }

    @Test
    fun unchangedKeepsTheSameList() {
        val current = listOf(b, a)
        assertTrue(IndexMerge.merge(current, emptyList(), setOf(1, 2)) === current)
    }

    @Test
    fun equalTimesByIdNewestFirst() {
        val x = item(10, "2026-11-01T10:00")
        val y = item(11, "2026-11-01T10:00")
        assertEquals(listOf(11L, 10L), IndexMerge.merge(emptyList(), listOf(x, y), null).map { it.id })
    }
}

class AlbumsTest {
    @Test
    fun foldersWithItemsOutsideTrash() {
        val items = listOf(
            item(4, "2026-11-04T10:00", path = "Pictures/Trashed/", trashed = true),
            item(3, "2026-11-03T10:00", path = "Pictures/WhatsApp/"),
            item(2, "2026-11-02T10:00"),
            item(1, "2026-11-01T10:00"),
        )
        val albums = Albums.of(items)
        assertEquals(listOf("Pictures/WhatsApp/", "DCIM/Camera/"), albums.map { it.folder.path })
        assertEquals(2, albums[1].count)
        assertEquals(2L, albums[1].newest.id)
    }
}
