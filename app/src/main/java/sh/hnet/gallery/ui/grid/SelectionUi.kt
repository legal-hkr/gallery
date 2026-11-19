@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.ui.components.TooltipIconButton

/**
 * The items selected in one grid (SPEC §5), by MediaStore id. Selecting lasts while anything is
 * selected; it isn't kept when the tab changes (SPEC §5.1, as ComfyChair), so it lives in `remember`.
 */
@Stable
class SelectionState {
    var selected by mutableStateOf<Set<Long>>(emptySet())

    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clear() {
        selected = emptySet()
    }
}

@Composable
fun rememberSelection(): SelectionState = remember { SelectionState() }

/** Set by a tab's screen while it selects: the shell hides the navigation bar, the selection's toolbar takes its place (SPEC §5.1). */
val LocalHideNavigationBar = compositionLocalOf<MutableState<Boolean>?> { null }

/** Reports [selecting] to the shell, and Back ends the selection (SPEC §5.1). */
@Composable
fun SelectionEffects(selection: SelectionState) = SelectionEffects(selection.active, selection::clear)

/** [SelectionEffects] for a selection of other things than items (the Albums tab's albums, SPEC-albums §9.5). */
@Composable
fun SelectionEffects(active: Boolean, onClear: () -> Unit) {
    val hide = LocalHideNavigationBar.current
    DisposableEffect(hide, active) {
        hide?.value = active
        onDispose { hide?.value = false }
    }
    BackHandler(enabled = active, onBack = onClear)
}

/** The selection's top bar (SPEC §5.1, as Contacts'): Close, "Selected: 3", Select all – again, when all are: clear. */
@Composable
fun SelectionTopBar(selection: SelectionState, all: List<Long>) {
    val allSelected = all.isNotEmpty() && selection.selected.containsAll(all)
    SelectionTopBar(selection.selected.size, allSelected, selection::clear) { selection.selected = if (allSelected) emptySet() else all.toSet() }
}

/** [SelectionTopBar] by its parts: the count, whether all are selected, Close, and Select all (or clear). */
@Composable
fun SelectionTopBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.title_selected, count), maxLines = 1) },
        navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_end_selection), onClose) },
        actions = {
            TooltipIconButton(
                R.drawable.ic_select_all,
                stringResource(if (allSelected) R.string.content_description_clear_selection else R.string.content_description_select_all),
                onSelectAll,
                colors = if (allSelected) IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary) else IconButtonDefaults.iconButtonColors(),
            )
        },
    )
}

/**
 * The selection's toolbar in Photos and the collections (SPEC §5.4), floating where the navigation bar
 * was: Share, Favorite (filled when all are), Add to album, Slideshow, Move to vault,
 * Move to Trash last in the error colour. Actions of later stages are shown disabled (D5).
 */
@Composable
fun MediaSelectionToolbar(
    items: List<MediaItem>,
    onShare: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onAddToAlbum: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromAlbum: (() -> Unit)? = null,
) {
    val allFavorite = items.isNotEmpty() && items.all { it.favorite }
    val scheme = MaterialTheme.colorScheme
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = scheme.surfaceContainer),
        expandedShadowElevation = 3.dp,
        modifier = modifier,
    ) {
        ShareButton(onShare)
        TooltipIconButton(
            if (allFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
            stringResource(if (allFavorite) R.string.action_unfavorite else R.string.action_favorite),
            { onFavorite(!allFavorite) },
        )
        TooltipIconButton(R.drawable.ic_library_add, stringResource(R.string.action_add_to_album), onAddToAlbum)
        // Remove from album on the toolbar in every grid, enabled only in a user album (the owner, over SPEC-albums §9.8's FAB),
        // after Add to album and far from Move to Trash
        TooltipIconButton(R.drawable.ic_library_remove, stringResource(R.string.action_remove_from_album), onRemoveFromAlbum ?: {}, enabled = onRemoveFromAlbum != null)
        // S8, S8
        TooltipIconButton(R.drawable.ic_slideshow, stringResource(R.string.action_slideshow), {}, enabled = false)
        TooltipIconButton(R.drawable.ic_lock, stringResource(R.string.action_move_to_vault), {}, enabled = false)
        TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.action_delete), onDelete, colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.error))
    }
}

/** Trash's selection toolbar (SPEC §11.3): two labelled buttons, Restore and Delete for good in the error colour. */
@Composable
fun TrashSelectionToolbar(onRestore: () -> Unit, onDeleteForever: () -> Unit, modifier: Modifier = Modifier) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        expandedShadowElevation = 3.dp,
        modifier = modifier,
    ) {
        TextButton(onClick = onRestore) {
            Icon(painterResource(R.drawable.ic_restore_from_trash), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.action_restore), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onDeleteForever) {
            Icon(painterResource(R.drawable.ic_delete_forever), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.action_delete_forever), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * A tile while selecting (SPEC §5.2, the system's pattern): selected, the picture shrinks to 80 % of
 * its cell and its corners round to 20 dp, a check in `primary` at the top start; not selected, an
 * empty circle there. No fill or frame of its own: the grid's surface shows around it.
 */
@Composable
fun SelectableFrame(selecting: Boolean, selected: Boolean, corner: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val motion = MaterialTheme.motionScheme
    val inset = animateFloatAsState(if (selected) 0.1f else 0f, motion.defaultSpatialSpec(), label = "inset")
    val round = animateDpAsState(if (selected) 20.dp else corner, motion.defaultSpatialSpec(), label = "corner")
    Box(modifier) {
        // Scaled in its layer, read while drawing: a grid of thousands of tiles neither measures nor composes anew for it.
        // The corners are drawn before the scale, so they are widened by as much
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val scale = 1f - 2f * inset.value
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape(round.value / scale)
                clip = true
            },
            content = content,
        )
        if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

/** The circle at a tile's or a header's start: empty, or `primary` with a check, ringed in `surface`. */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (selected) {
        Box(
            modifier.size(24.dp).border(2.dp, scheme.surface, CircleShape).padding(2.dp).background(scheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
        }
    } else {
        Box(modifier.size(20.dp).background(scheme.surfaceContainer, CircleShape).border(2.dp, scheme.outline, CircleShape))
    }
}

/**
 * Share, with the wait shown in its place (SPEC §14.3): after half a second of preparing copies a `LoadingIndicator`
 * takes the icon's place; after two the shell's status card says how far it is.
 */
@Composable
fun ShareButton(onClick: () -> Unit, enabled: Boolean = true) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val progress by app.sharing.progress.collectAsStateWithLifecycle()
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(progress != null) {
        waiting = false
        if (progress != null) {
            delay(500)
            waiting = true
        }
    }
    if (waiting && progress != null) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
    } else {
        TooltipIconButton(R.drawable.ic_share, stringResource(R.string.action_share), onClick, enabled = enabled)
    }
}

