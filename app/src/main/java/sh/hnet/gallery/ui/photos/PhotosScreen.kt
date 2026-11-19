@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.photos

import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.gallery.R
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.GridFastScroller
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.MediaAccess
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.rememberMediaAccessAsk
import sh.hnet.gallery.ui.grid.MediaGrid
import sh.hnet.gallery.ui.grid.MediaSelectionToolbar
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.grid.formatDate
import sh.hnet.gallery.ui.grid.rememberSelection
import sh.hnet.gallery.ui.viewer.ViewerRequest

/**
 * The Photos tab (SPEC §4): the timeline of the camera, the screenshots and the albums switched on,
 * newest first, with the partial-access card first while access is partial, and its states.
 *
 * @param onChooseAlbums The empty timeline's button: Settings › Photos
 */
@Composable
fun PhotosScreen(onOpenSettings: () -> Unit, onChooseAlbums: () -> Unit, onOpen: (ViewerRequest) -> Unit, viewModel: PhotosViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val grid = rememberLazyGridState()
    val today = remember(state) { LocalDate.now() }
    val scope = rememberCoroutineScope()
    // Selecting (SPEC §5): the items shown, by id; what went away meanwhile leaves the selection
    val selection = rememberSelection()
    SelectionEffects(selection)
    val actions = LocalMediaActions.current
    val shownItems = remember(state, step) {
        (state as? PhotosState.Shown)?.timeline(step)?.entries.orEmpty().mapNotNull { (it as? TimelineEntry.Tile)?.item }
    }
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
                title = { Text(stringResource(R.string.label_nav_photos), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
                scrollBehavior = scrollBehavior,
            )
        }
        when (val current = state) {
            PhotosState.Loading -> Loading()
            PhotosState.NoAccess -> NoAccess(onAnswer = viewModel::recheck)
            is PhotosState.Shown -> {
                val timeline = current.timeline(step)
                if (timeline.entries.isEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        current.partial?.let { PartialAccessCard(it, onAnswer = viewModel::recheck) }
                        if (!current.anyMedia) {
                            TimelineEmptyState(R.drawable.ic_photo_camera, stringResource(R.string.msg_no_media), stringResource(R.string.desc_no_media))
                        } else {
                            TimelineEmptyState(R.drawable.ic_photo_library, stringResource(R.string.msg_timeline_empty), stringResource(R.string.desc_timeline_empty, current.otherAlbums)) {
                                FilledTonalButton(onClick = onChooseAlbums) {
                                    Icon(painterResource(R.drawable.ic_tune), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.button_choose_folders))
                                }
                            }
                        }
                    }
                } else {
                    val locale = LocalConfiguration.current.locales[0]
                    val topCount = if (current.partial != null) 1 else 0
                    val entries = timeline.entries
                    val monthYear = stringResource(R.string.format_month_year)
                    Box(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
                        MediaGrid(
                            current.days,
                            current.months,
                            step,
                            onStepChange = viewModel::setStep,
                            facts = facts,
                            state = grid,
                            today = today,
                            top = {
                                current.partial?.let { count -> item(key = "card:partial", span = { GridItemSpan(maxLineSpan) }) { PartialAccessCard(count, onAnswer = viewModel::recheck) } }
                            },
                            topCount = topCount,
                            selection = selection,
                            bottomPadding = if (selection.active) 96.dp else 0.dp,
                            onOpen = { item ->
                                val ids = timeline.entries.mapNotNull { (it as? TimelineEntry.Tile)?.item?.id }
                                onOpen(ViewerRequest(ids, item.id) { id ->
                                    // The grid under the viewer follows the item shown, so the flight back lands on its tile
                                    val index = timeline.indexOf[id] ?: return@ViewerRequest
                                    val target = index + topCount
                                    val visible = grid.layoutInfo.visibleItemsInfo
                                    if (visible.none { it.index == target }) scope.launch { grid.scrollToItem(target) }
                                })
                            },
                        )
                        // The bubble shows the month and year there; the month view's headers already say the month, so the year alone (SPEC §4.6)
                        GridFastScroller(grid, entries.size + topCount, label = { index ->
                            val month = entries[(index - topCount).coerceIn(0, entries.lastIndex)].month
                            if (timeline.months) month.year.toString() else formatDate(monthYear, month.atDay(1), locale)
                        }, modifier = Modifier.align(Alignment.TopEnd))
                        if (selection.active) {
                            val chosen = shownItems.filter { it.id in selection.selected }
                            MediaSelectionToolbar(
                                chosen,
                                // Both end the selection once done (owner's test of S4a, over SPEC §5.4: as in the family's other apps)
                                onFavorite = { favorite -> actions.favorite(chosen, favorite, say = true) { selection.clear() } },
                                onDelete = { actions.trash(chosen) { selection.clear() } },
                                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The loading indicator, only once loading takes a moment (SPEC §4.7: over 300 ms). */
@Composable
private fun Loading() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        shown = true
    }
    if (shown) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

/** An empty state of the timeline (SPEC §4.7): the family's shape with its icon, the sentence, a supporting line and at most one button. */
@Composable
private fun TimelineEmptyState(@DrawableRes icon: Int, text: String, supporting: String, button: (@Composable () -> Unit)? = null) {
    EmptyState(icon, text) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 296.dp)) {
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            button?.invoke()
        }
    }
}

/**
 * No access (SPEC §4.7): the button asks while the system still shows its dialog, then leads to
 * the app's page in the system settings; the timeline loads by itself once access is given.
 */
@Composable
internal fun NoAccess(onAnswer: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val ask = rememberMediaAccessAsk(onAnswer)
    var mayAsk by remember { mutableStateOf(MediaAccess.mayAsk(context, activity)) }
    LifecycleResumeEffect(Unit) {
        mayAsk = MediaAccess.mayAsk(context, activity)
        onPauseOrDispose {}
    }
    TimelineEmptyState(R.drawable.ic_no_photography, stringResource(R.string.msg_no_access), stringResource(R.string.desc_no_access)) {
        if (mayAsk) {
            Button(onClick = ask) { Text(stringResource(R.string.button_allow_access)) }
        } else {
            Button(onClick = { MediaAccess.openAppSettings(context) }) { Text(stringResource(R.string.button_open_app_settings)) }
        }
    }
}

/**
 * The partial-access card (SPEC §4.7), first in the list while access is partial; not dismissible.
 * Both buttons ask again: the system shows its selection, where "Allow all" is the user's to choose.
 *
 * @param count The items the app may see
 */
@Composable
internal fun PartialAccessCard(count: Int, onAnswer: () -> Unit, sides: Dp = 16.dp) {
    val ask = rememberMediaAccessAsk(onAnswer)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = sides, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.title_partial_access), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.desc_partial_access, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = ask) { Text(stringResource(R.string.button_choose_more)) }
            FilledTonalButton(onClick = ask) { Text(stringResource(R.string.button_allow_all)) }
        }
    }
}
