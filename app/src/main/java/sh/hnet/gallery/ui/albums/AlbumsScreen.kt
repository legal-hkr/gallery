@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.albums

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.Collator
import java.text.NumberFormat
import kotlinx.coroutines.launch
import sh.hnet.gallery.R
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.ui.components.AlbumCover
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.grid.SelectableFrame
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.photos.NoAccess
import sh.hnet.gallery.ui.photos.PartialAccessCard

/** A collection's name and icon (SPEC §9.2). */
internal val CollectionKind.title: Int
    get() = when (this) {
        CollectionKind.FAVORITES -> R.string.label_favorites
        CollectionKind.VIDEOS -> R.string.label_videos
        CollectionKind.SCREENSHOTS -> R.string.label_screenshots
    }

internal val CollectionKind.icon: Int
    get() = when (this) {
        CollectionKind.FAVORITES -> R.drawable.ic_favorite
        CollectionKind.VIDEOS -> R.drawable.ic_movie
        CollectionKind.SCREENSHOTS -> R.drawable.ic_screenshot_monitor
    }

/**
 * The Albums tab (SPEC §9.1–§9.2, SPEC-albums §9.2–§9.6): the top bar with New album and Settings, the
 * partial-access card, the collections as tonal tiles in two columns – Places (S6) and the vault (S8b), the vault never with a count, Trash without one when empty, Screenshots gone when its folders
 * are hidden – then the albums of both kinds in one list, pinned first, sorted by the header's menu. A long
 * press selects albums (the collections leave meanwhile); the toolbar offers only what applies to all.
 */
@Composable
fun AlbumsScreen(
    onOpenSettings: () -> Unit,
    onOpenCollection: (CollectionKind) -> Unit,
    onOpenFolder: (FolderKey) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPlaces: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    /** A new album: its page with the picker over it (SPEC-albums §9.6). */
    onNewAlbum: (Long) -> Unit = onOpenAlbum,
    viewModel: AlbumsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalMediaActions.current
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var naming by rememberSaveable { mutableStateOf(false) }
    SelectionEffects(selected.isNotEmpty()) { selected = emptySet() }
    val locale = LocalConfiguration.current.locales[0]
    val number = NumberFormat.getIntegerInstance(locale)
    // Names as shown, for sorting by name in the app's language (SPEC-albums §9.4)
    val names = state.albums.associate { entry -> entry.key to if (entry is AlbumEntry.Source) albumName(entry.folder) else (entry as AlbumEntry.User).name }
    val albums = remember(state.albums, state.sort, names, locale) { sortAlbums(state.albums, state.sort, Collator.getInstance(locale)) { names[it.key].orEmpty() } }
    LaunchedEffect(albums) {
        val present = albums.mapTo(HashSet()) { it.key }
        if (selected.any { it !in present }) selected = selected.filterTo(HashSet()) { it in present }
    }
    val chosen = albums.filter { it.key in selected }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            val all = albums.isNotEmpty() && chosen.size == albums.size
            SelectionTopBar(chosen.size, all, { selected = emptySet() }) { selected = if (all) emptySet() else albums.mapTo(HashSet()) { it.key } }
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.label_nav_albums), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                actions = {
                    TooltipIconButton(R.drawable.ic_add, stringResource(R.string.content_description_new_album), { naming = true }, enabled = state.access != Access.NONE)
                    TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings)
                },
            )
        }
        if (state.access == Access.NONE) {
            NoAccess(onAnswer = viewModel::recheck)
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (selected.isNotEmpty()) 96.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // The collections can't be selected: they leave while albums are (SPEC-albums §9.5)
                if (selected.isEmpty()) {
                    state.partial?.let { count ->
                        item(key = "card:partial", span = { GridItemSpan(maxLineSpan) }) { PartialAccessCard(count, onAnswer = viewModel::recheck, sides = 0.dp) }
                    }
                    for (kind in CollectionKind.entries) {
                        // Screenshots leaves when its folders are hidden; Places after it stays (owner's test of S4b1: the whole line went)
                        if (!(kind == CollectionKind.SCREENSHOTS && state.screenshotsHidden)) {
                            item(key = kind.name) {
                                CollectionTile(kind.icon, stringResource(kind.title), state.counts[kind]?.takeIf { state.loaded }?.let(number::format)) { onOpenCollection(kind) }
                            }
                        }
                        // Places sits after Screenshots, as in the SPEC's order; it goes while place names are off (SPEC §20.5)
                        if (kind == CollectionKind.SCREENSHOTS) {
                            state.places?.let { towns ->
                                item(key = "places") { CollectionTile(R.drawable.ic_location_on, stringResource(R.string.label_places), towns.takeIf { it > 0 }?.let(number::format), onClick = onOpenPlaces) }
                            }
                        }
                    }
                    item(key = "trash") { CollectionTile(R.drawable.ic_delete, stringResource(R.string.label_trash), state.trash.takeIf { it > 0 }?.let(number::format), onClick = onOpenTrash) }
                    item(key = "vault") { CollectionTile(R.drawable.ic_lock, stringResource(R.string.label_vault), null, onClick = onOpenVault) }
                }
                if (albums.isNotEmpty()) {
                    if (selected.isEmpty()) item(key = "header:albums", span = { GridItemSpan(maxLineSpan) }) { AlbumsHeader(state.sort, viewModel::setSort) }
                    items(albums, key = { it.key }) { album ->
                        AlbumTile(
                            album,
                            names[album.key].orEmpty(),
                            number.format(album.count),
                            selecting = selected.isNotEmpty(),
                            selected = album.key in selected,
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                selected = if (album.key in selected) selected - album.key else selected + album.key
                            },
                        ) {
                            when {
                                selected.isNotEmpty() -> selected = if (album.key in selected) selected - album.key else selected + album.key
                                album is AlbumEntry.Source -> onOpenFolder(album.folder)
                                album is AlbumEntry.User -> onOpenAlbum(album.id)
                            }
                        }
                    }
                }
            }
            if (chosen.isNotEmpty()) {
                AlbumSelectionToolbar(
                    chosen,
                    onPin = { pin ->
                        viewModel.pin(chosen, pin)
                        selected = emptySet()
                    },
                    onDelete = {
                        val users = chosen.filterIsInstance<AlbumEntry.User>()
                        val message = if (users.size == 1) resources.getString(R.string.msg_album_deleted, users.first().name) else resources.getString(R.string.msg_albums_deleted, users.size)
                        selected = emptySet()
                        scope.launch {
                            val deleted = viewModel.delete(users.map { it.id })
                            actions.say(message) { viewModel.putBack(deleted) }
                        }
                    },
                    onHide = {
                        val sources = chosen.filterIsInstance<AlbumEntry.Source>()
                        val message = if (sources.size == 1) resources.getString(R.string.msg_folder_hidden, names[sources.first().key].orEmpty()) else resources.getString(R.string.msg_folders_hidden, sources.size)
                        selected = emptySet()
                        scope.launch {
                            val before = viewModel.hide(sources.map { it.folder })
                            actions.say(message) { viewModel.undoHide(before) }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                )
            }
        }
    }
    if (naming) {
        AlbumNameDialog(stringResource(R.string.title_new_album), "", stringResource(R.string.button_create), albumNamesTaken(), onDismiss = { naming = false }) { name ->
            naming = false
            // A new album opens the picker over its page; closed, the album stays empty on its empty state (SPEC-albums §9.6, D19)
            scope.launch { onNewAlbum(viewModel.create(name)) }
        }
    }
}

