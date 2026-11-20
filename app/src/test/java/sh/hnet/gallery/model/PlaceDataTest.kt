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
        // The same place, not a namesake: Places shows Kraków once (Tokyo, its wards and its urban area came out as three)
        assertEquals(data.place(50.0617, 19.9373).settlement, data.place(50.0717, 20.0377).settlement)
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

class PlaceGroupsTest {
    private fun item(id: Long, trashed: Boolean = false) = MediaItem(id = id, video = false, mime = "image/jpeg", taken = 1000 - id, name = "$id.jpg", bucketId = 0, path = "DCIM/Camera/", volume = "external_primary", size = 1, width = 1, height = 1, duration = 0, favorite = false, trashed = trashed, expires = 0, ours = false, generation = 0)

    @Test
    fun countriesTownsRegionsAndTheRest() {
        // Country 1: town 10 (region 5) ×3, town 11 (region 6) ×1, one item at sea; country 2: town 20 ×2; two without location
        val places = mapOf(
            1L to Place(10, 5, 1), 2L to Place(10, 5, 1), 3L to Place(10, 5, 1), 4L to Place(11, 6, 1), 5L to Place(-1, -1, 1),
            6L to Place(20, 7, 2), 7L to Place(20, 7, 2),
        )
        val items = (1L..9L).map { item(it) } + item(10, trashed = true)
        val groups = PlaceGroups.of(items) { places[it] }
        assertEquals(listOf(1, 2), groups.countries.map { it.country })
        val first = groups.countries[0]
        assertEquals(5, first.count)
        // The item at sea is a group of its country alone (no carousel left it out), after the larger town
        assertEquals(listOf(10, PlaceKey.of(Place(-1, -1, 1)), 11), first.towns.map { it.settlement })
        assertEquals(listOf(5, 6, -1), first.regions.map { it.region })
        assertEquals(listOf(1L, 2L, 3L), groups.town(10)!!.items.map { it.id })
        assertEquals(listOf(8L, 9L), groups.noLocation.map { it.id })
        assertEquals(3, groups.townCount)
    }
    @Test
    fun aTownAcrossABorderCountsToItsOwnCountry() {
        // Town 30 placed in country 1 by its newest item; an older one of it whose own point lies across the border
        val places = mapOf(1L to Place(30, 5, 1), 2L to Place(30, 9, 2))
        val groups = PlaceGroups.of((1L..2L).map { item(it) }) { places[it] }
        assertEquals(listOf(1), groups.countries.map { it.country })
        assertEquals(2, groups.countries.single().count)
        assertEquals(2, groups.town(30)!!.items.size)
    }
}

class PlaceKeyTest {
    @Test
    fun aTownARegionACountry() {
        assertEquals(12, PlaceKey.of(Place(12, 3, 1)))
        val region = PlaceKey.of(Place(-1, 3, 1))
        assertEquals(3, PlaceKey.region(region))
        assertEquals(-1, PlaceKey.country(region))
        val country = PlaceKey.of(Place(-1, -1, 7))
        assertEquals(7, PlaceKey.country(country))
        assertEquals(-1, PlaceKey.region(country))
        // Region 0 and country 0 are keys too
        assertEquals(0, PlaceKey.region(PlaceKey.of(Place(-1, 0, 0))))
        assertEquals(0, PlaceKey.country(PlaceKey.of(Place(-1, -1, 0))))
    }

    @Test
    fun townlessItemsGroupByRegion() {
        val places = mapOf(1L to Place(-1, 4, 2), 2L to Place(-1, 4, 2), 3L to Place(20, 4, 2))
        val items = (1L..3L).map { MediaItem(id = it, video = false, mime = "image/jpeg", taken = 10 - it, name = "$it", bucketId = 0, path = "DCIM/Camera/", volume = "external_primary", size = 1, width = 1, height = 1, duration = 0, favorite = false, trashed = false, expires = 0, ours = false, generation = 0) }
        val country = PlaceGroups.of(items) { places[it] }.countries.single()
        assertEquals(3, country.count)
        assertEquals(listOf(PlaceKey.of(Place(-1, 4, 2)), 20), country.towns.map { it.settlement })
        assertEquals(listOf(1L, 2L), country.towns[0].items.map { it.id })
    }
}
