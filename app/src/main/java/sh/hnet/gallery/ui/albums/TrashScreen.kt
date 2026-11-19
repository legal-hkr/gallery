@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.gallery.R
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.TrashDays
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.MenuItem
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.grid.Mark
import sh.hnet.gallery.ui.grid.SelectableFrame
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.grid.TrashSelectionToolbar
import sh.hnet.gallery.ui.grid.rememberSelection
import sh.hnet.gallery.ui.viewer.ViewerRequest
import sh.hnet.gallery.ui.viewer.mediaOrigin

/** What a confirmation of deleting for good is for (SPEC §11.2). */
private sealed interface Forever {
    data class Items(val items: List<MediaItem>) : Forever

    data class Empty(val items: List<MediaItem>) : Forever
}

/**
 * Trash (SPEC §11): the family's info card first, then 3 fixed columns (no pinch steps), each tile with
 * the days left until the system deletes it for good. A tap opens the viewer in Trash's mode (Restore,
 * Info, Delete for good); selecting by long press and taps, with Restore and Delete for good. The menu:
 * Restore all, Empty Trash – hidden while Trash is empty. Deleting for good asks first; nothing here has Undo.
 */
@Composable
fun TrashScreen(onBack: () -> Unit, onOpen: (ViewerRequest) -> Unit, viewModel: TrashViewModel = viewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val selection = rememberSelection()
    SelectionEffects(selection)
    val actions = LocalMediaActions.current
    val haptics = LocalHapticFeedback.current
    val list = items.orEmpty()
    val allIds = remember(list) { list.map { it.id } }
    LaunchedEffect(allIds) {
        val present = allIds.toHashSet()
        if (selection.selected.any { it !in present }) selection.selected = selection.selected.filterTo(HashSet()) { it in present }
    }
    var asking by remember { mutableStateOf<Forever?>(null) }
    var menu by remember { mutableStateOf(false) }
    val now = remember(list) { System.currentTimeMillis() }
    val cellPx = with(LocalDensity.current) { 120.dp.roundToPx() }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionTopBar(selection, allIds)
        } else {
            TopAppBar(
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                title = { Text(stringResource(R.string.label_trash), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                actions = {
                    if (list.isNotEmpty()) {
                        Box {
                            TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.action_more), { menu = true })
                            DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                                    MenuItem(stringResource(R.string.menu_restore_all), R.drawable.ic_restore_from_trash, 0, 2) {
                                        menu = false
                                        actions.restore(list)
                                    }
                                    MenuItem(stringResource(R.string.menu_empty_trash), R.drawable.ic_delete_forever, 1, 2, error = true) {
                                        menu = false
                                        asking = Forever.Empty(list)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
        if (items == null) return@Column
        if (list.isEmpty()) {
            EmptyState(R.drawable.ic_delete, stringResource(R.string.msg_trash_empty)) {
                Text(
                    stringResource(R.string.desc_trash_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 296.dp),
                )
            }
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = if (selection.active) 96.dp else 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "card:note", span = { GridItemSpan(maxLineSpan) }) { TrashNote() }
                items(list, key = { it.id }) { item ->
                    TrashTile(
                        item,
                        TrashDays.left(item.expires, now),
                        cellPx,
                        selecting = selection.active,
                        selected = item.id in selection.selected,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selection.toggle(item.id)
                        },
                        onClick = {
                            if (selection.active) {
                                selection.toggle(item.id)
                            } else {
                                onOpen(ViewerRequest(allIds, item.id, trash = true) { id ->
                                    // Trash's grid follows the viewer: its first item is the note
                                    val index = allIds.indexOf(id) + 1
                                    if (index > 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) scope.launch { grid.scrollToItem(index) }
                                })
                            }
                        },
                    )
                }
            }
            if (selection.active) {
                val chosen = list.filter { it.id in selection.selected }
                TrashSelectionToolbar(
                    onRestore = { actions.restore(chosen) { selection.clear() } },
                    onDeleteForever = { asking = Forever.Items(chosen) },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                )
            }
        }
    }

    asking?.let { question ->
        val (title, text, confirm, chosen) = when (question) {
            is Forever.Items -> Quad(
                if (question.items.size == 1) stringResource(R.string.title_delete_forever_one) else stringResource(R.string.title_delete_forever, question.items.size),
                stringResource(R.string.desc_delete_forever),
                stringResource(R.string.button_delete_forever),
                question.items,
            )
            is Forever.Empty -> Quad(
                stringResource(R.string.title_empty_trash),
                stringResource(R.string.desc_empty_trash, question.items.size),
                stringResource(R.string.button_empty_trash),
                question.items,
            )
        }
        ConfirmDestructiveDialog(
            R.drawable.ic_delete_forever,
            title,
            AnnotatedString(text),
            confirm,
            onConfirm = {
                asking = null
                actions.deleteForever(chosen) { selection.clear() }
            },
            onDismiss = { asking = null },
        )
    }
}

private data class Quad(val title: String, val text: String, val confirm: String, val items: List<MediaItem>)

/** The family's info card (SPEC §11): the system deletes after 30 days; scrolls away with the grid. */
@Composable
private fun TrashNote() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null)
            Text(stringResource(R.string.desc_trash_note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Trash's tile (SPEC §11): as the timeline's, with the days left at the bottom start and no favorite mark. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashTile(item: MediaItem, daysLeft: Int, px: Int, selecting: Boolean, selected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    SelectableFrame(
        selecting,
        selected,
        6.dp,
        Modifier.aspectRatio(1f).mediaOrigin(item.id.toString(), 6.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        MediaThumbnail(item, px, Modifier.fillMaxSize())
        if (!selecting) {
            Mark(Modifier.align(Alignment.BottomStart)) {
                Text(
                    if (daysLeft == 0) stringResource(R.string.label_today_left) else pluralStringResource(R.plurals.label_days_left, daysLeft, daysLeft),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