/**
 * The album selection's toolbar (SPEC-albums §9.5): only what applies to every album selected – Pin or Unpin
 * (Unpin when all are pinned) always; Delete albums when all are user albums (last, in error); Hide folders
 * when all are sources' albums.
 */
@Composable
private fun AlbumSelectionToolbar(chosen: List<AlbumEntry>, onPin: (Boolean) -> Unit, onDelete: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val allPinned = chosen.all { it.pinnedAt != null }
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        expandedShadowElevation = 3.dp,
        modifier = modifier,
    ) {
        TooltipIconButton(R.drawable.ic_keep, stringResource(if (allPinned) R.string.action_unpin else R.string.action_pin), { onPin(!allPinned) })
        if (chosen.all { it is AlbumEntry.Source }) TooltipIconButton(R.drawable.ic_visibility_off, stringResource(R.string.action_hide_folders), onHide)
        if (chosen.all { it is AlbumEntry.User }) {
            TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.action_delete_albums), onDelete, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error))
        }
    }
}

/** A collection's tonal tile (SPEC §9.2): 64 dp, `surface-container`, 16 dp corners, the icon in its box, the name and the count. */
@Composable
internal fun CollectionTile(@DrawableRes icon: Int, name: String, count: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp)
            // Disabled until its stage (D5), as M3 shows a disabled control
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(scheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = scheme.onSecondaryContainer)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) Text(count, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** "Albums" and the sort's text button with its menu (SPEC-albums §9.4). */
@Composable
private fun AlbumsHeader(sort: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("RECENT" to R.string.sort_recent, "NAME" to R.string.sort_name, "COUNT" to R.string.sort_count)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.label_nav_albums),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box {
            TextButton(onClick = { open = true }) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(options.firstOrNull { it.first == sort }?.second ?: R.string.sort_recent), modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    options.forEachIndexed { index, (option, label) ->
                        SelectableDropdownMenuItem(
                            selected = option == sort,
                            onClick = {
                                open = false
                                onSort(option)
                            },
                            text = { Text(stringResource(label), maxLines = 1) },
                            shapes = MenuDefaults.itemShape(index, options.size),
                            trailingContent = { if (option == sort) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * An album's tile (SPEC-albums §9.4): the mosaic in one 16 dp clip – an empty user album shows `photo_album` –,
 * the name, and a line with the pin, the folder mark (or the SD card's) for a source's album, and the count.
 * Selecting, it takes the system's selected-tile pattern (SPEC §5.2).
 */
@Composable
internal fun AlbumTile(album: AlbumEntry, name: String, count: String, selecting: Boolean, selected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        SelectableFrame(selecting, selected, 16.dp, Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (album.newest.isEmpty()) {
                Box(Modifier.fillMaxSize().background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_photo_album), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                }
            } else {
                AlbumCover(album.newest, Modifier.fillMaxSize(), corner = 0.dp, gap = 2.dp)
            }
        }
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (album.pinnedAt != null) Icon(painterResource(R.drawable.ic_keep), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            if (album is AlbumEntry.Source) {
                if (album.otherStorage) {
                    Icon(painterResource(R.drawable.ic_sd_card), contentDescription = stringResource(R.string.label_sd_card), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                } else {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = stringResource(R.string.content_description_folder_album), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Text(count, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
