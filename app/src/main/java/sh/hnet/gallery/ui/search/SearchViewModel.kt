package sh.hnet.gallery.ui.search

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.facts.AlbumItemRow
import sh.hnet.gallery.facts.AlbumRow
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.Fold
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PlaceData
import sh.hnet.gallery.model.PlaceGroups
import sh.hnet.gallery.model.PlaceLanguage
import sh.hnet.gallery.model.PlaceLevel
import sh.hnet.gallery.model.QueryPart
import sh.hnet.gallery.model.SearchKind
import sh.hnet.gallery.model.SearchName
import sh.hnet.gallery.model.SearchParser
import sh.hnet.gallery.model.SearchPart
import sh.hnet.gallery.model.Timeline
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.model.TownGroup

/**
 * What search searches (SPEC §12, LOGIKA §17.6): the library's items (not trashed, hidden sources left out), their
 * facts and places, the albums; the names of the places and albums there are, and how many items each meaning holds.
 */
class SearchContext(
    val items: List<MediaItem>,
    private val facts: Map<Long, Fact>,
    private val places: PlaceData?,
    val groups: PlaceGroups?,
    private val memberOf: Map<Long, Set<Long>>,
    val names: List<SearchName>,
) {
    private val counts = HashMap<SearchPart, Int>()
    private val zone = ZoneId.systemDefault()

    /** An item's day in the zone it was taken in, when the file says, else the phone's (as the timeline). */
    fun dayOf(item: MediaItem): LocalDate {
        val offset = facts[item.id]?.offsetMinutes
        val instant = Instant.ofEpochMilli(item.taken)
        return if (offset != null) instant.atOffset(ZoneOffset.ofTotalSeconds(offset * 60)).toLocalDate() else instant.atZone(zone).toLocalDate()
    }

    fun matches(item: MediaItem, part: SearchPart): Boolean = when (part) {
        is SearchPart.Year -> dayOf(item).year == part.year
        is SearchPart.Month -> dayOf(item).let { it.monthValue == part.month && (part.year == null || it.year == part.year) }
        is SearchPart.Day -> dayOf(item) == part.date
        is SearchPart.Place -> {
            val fact = facts[item.id]
            val data = places
            data != null && fact != null && fact.places == data.version && when (part.level) {
                PlaceLevel.TOWN -> fact.settlement == part.index
                PlaceLevel.REGION -> fact.region == part.index
                PlaceLevel.COUNTRY -> fact.country == part.index
            }
        }
        is SearchPart.Album -> if (part.key.startsWith("s:")) "s:" + item.folder.key == part.key else part.key.removePrefix("u:").toLongOrNull()?.let { it in memberOf[item.id].orEmpty() } == true
        is SearchPart.Kind -> when (part.kind) {
            SearchKind.VIDEOS -> item.video
            SearchKind.SCREENSHOTS -> TimelineFolders.isScreenshots(item.path)
            SearchKind.MOTION -> facts[item.id]?.motion == true
            SearchKind.HDR -> facts[item.id]?.gainmap == true
            SearchKind.RAW -> item.mime == "image/x-adobe-dng"
            SearchKind.FAVORITES -> item.favorite
        }
        is SearchPart.FileName -> Fold.of(item.name).contains(Fold.of(part.text))
    }

    @Synchronized
    fun countOf(part: SearchPart): Int = counts.getOrPut(part) { items.count { matches(it, part) } }

    fun parser(today: LocalDate): SearchParser = SearchParser(names, today, ::countOf)
}

/** A query run: its parts as chips, and what they find – the newest first – as a timeline (SPEC §12.4). */
class SearchResults(val text: String, val parts: List<QueryPart>, val items: List<MediaItem>, val days: Timeline, val months: Timeline) {
    fun timeline(step: GridStep): Timeline = if (step.months) months else days
}

/** Before typing (SPEC §12.1): the towns with the most items, the kinds the phone has, the last six months with items. */
class SearchHome(val towns: List<TownGroup>, val kinds: List<SearchKind>, val months: List<YearMonth>)

