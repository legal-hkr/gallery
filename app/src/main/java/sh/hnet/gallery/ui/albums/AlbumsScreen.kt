@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.gallery.ui.albums

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import java.text.NumberFormat
import sh.hnet.gallery.R
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.ui.components.TooltipIconButton
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
 * and the vault (S8) are shown disabled; the vault never has a count, Trash none when empty. The list
 * of albums comes with S4b, in the same grid.
 */
@Composable
fun AlbumsScreen(onOpenSettings: () -> Unit, onOpenCollection: (CollectionKind) -> Unit, onOpenTrash: () -> Unit, viewModel: AlbumsViewModel = viewModel()) {
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
                item(key = kind.name) {
                    CollectionTile(kind.icon, stringResource(kind.title), state.counts[kind]?.takeIf { state.loaded }?.let(number::format)) { onOpenCollection(kind) }
                }
                // Places sits after Screenshots, as in the SPEC's order
                if (kind == CollectionKind.SCREENSHOTS) {
                    item(key = "places") { CollectionTile(R.drawable.ic_location_on, stringResource(R.string.label_places), null, enabled = false) {} }
                }
            }
            item(key = "trash") { CollectionTile(R.drawable.ic_delete, stringResource(R.string.label_trash), state.trash.takeIf { it > 0 }?.let(number::format), onClick = onOpenTrash) }
            item(key = "vault") { CollectionTile(R.drawable.ic_lock, stringResource(R.string.label_vault), null, enabled = false) {} }
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
