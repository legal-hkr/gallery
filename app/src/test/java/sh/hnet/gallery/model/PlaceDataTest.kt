package sh.hnet.gallery.model

import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** On a real extract of Lesser Poland (`tools/places/build.py --box 49.0,19.0,51.0,21.0`). */
class PlaceDataTest {
    private val data = PlaceData(ByteBuffer.wrap(GZIPInputStream(javaClass.getResourceAsStream("/places-malopolska.bin.gz")!!).readBytes()))

    private fun named(lat: Double, lon: Double, language: PlaceLanguage = PlaceLanguage.POLISH): Triple<String?, String?, String?> {
        val place = data.place(lat, lon)
        return Triple(
            place.settlement.takeIf { it >= 0 }?.let { data.settlementName(it, language) },
            place.region.takeIf { it >= 0 }?.let { data.regionName(it, language) },
            place.country.takeIf { it >= 0 }?.let { data.countryName(it, language) },
        )
    }

    @Test
    fun krakowsMarketSquare() {
        assertEquals(Triple("Kraków", "małopolskie", "Polska"), named(50.0617, 19.9373))
        assertEquals(Triple("Kraków", "Lesser Poland Voivodeship", "Poland"), named(50.0617, 19.9373, PlaceLanguage.ENGLISH))
    }

    @Test
    fun aPartOfTheCityIsTheCity() {
        // Nowa Huta's square, 9 km from the centre: a part of Kraków, not a village beyond it
        assertEquals("Kraków", named(50.0717, 20.0377).first)
    }

    @Test
    fun aTownInTheMountains() {
        assertEquals("Zakopane", named(49.2992, 19.9496).first)
    }

    @Test
    fun farFromEverything() {
        assertEquals(Place.NONE, data.place(10.0, 10.0))
        assertEquals(-1, data.nearest(10.0, 10.0))
    }

    @Test
    fun namesOfAllLanguagesForSearch() {
        val krakow = data.place(50.0617, 19.9373).settlement
        // Wikidata's English name is "Kraków" too: one name, no repeats (search folds diacritics itself, SPEC §12.3)
        assertEquals(listOf("Kraków"), data.settlementNames(krakow))
        assertTrue(data.countryNames(data.place(50.0617, 19.9373).country).containsAll(listOf("Polska", "Poland")))
    }

    @Test
    fun distance() {
        // Kraków – Zakopane, about 84 km in a straight line
        val d = PlaceData.distanceKm(50.0617, 19.9373, 49.2992, 19.9496)
        assertTrue(d in 83.0..86.0)
        // Across the date line
        assertTrue(PlaceData.distanceKm(0.0, 179.9, 0.0, -179.9) < 25.0)
    }

    @Test
    fun borderWithAHole() {
        val square = intArrayOf(0, 0, 100, 0, 100, 100, 0, 100, 0, 0)
        val hole = intArrayOf(40, 40, 60, 40, 60, 60, 40, 60, 40, 40)
        val border = Border(listOf(square, hole))
        assertTrue(border.contains(10, 10))
        assertTrue(!border.contains(50, 50))
        assertTrue(!border.contains(150, 50))
    }
}

class DayPlacesTest {
    private fun item(id: Long) = MediaItem(id = id, video = false, mime = "image/jpeg", taken = 0, name = "$id.jpg", bucketId = 0, path = "DCIM/Camera/", volume = "external_primary", size = 1, width = 1, height = 1, duration = 0, favorite = false, trashed = false, expires = 0, ours = false, generation = 0)

    private fun day(d: Int) = TimelineEntry.Day(java.time.LocalDate.of(2026, 11, d))

    private fun tile(id: Long, d: Int) = TimelineEntry.Tile(item(id), java.time.LocalDate.of(2026, 11, d))

    @Test
    fun mostFrequentTwoOrNone() {
        val entries = listOf(
            day(20), tile(1, 20), tile(2, 20), tile(3, 20), tile(4, 20),
            day(19), tile(5, 19), tile(6, 19), tile(7, 19),
            day(18), tile(8, 18),
        )
        // Day 20: three towns, the most frequent; day 19: two, both, the more frequent first; day 18: no place
        val towns = mapOf(1L to 10, 2L to 10, 3L to 11, 4L to 12, 5L to 20, 6L to 21, 7L to 21)
        val places = DayPlaces.of(entries) { towns[it] }
        assertEquals(listOf(10), places.getValue(day(20).key).toList())
        assertEquals(listOf(21, 20), places.getValue(day(19).key).toList())
        assertEquals(false, day(18).key in places)
    }
}
