package sh.hnet.gallery.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val krakow = SearchPart.Place(PlaceLevel.TOWN, 10, "Kraków")
    private val nowyJork = SearchPart.Place(PlaceLevel.TOWN, 11, "Nowy Jork")
    private val majVillage = SearchPart.Place(PlaceLevel.TOWN, 12, "Maj")
    private val camera = SearchPart.Album("s:external_primary:DCIM/Camera/", "Aparat")
    private val names = listOf(
        SearchName("Kraków", krakow), SearchName("Krakow", krakow), SearchName("Cracow", krakow),
        SearchName("Nowy Jork", nowyJork), SearchName("New York", nowyJork),
        SearchName("Maj", majVillage),
        SearchName("Aparat", camera),
    )
    private val counts = mapOf<SearchPart, Int>(SearchPart.Month(5, null) to 40, majVillage to 2)
    private val parser = SearchParser(names, today) { counts[it] ?: 0 }

    private fun parts(text: String) = parser.parse(text).map { it.part }

    @Test
    fun placeAndYear() {
        assertEquals(listOf(krakow, SearchPart.Year(2023)), parts("kraków 2023"))
        // Without diacritics, any case
        assertEquals(listOf(krakow, SearchPart.Year(2023)), parts("KRAKOW 2023"))
    }

    @Test
    fun monthsInEveryCaseAndLanguage() {
        assertEquals(listOf(SearchPart.Month(7, 2025)), parts("lipca 2025"))
        assertEquals(listOf(SearchPart.Month(7, 2025)), parts("July 2025"))
        assertEquals(listOf(SearchPart.Month(10, null)), parts("październiku"))
        assertEquals(listOf(SearchPart.Month(2, null)), parts("lutym"))
    }

    @Test
    fun days() {
        assertEquals(listOf(SearchPart.Day(today.minusDays(1))), parts("wczoraj"))
        assertEquals(listOf(SearchPart.Day(today)), parts("dzisiaj"))
        assertEquals(listOf(SearchPart.Day(LocalDate.of(2025, 11, 18))), parts("18.11.2025"))
        assertEquals(listOf(SearchPart.Day(LocalDate.of(2026, 3, 4))), parts("4.3"))
    }

    @Test
    fun manyWordsAndKinds() {
        assertEquals(listOf(nowyJork), parts("nowy jork"))
        assertEquals(listOf(SearchPart.Kind(SearchKind.SCREENSHOTS)), parts("screenshots"))
        assertEquals(listOf(SearchPart.Kind(SearchKind.MOTION)), parts("zdjęcia ruchome"))
        assertEquals(listOf(SearchPart.Kind(SearchKind.VIDEOS), camera), parts("filmy aparat"))
    }

    @Test
    fun aWordOfTwoMeaningsTakesTheMoreFrequent() {
        val maj = parser.parse("maj").single()
        assertEquals(SearchPart.Month(5, null), maj.part)
        assertTrue(majVillage in maj.options)
    }

    @Test
    fun prefixesAndFileNames() {
        assertEquals(listOf(krakow), parts("krak"))
        assertEquals(listOf(SearchPart.FileName("IMG_2041")), parts("IMG_2041"))
        // Two letters aren't enough for a prefix
        assertEquals(listOf(SearchPart.FileName("kr")), parts("kr"))
    }

    @Test
    fun suggestions() {
        val found = parser.suggest("lato w kra")
        assertEquals(krakow, found.first())
        assertTrue(parser.suggest("lip").contains(SearchPart.Month(7, null)))
    }
}