/**
 * Search (SPEC §12, LOGIKA §17.6): the field, what the word being typed may become (after 150 ms without typing),
 * and a query run when sent – by the keyboard, a suggestion, a chip. Nothing is ever stored: no recent queries
 * (family rule).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApplication

    val field = TextFieldState()

    val step: StateFlow<GridStep> = app.preferences.gridStep.stateIn(viewModelScope, SharingStarted.Eagerly, GridStep.DEFAULT)

    val facts: StateFlow<Map<Long, Fact>> = app.facts.facts.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The query sent, as typed; null while typing. */
    private val sent = MutableStateFlow<String?>(null)

    /** The meaning chosen for a part (its first word's place) other than the most frequent (SPEC §12.3). */
    private val chosen = MutableStateFlow<Map<Int, Int>>(emptyMap())

    private val albumRows = combine(app.albums.albums, app.albums.members) { albums, members -> albums to members }

    val context: StateFlow<SearchContext?> = combine(app.library, app.facts.facts, app.places, app.placeGroups, albumRows) { library, facts, places, groups, (albums, members) ->
        if (library == null || facts == null) null else build(library.visible.filter { !it.trashed }, facts, places, groups, albums.orEmpty(), members.orEmpty())
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val text: StateFlow<String> = snapshotFlow { field.text.toString() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val home: StateFlow<SearchHome?> = context.map { c ->
        c ?: return@map null
        val towns = c.groups?.countries?.flatMap { it.towns }?.filter { it.isTown }?.sortedByDescending { it.items.size }?.take(10).orEmpty()
        val kinds = SearchKind.entries.filter { c.countOf(SearchPart.Kind(it)) > 0 }
        val months = c.items.asSequence().map { YearMonth.from(c.dayOf(it)) }.distinct().sortedDescending().take(6).toList()
        SearchHome(towns, kinds, months)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What the word being typed may become, with how many items each holds (SPEC §12.2). */
    val suggestions: StateFlow<List<Pair<SearchPart, Int>>> = combine(text.debounce(150), context, sent) { typed, c, sent -> Triple(typed, c, sent) }
        .mapLatest { (typed, c, sent) ->
            if (c == null || typed.isBlank() || sent == typed) emptyList()
            else c.parser(LocalDate.now()).suggest(typed).map { it to c.countOf(it) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<SearchResults?> = combine(sent, chosen, context) { sent, chosen, c -> Triple(sent, chosen, c) }
        .mapLatest { (sent, chosen, c) ->
            if (sent == null || c == null) return@mapLatest null
            val parts = c.parser(LocalDate.now()).parse(sent).map { part -> chosen[part.from]?.takeIf { it in part.options.indices }?.let { part.copy(chosen = it) } ?: part }
            val found = c.items.filter { item -> parts.all { c.matches(item, it.part) } }
            val zone = ZoneId.systemDefault()
            val offsets = facts.value
            SearchResults(sent, parts, found, Timeline.build(found, months = false, zone) { offsets[it]?.offsetMinutes }, Timeline.build(found, months = true, zone) { offsets[it]?.offsetMinutes })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Runs the query in the field (the keyboard's search). */
    fun send() {
        val typed = field.text.toString().trim()
        if (typed.isEmpty()) return
        chosen.value = emptyMap()
        sent.value = typed
    }

    /** Runs [query] as the field's whole text (a town, a kind, a month tapped before typing). */
    fun run(query: String) {
        field.setTextAndPlaceCursorAtEnd(query)
        send()
    }

    /**
     * The word being typed replaced by [words]; [send]: run it (a suggestion's tap), else go on typing (its arrow,
     * SPEC §12.2).
     */
    fun complete(words: String, send: Boolean) {
        val tokens = SearchParser.tokens(field.text.toString())
        val text = (tokens.dropLast(1) + words).joinToString(" ")
        field.setTextAndPlaceCursorAtEnd(if (send) text else "$text ")
        if (send) send()
    }

    /** Another meaning of a part (SPEC §12.3: the user picks the other). */
    fun choose(part: QueryPart, option: Int) {
        chosen.value = chosen.value + (part.from to option)
    }

    /** A chip's close (SPEC §12.4): its words leave the query, which runs again – or ends when nothing's left. */
    fun remove(part: QueryPart) {
        val tokens = SearchParser.tokens(sent.value ?: field.text.toString())
        val rest = tokens.filterIndexed { i, _ -> i !in part.from until part.to }.joinToString(" ")
        field.setTextAndPlaceCursorAtEnd(rest)
        chosen.value = emptyMap()
        sent.value = rest.ifBlank { null }
    }

    fun clear() {
        field.setTextAndPlaceCursorAtEnd("")
        chosen.value = emptyMap()
        sent.value = null
    }

    fun setStep(step: GridStep) {
        viewModelScope.launch { app.preferences.setGridStep(step) }
    }

    /** The names search finds: the places and albums the library has, every spelling of each. */
    private fun build(items: List<MediaItem>, facts: Map<Long, Fact>, places: PlaceData?, groups: PlaceGroups?, albums: List<AlbumRow>, members: List<AlbumItemRow>): SearchContext {
        val names = ArrayList<SearchName>()
        val language = if (app.resources.configuration.locales[0].language == "pl") PlaceLanguage.POLISH else PlaceLanguage.ENGLISH
        if (places != null && groups != null) {
            for (country in groups.countries) {
                val countryPart = SearchPart.Place(PlaceLevel.COUNTRY, country.country, places.countryName(country.country, language))
                places.countryNames(country.country).forEach { names.add(SearchName(it, countryPart)) }
                for (region in country.regions) {
                    if (region.region < 0) continue
                    val regionPart = SearchPart.Place(PlaceLevel.REGION, region.region, places.regionName(region.region, language))
                    places.regionNames(region.region).forEach { names.add(SearchName(it, regionPart)) }
                }
                for (town in country.towns) {
                    // A region's or country's group is found by its region's or country's names already
                    if (!town.isTown) continue
                    val townPart = SearchPart.Place(PlaceLevel.TOWN, town.settlement, places.settlementName(town.settlement, language))
                    places.settlementNames(town.settlement).forEach { names.add(SearchName(it, townPart)) }
                }
            }
        }
        for (folder in items.mapTo(LinkedHashSet()) { it.folder }) {
            val name = albumNameOf(folder)
            names.add(SearchName(name, SearchPart.Album("s:" + folder.key, name)))
        }
        val shown = items.mapTo(HashSet()) { it.id }
        val memberOf = HashMap<Long, MutableSet<Long>>()
        for (row in members) if (row.mediaId in shown) memberOf.getOrPut(row.mediaId) { HashSet() }.add(row.albumId)
        for (album in albums) names.add(SearchName(album.name, SearchPart.Album("u:" + album.id, album.name)))
        return SearchContext(items, facts, places, groups, memberOf, names)
    }

    /** A source's album name, as the app shows it (ui/components/AlbumNames). */
    private fun albumNameOf(folder: FolderKey): String = when {
        folder.path.equals("DCIM/Camera/", ignoreCase = true) -> app.getString(R.string.label_camera)
        folder.path.equals("Download/", ignoreCase = true) -> app.getString(R.string.label_downloads)
        else -> folder.name
    }
}
