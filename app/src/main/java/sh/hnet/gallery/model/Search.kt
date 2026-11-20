package sh.hnet.gallery.model

import java.text.Normalizer
import java.time.LocalDate

/** Text as search compares it (SPEC §12.3): lower case, without diacritics ("Kraków" is "krakow", "Łódź" "lodz"). */
object Fold {
    private val marks = Regex("\\p{M}+")

    fun of(text: String): String =
        marks.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "").replace('ł', 'l').replace('ø', 'o').replace('ß', 's').trim()
}

/** The kinds of items search knows (SPEC §12.1), with their words in both languages. */
enum class SearchKind(val words: List<String>) {
    VIDEOS(listOf("film", "filmy", "filmu", "filmow", "wideo", "video", "videos", "movie", "movies")),
    SCREENSHOTS(listOf("zrzut", "zrzuty", "zrzutow", "zrzuty ekranu", "zrzut ekranu", "screenshot", "screenshots")),
    MOTION(listOf("ruchome", "ruchomy", "ruchomych", "zdjecia ruchome", "zdjecie ruchome", "motion", "motion photo", "motion photos")),
    HDR(listOf("hdr")),
    RAW(listOf("raw", "dng")),
    FAVORITES(listOf("ulubione", "ulubiony", "ulubionych", "favorite", "favorites", "favourite", "favourites")),
}

/** One thing a part of the query can mean (SPEC §12.3). */
sealed interface SearchPart {
    data class Year(val year: Int) : SearchPart

    /** A month, of [year] or of any year. */
    data class Month(val month: Int, val year: Int?) : SearchPart

    data class Day(val date: LocalDate) : SearchPart

    /** A town, region or country by the place data's index. */
    data class Place(val level: PlaceLevel, val index: Int, val name: String) : SearchPart

    /** An album: a source's (`s:` + its folder key) or a user's (`u:` + its id). */
    data class Album(val key: String, val name: String) : SearchPart

    data class Kind(val kind: SearchKind) : SearchPart

    data class FileName(val text: String) : SearchPart
}

enum class PlaceLevel { TOWN, REGION, COUNTRY }

/** A name search can find – a place's or an album's in any of its spellings – and what it means. */
data class SearchName(val name: String, val part: SearchPart) {
    val folded: String = Fold.of(name)
}

/**
 * A part of the query (SPEC §12.4's chip): the words [from] until [to] of the query, and what they may mean – the
 * most frequent first – of which [chosen] counts.
 */
data class QueryPart(val from: Int, val to: Int, val words: String, val options: List<SearchPart>, val chosen: Int = 0) {
    val part: SearchPart get() = options[chosen]
}

/**
 * The query's parser (SPEC §12.3, LOGIKA §17.6): pure, so it is tested on the JVM; nothing it reads is stored.
 * Words are taken from the left, the longest run of words that means something first (up to four: "nowy jork");
 * what means nothing is a piece of a file name. Parts are joined by AND.
 */
