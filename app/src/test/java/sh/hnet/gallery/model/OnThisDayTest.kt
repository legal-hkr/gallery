package sh.hnet.gallery.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnThisDayTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val today = LocalDate.of(2026, 11, 18)
    private var next = 1L

    private fun item(date: String, hour: Int = 12, video: Boolean = false, path: String = "DCIM/Camera/") =
        MediaItem(next++, video, if (video) "video/mp4" else "image/jpeg", LocalDate.parse(date).atTime(hour, 0).atZone(warsaw).toInstant().toEpochMilli(), "IMG.jpg", 0, path, "external_primary", 1, 1, 1, 0, false, false, 0, false, 1)

    private fun of(items: List<MediaItem>, offsets: Map<Long, Int> = emptyMap(), day: LocalDate = today) =
        OnThisDay.of(items, day, warsaw, { offsets[it] }) { TimelineFolders.isScreenshots(it.path) }

    @Test
    fun earlierYearsOnlyWithoutScreenshotsOrVideos() {
        val a = item("2023-11-18")
        val b = item("2021-11-18")
        val items = listOf(a, b, item("2026-11-18"), item("2024-11-17"), item("2022-11-18", video = true), item("2020-11-18", path = "Pictures/Screenshots/"))
        val found = of(items)!!
        assertEquals(listOf(a, b), found.items.map { it.item })
        assertEquals(listOf(2023, 2021), found.years)
        assertNull(of(listOf(item("2026-11-18"), item("2025-11-17"))))
    }

    @Test
    fun theDayInThePhotosOwnZone() {
        // 17 November 23:00 in Warsaw is already 18 November in Tokyo (UTC+9), where it was taken
        val tokyo = item("2024-11-17", hour = 23)
        val found = of(listOf(tokyo), offsets = mapOf(tokyo.id to 9 * 60))
        assertEquals(listOf(tokyo), found!!.items.map { it.item })
        // Without its zone, the phone's: 17 November
        assertNull(of(listOf(item("2024-11-17", hour = 23))))
    }

    @Test
    fun yearsSpreadAndAtMostTwenty() {
        val many = (1..15).map { item("2025-11-18", hour = it % 24) } + (1..15).map { item("2020-11-18", hour = it % 24) } + item("2019-11-18")
        val found = of(many)!!
        assertEquals(OnThisDay.LIMIT, found.items.size)
        // One from each year first, newest year first
        assertEquals(listOf(2025, 2020, 2019, 2025, 2020), found.items.take(5).map { it.year })
        assertEquals(listOf(2025, 2020, 2019), found.years)
    }

    @Test
    fun aLeapDayOnTheTwentyEighth() {
        assertTrue(OnThisDay.sameDay(LocalDate.of(2024, 2, 29), LocalDate.of(2027, 2, 28)))
        assertTrue(!OnThisDay.sameDay(LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 28)))
        assertTrue(OnThisDay.sameDay(LocalDate.of(2023, 2, 28), LocalDate.of(2027, 2, 28)))
        assertEquals(1, of(listOf(item("2024-02-29")), day = LocalDate.of(2027, 2, 28))!!.items.size)
    }

    @Test
    fun theWidgetsPickAndItsFallbacks() {
        val screenshot: (MediaItem) -> Boolean = { TimelineFolders.isScreenshots(it.path) }
        val a = item("2023-11-18")
        val b = item("2021-11-18")
        // Today's: one of them, the same all day for this widget, how long ago
        val pick = WidgetPick.of(listOf(a, b), emptyList(), today, warsaw, { null }, screenshot, WidgetPick.seed(today, 7)) as WidgetPick.Today
        assertEquals(listOf(a, b), pick.shown)
        assertEquals(today.year - pick.shown[pick.index].let { if (it == a) 2023 else 2021 }, pick.yearsAgo)
        assertEquals(pick, WidgetPick.of(listOf(a, b), emptyList(), today, warsaw, { null }, screenshot, WidgetPick.seed(today, 7)))
        // None today: around it, the nearest day first
        val near = item("2024-11-20")
        val far = item("2022-11-11")
        val around = WidgetPick.of(listOf(far, near, item("2024-11-01")), emptyList(), today, warsaw, { null }, screenshot, 0) as WidgetPick.Around
        assertEquals(listOf(near, far), around.shown)
        // None around: any photo of the timeline, with its date
        val any = item("2025-08-18")
        val anyPick = WidgetPick.of(emptyList(), listOf(any, item("2025-08-19", video = true)), today, warsaw, { null }, screenshot, 3) as WidgetPick.AnyDay
        assertEquals(any, anyPick.item)
        assertEquals(LocalDate.of(2025, 8, 18), anyPick.date)
        assertEquals(WidgetPick.Nothing, WidgetPick.of(emptyList(), emptyList(), today, warsaw, { null }, screenshot, 0))
    }

    @Test
    fun windowsToAskTheMediaFor() {
        val windows = OnThisDay.windows(today, warsaw)
        assertEquals(today.year - 1970, windows.size)
        // Last year's: from 10 November to 26 November, whole days in the phone's zone
        val last = windows.first()
        assertEquals(LocalDate.of(2025, 11, 10).atStartOfDay(warsaw).toInstant().toEpochMilli(), last.first)
        assertEquals(LocalDate.of(2025, 11, 27).atStartOfDay(warsaw).toInstant().toEpochMilli() - 1, last.last)
        assertEquals(LocalDate.of(2023, 2, 28), OnThisDay.dayIn(LocalDate.of(2024, 2, 29), 2023))
    }

    @Test
    fun aroundAcrossTheNewYear() {
        // 3 January 2027: a photo of 30 December 2025 is 4 days around 3 January 2026, one year ago
        val newYear = LocalDate.of(2027, 1, 3)
        val photo = item("2025-12-30")
        val around = OnThisDay.around(listOf(photo), newYear, warsaw, { null }) { false }
        assertEquals(listOf(photo), around.map { it.item })
        assertEquals(2026, around.single().year)
        // Not one from this year's days before today
        assertTrue(OnThisDay.around(listOf(item("2026-12-30")), newYear, warsaw, { null }) { false }.isEmpty())
    }
}
