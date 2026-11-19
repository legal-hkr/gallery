@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.albums

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.launch
import sh.hnet.gallery.R
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.SourceState
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.GridFastScroller
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.MenuItem
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.components.albumPath
import sh.hnet.gallery.ui.grid.MediaGrid
import sh.hnet.gallery.ui.grid.MediaSelectionToolbar
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.grid.formatDate
import sh.hnet.gallery.ui.grid.rememberSelection
import sh.hnet.gallery.ui.settings.icon
import sh.hnet.gallery.ui.viewer.ViewerRequest

/**
 * A grid page of the Albums tab: a collection (SPEC §9.2; Favorites, Videos, Screenshots) or a source's
 * album (SPEC-albums §9.10). The timeline's grid (the same steps and pinch, selecting as in Photos) over
 * its items, with a sort chip – newest or oldest first, a source also by file name (a plain grid) – and,
 * for a source and Screenshots, the state chip. A source's ⋮ has Pin and Hide folder; hiding, or the folder
 * emptied, closes the page. Slideshow is S8's (disabled, D5).
 */
@Composable
fun AlbumPage(key: PageKey, onBack: () -> Unit, onOpen: (ViewerRequest) -> Unit, viewModel: PageViewModel = viewModel(key = key.toString())) {
    LaunchedEffect(key) { viewModel.show(key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val today = remember(state) { LocalDate.now() }
    val scope = rememberCoroutineScope()
    val selection = rememberSelection()
    SelectionEffects(selection)
    val actions = LocalMediaActions.current
    LaunchedEffect(state) { if (state == PageState.Gone) onBack() }
    val shown = state as? PageState.Shown
    val shownItems = remember(shown, step) { shown?.timeline(step)?.entries.orEmpty().mapNotNull { (it as? TimelineEntry.Tile)?.item } }
    val allIds = remember(shownItems) { shownItems.map { it.id } }
    LaunchedEffect(allIds) {
        val present = allIds.toHashSet()
        if (selection.selected.any { it !in present }) selection.selected = selection.selected.filterTo(HashSet()) { it in present }
    }
    val folder = (key as? PageKey.Folder)?.folder
    val title = when (key) {
        is PageKey.Collection -> stringResource(key.kind.title)
        is PageKey.Folder -> albumName(key.folder)
    }
    val screenshots = key == PageKey.Collection(CollectionKind.SCREENSHOTS)

    // Changing the state says so with Undo; hiding leaves the page (SPEC-albums §9.10, §9.11)
    val inPhotos = stringResource(R.string.msg_source_in_photos, title)
    val albumOnly = stringResource(R.string.msg_source_album_only, title)
    val hidden = if (screenshots) stringResource(R.string.msg_folders_hidden, shown?.folders?.size ?: 0) else stringResource(R.string.msg_folder_hidden, title)
    fun choose(chosen: SourceState) {
        val folders = shown?.folders.orEmpty()
        if (folders.isEmpty()) return
        scope.launch {
            val before = viewModel.choose(folders, chosen)
            if (chosen == SourceState.HIDDEN) onBack()
            actions.say(
                when (chosen) {
                    SourceState.IN_PHOTOS -> inPhotos
                    SourceState.ALBUM_ONLY -> albumOnly
                    SourceState.HIDDEN -> hidden
                },
            ) { viewModel.undo(before) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionTopBar(selection, allIds)
        } else {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        val subtitle = when {
                            folder != null -> albumPath(folder)
                            screenshots && shown != null && shown.folders.isNotEmpty() -> stringResource(R.string.label_folders_count, shown.folders.size)
                            else -> null
                        }
                        // A long path scrolls sideways, never wraps (SPEC-albums §9.10)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                },
                actions = {
                    TooltipIconButton(R.drawable.ic_slideshow, stringResource(R.string.action_slideshow), {}, enabled = false)
                    if (folder != null && shown != null) {
                        Box {
                            TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.action_more), { menu = true })
                            DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                                    MenuItem(stringResource(if (shown.pinned) R.string.menu_unpin else R.string.menu_pin), R.drawable.ic_keep, 0, 1) {
                                        menu = false
                                        viewModel.setPinned(folder, !shown.pinned)
                                    }
                                }
                                Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                                DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                                    MenuItem(stringResource(R.string.action_hide_folder), R.drawable.ic_visibility_off, 0, 1) {
                                        menu = false
                                        choose(SourceState.HIDDEN)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
        if (shown == null) return@Column
        if (allIds.isEmpty()) {
            if (key is PageKey.Collection) EmptyState(key.kind.icon, stringResource(key.kind.empty))
            return@Column
        }
        if (!selection.active) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.state?.let { current -> StateChip(current, screenshots, ::choose) }
                SortChip(shown.sort, if (folder != null) PageSort.entries else listOf(PageSort.NEWEST, PageSort.OLDEST), viewModel::setSort)
            }
        }
        val timeline = shown.timeline(step)
        val entries = timeline.entries
        val locale = LocalConfiguration.current.locales[0]
        val monthYear = stringResource(R.string.format_month_year)
        Box(Modifier.fillMaxSize()) {
            MediaGrid(
                shown.days,
                shown.months,
                step,
                onStepChange = viewModel::setStep,
                facts = facts,
                state = grid,
                today = today,
                selection = selection,
                bottomPadding = if (selection.active) 96.dp else 0.dp,
                onOpen = { item ->
                    onOpen(ViewerRequest(allIds, item.id) { id ->
                        // The grid under the viewer follows the item shown, so the flight back lands on its tile
                        val index = timeline.indexOf[id] ?: return@ViewerRequest
                        if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) scope.launch { grid.scrollToItem(index) }
                    })
                },
            )
            GridFastScroller(grid, entries.size, label = { index ->
                val entry = entries[index.coerceIn(0, entries.lastIndex)]
                when {
                    // In name order the bubble shows the name's first letter
                    shown.sort == PageSort.FILE_NAME -> (entry as? TimelineEntry.Tile)?.item?.name?.firstOrNull()?.uppercase(locale).orEmpty()
                    timeline.months -> entry.month.year.toString()
                    else -> formatDate(monthYear, entry.month.atDay(1), locale)
                }
            }, modifier = Modifier.align(Alignment.TopEnd))
            if (selection.active) {
                val chosen = shownItems.filter { it.id in selection.selected }
                MediaSelectionToolbar(
                    chosen,
                    onFavorite = { favorite -> actions.favorite(chosen, favorite, say = true) { selection.clear() } },
                    onDelete = { actions.trash(chosen) { selection.clear() } },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                )
            }
        }
    }
}

/**
 * The state chip (SPEC-albums §9.10): selected, its state's icon, a menu of In Photos and Album only with what
 * each means, the current one checked, and apart Hide folder – Hide screenshots on the Screenshots page.
 */
@Composable
private fun StateChip(state: SourceState, screenshots: Boolean, onChoose: (SourceState) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true,
            onClick = { open = true },
            label = { Text(stringResource(if (state == SourceState.IN_PHOTOS) R.string.state_in_photos else R.string.state_album_only), maxLines = 1) },
            leadingIcon = { Icon(painterResource(if (state == SourceState.IN_PHOTOS) R.drawable.ic_photo_library_filled else R.drawable.ic_photo_album_filled), contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                listOf(SourceState.IN_PHOTOS to R.string.desc_state_in_photos, SourceState.ALBUM_ONLY to R.string.desc_state_album_only).forEachIndexed { index, (option, desc) ->
                    SelectableDropdownMenuItem(
                        selected = option == state,
                        onClick = {
                            open = false
                            if (option != state) onChoose(option)
                        },
                        text = { Text(stringResource(if (option == SourceState.IN_PHOTOS) R.string.state_in_photos else R.string.state_album_only), maxLines = 1) },
                        supportingText = { Text(stringResource(desc)) },
                        shapes = MenuDefaults.itemShape(index, 2),
                        leadingIcon = { Icon(painterResource(option.icon()), contentDescription = null) },
                        trailingContent = { if (option == state) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                // The expressive item with a supporting line, never selected
                SelectableDropdownMenuItem(
                    selected = false,
                    onClick = {
                        open = false
                        onChoose(SourceState.HIDDEN)
                    },
                    text = { Text(stringResource(if (screenshots) R.string.action_hide_screenshots else R.string.action_hide_folder), maxLines = 1) },
                    supportingText = { Text(stringResource(if (screenshots) R.string.desc_hide_screenshots else R.string.desc_hide_folder)) },
                    shapes = MenuDefaults.itemShape(0, 1),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = null) },
                )
            }
        }
    }
}

/** The sort chip (SPEC-albums §9.7, §9.10): the current order, the others from a menu. */
@Composable
private fun SortChip(sort: PageSort, options: List<PageSort>, onChange: (PageSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(of: PageSort) = when (of) {
        PageSort.NEWEST -> R.string.sort_newest
        PageSort.OLDEST -> R.string.sort_oldest
        PageSort.FILE_NAME -> R.string.sort_file_name
    }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(stringResource(label(sort)), maxLines = 1) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                options.forEachIndexed { index, option ->
                    SelectableDropdownMenuItem(
                        selected = option == sort,
                        onClick = {
                            open = false
                            onChange(option)
                        },
                        text = { Text(stringResource(label(option)), maxLines = 1) },
                        shapes = MenuDefaults.itemShape(index, options.size),
                        trailingContent = { if (option == sort) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}

/** What an empty collection says (mine: the SPEC has no empty state for these). */
internal val CollectionKind.empty: Int
    get() = when (this) {
        CollectionKind.FAVORITES -> R.string.msg_no_favorites
        CollectionKind.VIDEOS -> R.string.msg_no_videos
        CollectionKind.SCREENSHOTS -> R.string.msg_no_screenshots
    }
