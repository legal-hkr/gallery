@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.albums

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
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
import sh.hnet.gallery.R
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.ui.components.AlbumCover
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
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
 * The Albums tab (SPEC §9.1–§9.2; S4a): the top bar with New album (S4b, disabled until then – D5)
 * and Settings, the partial-access card, the collections as tonal tiles in two columns. Places (S6)
 * and the vault (S8) are shown disabled; the vault never has a count, Trash none when empty; Screenshots
 * leaves when its folders are hidden. Then the albums (SPEC-albums §9.4): in S4b1 the sources' albums,
 * pinned first, sorted by the header's menu; user albums join with S4b2.
 */
@Composable
fun AlbumsScreen(onOpenSettings: () -> Unit, onOpenCollection: (CollectionKind) -> Unit, onOpenFolder: (FolderKey) -> Unit, onOpenTrash: () -> Unit, viewModel: AlbumsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.label_nav_albums), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = {
                TooltipIconButton(R.drawable.ic_add, stringResource(R.string.content_description_new_album), {}, enabled = false)
                TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings)
            },
        )
        if (state.access == Access.NONE) {
            NoAccess(onAnswer = viewModel::recheck)
            return@Column
        }
        val locale = LocalConfiguration.current.locales[0]
        val number = NumberFormat.getIntegerInstance(locale)
        // Names as shown, for sorting by name in the app's language (SPEC-albums §9.4)
        val names = state.albums.associate { it.folder to albumName(it.folder) }
        val albums = remember(state.albums, state.sort, names, locale) { sortAlbums(state.albums, state.sort, names, Collator.getInstance(locale)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
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
                // Places sits after Screenshots, as in the SPEC's order
                if (kind == CollectionKind.SCREENSHOTS) {
                    item(key = "places") { CollectionTile(R.drawable.ic_location_on, stringResource(R.string.label_places), null, enabled = false) {} }
                }
            }
            item(key = "trash") { CollectionTile(R.drawable.ic_delete, stringResource(R.string.label_trash), state.trash.takeIf { it > 0 }?.let(number::format), onClick = onOpenTrash) }
            item(key = "vault") { CollectionTile(R.drawable.ic_lock, stringResource(R.string.label_vault), null, enabled = false) {} }
            if (albums.isNotEmpty()) {
                item(key = "header:albums", span = { GridItemSpan(maxLineSpan) }) { AlbumsHeader(state.sort, viewModel::setSort) }
                items(albums, key = { it.folder.key }) { album ->
                    AlbumTile(album, names[album.folder].orEmpty(), number.format(album.count)) { onOpenFolder(album.folder) }
                }
            }
        }
    }
}

/** A collection's tonal tile (SPEC §9.2): 64 dp, `surface-container`, 16 dp corners, the icon in its box, the name and the count. */
@Composable
private fun CollectionTile(@DrawableRes icon: Int, name: String, count: String?, enabled: Boolean = true, onClick: () -> Unit) {
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

/** The albums in order (SPEC-albums §9.4): pinned first in pinning order, then by [sort] – the newest file added, the name in the language's collation, the count. */
private fun sortAlbums(albums: List<SourceAlbum>, sort: String, names: Map<FolderKey, String>, collator: Collator): List<SourceAlbum> {
    val pinned = albums.filter { it.pinnedAt != null }.sortedBy { it.pinnedAt }
    val rest = albums.filter { it.pinnedAt == null }
    val sorted = when (sort) {
        "NAME" -> rest.sortedWith { a, b -> collator.compare(names[a.folder].orEmpty(), names[b.folder].orEmpty()) }
        "COUNT" -> rest.sortedByDescending { it.count }
        else -> rest.sortedByDescending { it.recent }
    }
    return pinned + sorted
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
 * An album's tile (SPEC-albums §9.4): the mosaic in one 16 dp clip, the name, and a line with the pin, the
 * folder mark (or the SD card's) and the count. Only the folder mark tells a source's album from a user's.
 */
@Composable
private fun AlbumTile(album: SourceAlbum, name: String, count: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        AlbumCover(album.newest, Modifier.fillMaxWidth().aspectRatio(1f), corner = 16.dp, gap = 2.dp)
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (album.pinnedAt != null) Icon(painterResource(R.drawable.ic_keep), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            if (album.otherStorage) {
                Icon(painterResource(R.drawable.ic_sd_card), contentDescription = stringResource(R.string.label_sd_card), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            } else {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = stringResource(R.string.content_description_folder_album), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            Text(count, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

