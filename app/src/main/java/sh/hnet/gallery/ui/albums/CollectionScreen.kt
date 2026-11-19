@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.albums

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.GridFastScroller
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.grid.MediaGrid
import sh.hnet.gallery.ui.grid.MediaSelectionToolbar
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.grid.formatDate
import sh.hnet.gallery.ui.grid.rememberSelection
import sh.hnet.gallery.ui.viewer.ViewerRequest

/**
 * A collection's page (SPEC §9.2, S4a): Favorites, Videos or Screenshots – the timeline's grid (the
 * same steps and pinch, selecting as in Photos) over the collection, with a sort chip, newest or oldest
 * first, remembered per collection (LOGIKA §15.11). Screenshots' subtitle counts its folders; its state
 * chip comes with S4b. Slideshow is S8's (disabled, D5).
 */
@Composable
fun CollectionScreen(kind: CollectionKind, onBack: () -> Unit, onOpen: (ViewerRequest) -> Unit, viewModel: CollectionViewModel = viewModel()) {
    LaunchedEffect(kind) { viewModel.show(kind) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val today = remember(state) { LocalDate.now() }
    val scope = rememberCoroutineScope()
    val selection = rememberSelection()
    SelectionEffects(selection)
    val actions = LocalMediaActions.current
    val shown = state as? CollectionState.Shown
    val shownItems = remember(shown, step) { shown?.timeline(step)?.entries.orEmpty().mapNotNull { (it as? TimelineEntry.Tile)?.item } }
    val allIds = remember(shownItems) { shownItems.map { it.id } }
    LaunchedEffect(allIds) {
        val present = allIds.toHashSet()
        if (selection.selected.any { it !in present }) selection.selected = selection.selected.filterTo(HashSet()) { it in present }
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionTopBar(selection, allIds)
        } else {
            TopAppBar(
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                title = {
                    Column {
                        Text(stringResource(kind.title), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        if (kind == CollectionKind.SCREENSHOTS && shown != null && shown.folders > 0) {
                            Text(stringResource(R.string.label_folders_count, shown.folders), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                actions = { TooltipIconButton(R.drawable.ic_slideshow, stringResource(R.string.action_slideshow), {}, enabled = false) },
            )
        }
        if (shown == null) return@Column
        if (allIds.isEmpty()) {
            EmptyState(kind.icon, stringResource(kind.empty))
            return@Column
        }
        if (!selection.active) SortChip(shown.oldestFirst, viewModel::setOldestFirst)
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
                val month = entries[index.coerceIn(0, entries.lastIndex)].month
                if (timeline.months) month.year.toString() else formatDate(monthYear, month.atDay(1), locale)
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

/** The sort chip (SPEC §9.4's for an album): Newest first or Oldest first, from a menu. */
@Composable
private fun SortChip(oldestFirst: Boolean, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(stringResource(if (oldestFirst) R.string.sort_oldest else R.string.sort_newest)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                listOf(false, true).forEachIndexed { index, oldest ->
                    SelectableDropdownMenuItem(
                        selected = oldest == oldestFirst,
                        onClick = {
                            open = false
                            onChange(oldest)
                        },
                        text = { Text(stringResource(if (oldest) R.string.sort_oldest else R.string.sort_newest), maxLines = 1) },
                        shapes = MenuDefaults.itemShape(index, 2),
                        trailingContent = { if (oldest == oldestFirst) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}

/** What an empty collection says (mine: the SPEC has no empty state for these). */
private val CollectionKind.empty: Int
    get() = when (this) {
        CollectionKind.FAVORITES -> R.string.msg_no_favorites
        CollectionKind.VIDEOS -> R.string.msg_no_videos
        CollectionKind.SCREENSHOTS -> R.string.msg_no_screenshots
    }
