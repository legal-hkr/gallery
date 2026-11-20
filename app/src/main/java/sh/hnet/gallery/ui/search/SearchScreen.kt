@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package sh.hnet.gallery.ui.search

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch
import sh.hnet.gallery.R
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PlaceLevel
import sh.hnet.gallery.model.QueryPart
import sh.hnet.gallery.model.SearchKind
import sh.hnet.gallery.model.SearchPart
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.ui.albums.AddToAlbumSheet
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.GridFastScroller
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.groupName
import sh.hnet.gallery.ui.components.placeData
import sh.hnet.gallery.ui.components.placeLanguage
import sh.hnet.gallery.ui.grid.MediaGrid
import sh.hnet.gallery.ui.grid.MediaSelectionToolbar
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionState
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.grid.formatDate
import sh.hnet.gallery.ui.grid.rememberSelection
import sh.hnet.gallery.ui.places.TownRow
import sh.hnet.gallery.ui.viewer.ViewerRequest

/**
 * The Search tab (SPEC §12, LOGIKA §17.6), the field as Music's: before typing, Places (the towns with the most
 * items), Types and Months, each a query at a tap; while typing, what the word may become; once sent, the
 * query's parts as chips over the results' grid with day headers with the year. Nothing is stored.
 */
@Composable
fun SearchScreen(onOpen: (ViewerRequest) -> Unit, viewModel: SearchViewModel = viewModel()) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val locale = LocalConfiguration.current.locales[0]
    val selection = rememberSelection()
    SelectionEffects(selection)
    val actions = LocalMediaActions.current
    var adding by remember { mutableStateOf<List<MediaItem>?>(null) }
    adding?.let { items ->
        AddToAlbumSheet(items, onDismiss = { adding = null }, onAdded = {
            adding = null
            selection.clear()
        })
    }
    val shown = results?.takeIf { it.text == text.trim() }
    val shownItems = remember(shown, step) { shown?.timeline(step)?.entries.orEmpty().mapNotNull { (it as? TimelineEntry.Tile)?.item } }
    val allIds = remember(shownItems) { shownItems.map { it.id } }
    LaunchedEffect(allIds) {
        val present = allIds.toHashSet()
        if (selection.selected.any { it !in present }) selection.selected = selection.selected.filterTo(HashSet()) { it in present }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (selection.active) {
            SelectionTopBar(selection, allIds)
        } else {
            // The input alone, as Music's: a collapsed SearchBar takes no typing, so the field carries the bar's container
            SearchBarDefaults.InputField(
                textFieldState = viewModel.field,
                searchBarState = rememberSearchBarState(),
                onSearch = {
                    viewModel.send()
                    keyboard?.hide()
                },
                placeholder = { Text(stringResource(R.string.hint_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = if (text.isNotEmpty()) {
                    { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear_search), viewModel::clear) }
                } else null,
                colors = SearchBarDefaults.inputFieldColors(
                    focusedContainerColor = SearchBarDefaults.colors().containerColor,
                    unfocusedContainerColor = SearchBarDefaults.colors().containerColor,
                    disabledContainerColor = SearchBarDefaults.colors().containerColor,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
        }
        when {
            shown != null -> Results(shown, step, facts, selection, onOpen, viewModel, locale) { chosen ->
                MediaSelectionToolbar(
                    chosen,
                    onFavorite = { favorite -> actions.favorite(chosen, favorite, say = true) { selection.clear() } },
                    onShare = { actions.share(chosen) { selection.clear() } },
                    onAddToAlbum = { adding = chosen },
                    onDelete = { actions.trash(chosen) { selection.clear() } },
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp),
                )
            }
            text.isBlank() -> home?.let { Home(it, viewModel, locale) }
            else -> Suggestions(text, suggestions, viewModel, locale) {
                keyboard?.hide()
            }
        }
    }
}

/** Before typing (SPEC §12.1). */
@Composable
private fun Home(home: SearchHome, viewModel: SearchViewModel, locale: Locale) {
    val data = placeData()
    val language = placeLanguage()
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (data != null && home.towns.isNotEmpty()) {
            item(key = "places") {
                // No Map button here (owner's test of S6c: it made no sense in search); the map is Places' card
                Header(stringResource(R.string.header_places), Modifier.padding(start = 16.dp))
                TownRow(home.towns, data, language) { key -> viewModel.run(data.groupName(key, language)) }
            }
        }
        if (home.kinds.isNotEmpty()) {
            item(key = "kinds") {
                Header(stringResource(R.string.header_kinds), Modifier.padding(start = 16.dp, top = 16.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in home.kinds) {
                        val label = stringResource(kind.label)
                        FilterChip(selected = false, onClick = { viewModel.run(label) }, label = { Text(label) }, leadingIcon = { Icon(painterResource(kind.icon), contentDescription = null, modifier = Modifier.size(18.dp)) })
                    }
                }
            }
        }
        if (home.months.isNotEmpty()) {
            item(key = "months") {
                Header(stringResource(R.string.header_months), Modifier.padding(start = 16.dp, top = 16.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pattern = stringResource(R.string.format_month)
                    val patternYear = stringResource(R.string.format_month_year)
                    val thisYear = LocalDate.now().year
                    for (month in home.months) {
                        val label = formatDate(if (month.year == thisYear) pattern else patternYear, month.atDay(1), locale)
                        // The query names the year always, so the month of this year alone is found
                        FilterChip(selected = false, onClick = { viewModel.run(monthQuery(month, locale)) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

/** While typing (SPEC §12.2): at most 8 rows, places and dates first; the typed word as a file name last. */
@Composable
private fun Suggestions(text: String, suggestions: List<Pair<SearchPart, Int>>, viewModel: SearchViewModel, locale: Locale, onSent: () -> Unit) {
    val last = text.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(suggestions, key = { it.first.toString() }) { (part, count) ->
            // A type in the app's words, so the field reads as typed ("Filmy", not "videos")
            val words = if (part is SearchPart.Kind) stringResource(part.kind.label) else wordsOf(part, locale)
            SuggestionRow(iconOf(part), labelOf(part, locale), supportingOf(part, count), onFill = { viewModel.complete(words, send = false) }) {
                viewModel.complete(words, send = true)
                onSent()
            }
        }
        if (last.isNotEmpty()) {
            item(key = "file") {
                SuggestionRow(R.drawable.ic_description, last, stringResource(R.string.suggest_file_name), onFill = null) {
                    viewModel.send()
                    onSent()
                }
            }
        }
    }
}

/** A suggestion (SPEC §12.2): 64 dp, its icon, the match, what it is with its count, the arrow that puts it in the field. */
@Composable
private fun SuggestionRow(@DrawableRes icon: Int, title: String, supporting: String, onFill: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onFill != null) TooltipIconButton(R.drawable.ic_north_west, stringResource(R.string.content_description_fill_query), onFill)
    }
}

/** A sent query (SPEC §12.4): its parts as chips – another meaning from a chip's menu, close removes it –, the count, the grid. */
@Composable
private fun Results(
    results: SearchResults,
    step: GridStep,
    facts: Map<Long, Fact>,
    selection: SelectionState,
    onOpen: (ViewerRequest) -> Unit,
    viewModel: SearchViewModel,
    locale: Locale,
    toolbar: @Composable (List<MediaItem>) -> Unit,
) {
    if (!selection.active) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (part in results.parts) PartChip(part, viewModel, locale)
        }
    }
    if (results.items.isEmpty()) {
        EmptyState(R.drawable.ic_search_off, stringResource(R.string.msg_no_results, results.text), Modifier.imePadding()) {
            Text(stringResource(R.string.desc_no_results), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    Text(stringResource(R.string.label_results, results.items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val today = remember(results) { LocalDate.now() }
    val timeline = results.timeline(step)
    val entries = timeline.entries
    val monthYear = stringResource(R.string.format_month_year)
    Box(Modifier.fillMaxSize()) {
        MediaGrid(
            results.days, results.months, step,
            onStepChange = viewModel::setStep,
            facts = facts,
            state = grid,
            today = today,
            selection = selection,
            bottomPadding = if (selection.active) 96.dp else 0.dp,
            yearInDays = true,
            onOpen = { item ->
                val ids = entries.mapNotNull { (it as? TimelineEntry.Tile)?.item?.id }
                onOpen(ViewerRequest(ids, item.id) { id ->
                    val index = timeline.indexOf[id] ?: return@ViewerRequest
                    if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) scope.launch { grid.scrollToItem(index) }
                })
            },
        )
        GridFastScroller(grid, entries.size, label = { index ->
            val month = entries[index.coerceIn(0, entries.lastIndex)].month
            if (timeline.months) month.year.toString() else formatDate(monthYear, month.atDay(1), locale)
        }, modifier = Modifier.align(Alignment.TopEnd))
        if (selection.active) {
            Box(Modifier.align(Alignment.BottomCenter)) { toolbar(results.items.filter { it.id in selection.selected }) }
        }
    }
}

/** A part's chip: selected, its kind's icon, close; a part of several meanings opens their menu (SPEC §12.3). */
@Composable
private fun PartChip(part: QueryPart, viewModel: SearchViewModel, locale: Locale) {
    var open by remember { mutableStateOf(false) }
    val label = labelOf(part.part, locale)
    Box {
        InputChip(
            selected = true,
            onClick = { if (part.options.size > 1) open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Icon(painterResource(iconOf(part.part)), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
            trailingIcon = {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.content_description_remove_part, label),
                    modifier = Modifier.size(InputChipDefaults.IconSize).clickable { viewModel.remove(part) },
                )
            },
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                part.options.forEachIndexed { index, option ->
                    SelectableDropdownMenuItem(
                        selected = index == part.chosen,
                        onClick = {
                            open = false
                            viewModel.choose(part, index)
                        },
                        text = { Text(labelOf(option, locale), maxLines = 1) },
                        supportingText = { Text(stringResource(kindOf(option))) },
                        shapes = MenuDefaults.itemShape(index, part.options.size),
                        leadingIcon = { Icon(painterResource(iconOf(option)), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingContent = { if (index == part.chosen) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 12.dp, bottom = 8.dp).semantics { heading() })
}

/** A part as words the parser reads back (a suggestion put into the field). */
private fun wordsOf(part: SearchPart, locale: Locale): String = when (part) {
    is SearchPart.Place -> part.name
    is SearchPart.Album -> part.name
    is SearchPart.Year -> part.year.toString()
    is SearchPart.Month -> if (part.year != null) monthQuery(YearMonth.of(part.year, part.month), locale) else DateTimeFormatter.ofPattern("LLLL", locale).format(YearMonth.of(2000, part.month))
    is SearchPart.Day -> "${part.date.dayOfMonth}.${part.date.monthValue}.${part.date.year}"
    is SearchPart.Kind -> part.kind.name.lowercase()
    is SearchPart.FileName -> part.text
}

/** A month and year in words the parser reads ("lipiec 2025", "July 2025"). */
private fun monthQuery(month: YearMonth, locale: Locale): String = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)

@Composable
private fun labelOf(part: SearchPart, locale: Locale): String = when (part) {
    is SearchPart.Place -> part.name
    is SearchPart.Album -> part.name
    is SearchPart.Year -> part.year.toString()
    is SearchPart.Month -> formatDate(stringResource(if (part.year != null) R.string.format_month_year else R.string.format_month), LocalDate.of(part.year ?: 2000, part.month, 1), locale)
    is SearchPart.Day -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(part.date)
    is SearchPart.Kind -> stringResource(part.kind.label)
    is SearchPart.FileName -> part.text
}

@Composable
private fun supportingOf(part: SearchPart, count: Int): String = when (part) {
    is SearchPart.Place -> when (part.level) {
        PlaceLevel.TOWN -> stringResource(R.string.suggest_place, stringResource(R.string.label_town), count)
        PlaceLevel.REGION -> stringResource(R.string.suggest_place, stringResource(R.string.label_region), count)
        PlaceLevel.COUNTRY -> stringResource(R.string.suggest_place, stringResource(R.string.label_country), count)
    }
    is SearchPart.Album -> stringResource(R.string.suggest_album, count)
    is SearchPart.Year -> stringResource(R.string.suggest_year, count)
    is SearchPart.Month -> stringResource(R.string.suggest_month, count)
    is SearchPart.Day -> stringResource(R.string.suggest_day, count)
    is SearchPart.Kind -> stringResource(R.string.suggest_kind, count)
    is SearchPart.FileName -> stringResource(R.string.suggest_file_name)
}

/** What a meaning is, for the chip's menu of meanings. */
private fun kindOf(part: SearchPart): Int = when (part) {
    is SearchPart.Place -> when (part.level) {
        PlaceLevel.TOWN -> R.string.label_town
        PlaceLevel.REGION -> R.string.label_region
        PlaceLevel.COUNTRY -> R.string.label_country
    }
    is SearchPart.Album -> R.string.label_album
    is SearchPart.Year, is SearchPart.Month, is SearchPart.Day -> R.string.label_date
    is SearchPart.Kind -> R.string.header_kinds
    is SearchPart.FileName -> R.string.suggest_file_name
}

@DrawableRes
private fun iconOf(part: SearchPart): Int = when (part) {
    is SearchPart.Place -> R.drawable.ic_location_on
    is SearchPart.Album -> R.drawable.ic_photo_album
    is SearchPart.Year, is SearchPart.Month -> R.drawable.ic_calendar_month
    is SearchPart.Day -> R.drawable.ic_today
    is SearchPart.Kind -> part.kind.icon
    is SearchPart.FileName -> R.drawable.ic_description
}

private val SearchKind.label: Int
    get() = when (this) {
        SearchKind.VIDEOS -> R.string.kind_videos
        SearchKind.SCREENSHOTS -> R.string.kind_screenshots
        SearchKind.MOTION -> R.string.kind_motion_photos
        SearchKind.HDR -> R.string.kind_hdr
        SearchKind.RAW -> R.string.kind_raw
        SearchKind.FAVORITES -> R.string.kind_favorites
    }

private val SearchKind.icon: Int
    get() = when (this) {
        SearchKind.VIDEOS -> R.drawable.ic_movie
        SearchKind.SCREENSHOTS -> R.drawable.ic_screenshot_monitor
        SearchKind.MOTION -> R.drawable.ic_motion_photos_on
        SearchKind.HDR -> R.drawable.ic_hdr_on
        SearchKind.RAW -> R.drawable.ic_raw_on
        SearchKind.FAVORITES -> R.drawable.ic_favorite
    }
