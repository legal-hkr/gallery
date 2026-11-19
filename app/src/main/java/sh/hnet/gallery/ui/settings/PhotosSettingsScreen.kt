@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.TimelineChoice
import sh.hnet.gallery.model.Album
import sh.hnet.gallery.model.Albums
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.SettingsGroupHeader
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.SettingsSwitchRow
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.components.albumPath
import sh.hnet.gallery.ui.components.groupShapes
import sh.hnet.gallery.ui.components.settingsRowColors

/**
 * Settings › Photos (SPEC §20.3): every album with a switch – shown in the timeline or not –, the
 * camera and the screenshots first; then whether albums created in the app start in Photos.
 */
@Composable
fun PhotosSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val items by app.index.items.collectAsStateWithLifecycle()
    val choice by app.preferences.timeline.collectAsStateWithLifecycle(TimelineChoice())
    val newAlbums by app.preferences.newAlbumsInPhotos.collectAsStateWithLifecycle(true)
    // The defaults first (camera, then screenshots), then the album with the newest item first, as Albums sorts by default
    val albums = remember(items) {
        Albums.of(items.orEmpty()).sortedBy { if (TimelineFolders.isCamera(it.folder.path)) 0 else if (TimelineFolders.isScreenshots(it.folder.path)) 1 else 2 }
    }
    SettingsPage(title = stringResource(R.string.label_nav_photos), onBack = onBack) {
        if (albums.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsGroupHeader(stringResource(R.string.header_albums_in_photos))
                albums.forEachIndexed { index, album ->
                    AlbumSwitchRow(index, albums.size, album, shown = choice.shown(album.folder)) { shown ->
                        scope.launch { app.preferences.setInTimeline(album.folder, shown) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsGroupHeader(stringResource(R.string.header_other))
            SettingsSwitchRow(
                groupShapes(0, 1), R.drawable.ic_create_new_folder, stringResource(R.string.label_new_albums_in_photos), newAlbums,
                onCheckedChange = { scope.launch { app.preferences.setNewAlbumsInPhotos(it) } },
                supporting = stringResource(R.string.desc_new_albums_in_photos),
            )
        }
    }
}

/** An album with its switch (SPEC §20.3): the cover (its newest item until the mosaic of S4), name and path; the whole row toggles. */
@Composable
private fun AlbumSwitchRow(index: Int, count: Int, album: Album, shown: Boolean, onShownChange: (Boolean) -> Unit) {
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    SegmentedListItem(
        checked = shown,
        onCheckedChange = onShownChange,
        shapes = groupShapes(index, count),
        colors = settingsRowColors(),
        leadingContent = { MediaThumbnail(album.newest, px, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))) },
        trailingContent = { Switch(checked = shown, onCheckedChange = null) },
        supportingContent = { Text(albumPath(album.folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(albumName(album.folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