class SearchParser(
    private val names: List<SearchName>,
    private val today: LocalDate,
    /** How many items a meaning holds, for its order among a word's meanings (SPEC §12.3: "Maj" a month and a village). */
    private val countOf: (SearchPart) -> Int = { 0 },
) {
    private val byFolded = names.groupBy { it.folded }

    fun parse(text: String): List<QueryPart> {
        val tokens = tokens(text)
        val parts = ArrayList<QueryPart>()
        var i = 0
        while (i < tokens.size) {
            var taken = false
            for (j in minOf(tokens.size, i + LONGEST) downTo i + 1) {
                val words = tokens.subList(i, j)
                val options = meanings(words.map { Fold.of(it) }, exactOnly = j - i > 1)
                if (options.isNotEmpty()) {
                    parts.add(QueryPart(i, j, words.joinToString(" "), options.sortedByDescending(countOf)))
                    i = j
                    taken = true
                    break
                }
            }
            if (!taken) {
                parts.add(QueryPart(i, i + 1, tokens[i], listOf(SearchPart.FileName(tokens[i]))))
                i++
            }
        }
        return parts
    }

    /**
     * What the word being typed may become (SPEC §12.2): names, months, years and kinds starting with it, the
     * most frequent first, at most [most]; places and dates before the rest.
     */
    fun suggest(text: String, most: Int = 8): List<SearchPart> {
        val last = tokens(text).lastOrNull()?.let(Fold::of) ?: return emptyList()
        if (last.isEmpty()) return emptyList()
        val found = LinkedHashSet<SearchPart>()
        found.addAll(meanings(listOf(last), exactOnly = false))
        for (name in names) if (name.folded.startsWith(last)) found.add(name.part)
        return found
            .sortedWith(compareBy<SearchPart> { rank(it) }.thenByDescending(countOf))
            .take(most)
    }

    /** What these words mean together; a lone word also by the start of a name (three letters at least). */
    private fun meanings(words: List<String>, exactOnly: Boolean): List<SearchPart> {
        val phrase = words.joinToString(" ")
        val found = LinkedHashSet<SearchPart>()
        dateOf(words)?.let(found::add)
        byFolded[phrase]?.forEach { found.add(it.part) }
        SearchKind.entries.filter { kind -> kind.words.any { it == phrase } }.forEach { found.add(SearchPart.Kind(it)) }
        if (!exactOnly && words.size == 1 && phrase.length >= PREFIX_LEAST) {
            SearchKind.entries.filter { kind -> kind.words.any { it.startsWith(phrase) } }.forEach { found.add(SearchPart.Kind(it)) }
            monthOf(phrase, prefix = true)?.let { found.add(SearchPart.Month(it, null)) }
            if (found.none { it is SearchPart.Place || it is SearchPart.Album }) {
                names.filter { it.folded.startsWith(phrase) }.forEach { found.add(it.part) }
            }
        }
        return found.toList()
    }

    /** A date in these words: a year, a month (with its year), today or yesterday, a written date. */
    private fun dateOf(words: List<String>): SearchPart? {
        if (words.size == 2) {
            val month = monthOf(words[0], prefix = false) ?: return null
            val year = words[1].toIntOrNull()?.takeIf { it in YEARS } ?: return null
            return SearchPart.Month(month, year)
        }
        if (words.size != 1) return null
        val word = words[0]
        word.toIntOrNull()?.takeIf { it in YEARS && word.length == 4 }?.let { return SearchPart.Year(it) }
        when (word) {
            "dzis", "dzisiaj", "today" -> return SearchPart.Day(today)
            "wczoraj", "yesterday" -> return SearchPart.Day(today.minusDays(1))
        }
        monthOf(word, prefix = false)?.let { return SearchPart.Month(it, null) }
        Regex("(\\d{1,2})[./-](\\d{1,2})(?:[./-](\\d{4}))?").matchEntire(word)?.let { m ->
            val (d, mo, y) = m.destructured
            return runCatching { SearchPart.Day(LocalDate.of(y.toIntOrNull() ?: today.year, mo.toInt(), d.toInt())) }.getOrNull()
        }
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matchEntire(word)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { SearchPart.Day(LocalDate.of(y.toInt(), mo.toInt(), d.toInt())) }.getOrNull()
        }
        return null
    }

    private fun rank(part: SearchPart): Int = when (part) {
        is SearchPart.Place -> 0
        is SearchPart.Day, is SearchPart.Month, is SearchPart.Year -> 1
        is SearchPart.Album -> 2
        is SearchPart.Kind -> 3
        is SearchPart.FileName -> 4
    }

    companion object {
        private const val LONGEST = 4
        private const val PREFIX_LEAST = 3
        private val YEARS = 1900..2100

        /** The query's words, as typed (the chips show them so). */
        fun tokens(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /**
         * A month by its name (1–12), Polish in every case and English, without diacritics; [prefix]: by its
         * start too (three letters at least).
         */
        fun monthOf(word: String, prefix: Boolean): Int? {
            MONTHS.forEachIndexed { index, forms -> if (word in forms) return index + 1 }
            if (prefix && word.length >= PREFIX_LEAST) MONTHS.forEachIndexed { index, forms -> if (forms.any { it.startsWith(word) }) return index + 1 }
            return null
        }

        private val MONTHS = listOf(
            listOf("styczen", "stycznia", "styczniu", "styczniem", "january", "jan"),
            listOf("luty", "lutego", "lutym", "february", "feb"),
            listOf("marzec", "marca", "marcu", "marcem", "march", "mar"),
            listOf("kwiecien", "kwietnia", "kwietniu", "kwietniem", "april", "apr"),
            listOf("maj", "maja", "maju", "majem", "may"),
            listOf("czerwiec", "czerwca", "czerwcu", "czerwcem", "june", "jun"),
            listOf("lipiec", "lipca", "lipcu", "lipcem", "july", "jul"),
            listOf("sierpien", "sierpnia", "sierpniu", "sierpniem", "august", "aug"),
            listOf("wrzesien", "wrzesnia", "wrzesniu", "wrzesniem", "september", "sep", "sept"),
            listOf("pazdziernik", "pazdziernika", "pazdzierniku", "pazdziernikiem", "october", "oct"),
            listOf("listopad", "listopada", "listopadzie", "listopadem", "november", "nov"),
            listOf("grudzien", "grudnia", "grudniu", "grudniem", "december", "dec"),
        )
    }
}
