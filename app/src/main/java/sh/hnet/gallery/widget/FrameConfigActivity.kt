@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FrameInterval
import sh.hnet.gallery.model.FrameState
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.ui.albums.AlbumEntry
import sh.hnet.gallery.ui.albums.AlbumsViewModel
import sh.hnet.gallery.ui.components.AlbumCover
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.SettingsGroupHeader
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.components.groupShapes
import sh.hnet.gallery.ui.components.settingsRowColors
import sh.hnet.gallery.ui.theme.GalleryTheme

/**
 * Frame's configuration (S11, SPEC §21.4, board 22c): when the widget is placed, from the launcher's widget settings,
 * and from the widget's "Album deleted" state. A preview, the album – the Albums tab's albums in its order, then
 * Favorites and Videos; never Trash or the vault –, and the interval as connected toggles. Add keeps the choice and
 * draws the widget.
 */
class FrameConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GalleryApplication
        app.startLibrary()
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Closed without Add: the launcher drops a widget just placed
        setResult(Activity.RESULT_CANCELED)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val existing = Frames.get(this, id)
        setContent {
            GalleryTheme {
                Setup(existing, onClose = ::finish, onAdd = { album: String, interval: FrameInterval ->
                    // As it is now, not as it was when this opened: a redraw may have moved its round on meanwhile
                    val now = Frames.get(this, id)
                    // The same album keeps its round; another starts anew
                    val state = if (now != null && now.album == album) now.copy(interval = interval.name) else FrameState(album, interval.name)
                    // Another interval: a new photo now, the next one after the new interval
                    Frames.set(this, id, if (now?.interval != interval.name) state.copy(nextAt = 0) else state)
                    Widgets.update(this)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                })
            }
        }
    }
}

/** One album to choose: its key, name and cover. */
private class Choice(val key: String, val name: String, val cover: List<MediaItem>)

@Composable
private fun Setup(existing: FrameState?, onClose: () -> Unit, onAdd: (String, FrameInterval) -> Unit, viewModel: AlbumsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as GalleryApplication
    val library by app.library.collectAsStateWithLifecycle()
    val favorites = stringResource(R.string.label_favorites)
    val videos = stringResource(R.string.label_videos)
    val choices = buildList {
        for (entry in state.albums) {
            when (entry) {
                is AlbumEntry.Source -> add(Choice(entry.key, albumName(entry.folder), entry.newest))
                is AlbumEntry.User -> if (entry.count > 0) add(Choice(entry.key, entry.name, entry.newest))
            }
        }
        val shown = library?.visible.orEmpty()
        val favoriteItems = shown.filter(CollectionKind.FAVORITES::holds)
        if (favoriteItems.isNotEmpty()) add(Choice(Frames.FAVORITES, favorites, favoriteItems.take(4)))
        val videoItems = shown.filter(CollectionKind.VIDEOS::holds)
        if (videoItems.isNotEmpty()) add(Choice(Frames.VIDEOS, videos, videoItems.take(4)))
    }
    var album by rememberSaveable { mutableStateOf(existing?.album) }
    var interval by rememberSaveable { mutableStateOf(FrameInterval.of(existing?.interval)) }
    // Everything read before a choice is made: Favorites and Videos come with the library, maybe after the albums – a
    // Save before it put the first album in their place (review of S11). Only an album truly gone falls back to the first.
    val loaded = state.loaded && library != null
    val chosen = if (!loaded) null else choices.firstOrNull { it.key == album } ?: choices.firstOrNull()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_close), onClose) },
                title = { Text(stringResource(R.string.widget_frame_name)) },
                actions = {
                    Button(onClick = { chosen?.let { onAdd(it.key, interval) } }, enabled = chosen != null, modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(if (existing != null) R.string.button_save else R.string.button_add))
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The preview: the album's newest photo, as the widget's 2 × 2
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(160.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    chosen?.cover?.firstOrNull()?.let { MediaThumbnail(it, 480, Modifier.fillMaxSize()) }
                }
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsGroupHeader(stringResource(R.string.header_album))
                choices.forEachIndexed { index, choice ->
                    val selected = choice.key == chosen?.key
                    SegmentedListItem(
                        selected = selected,
                        onClick = { album = choice.key },
                        shapes = groupShapes(index, choices.size),
                        colors = settingsRowColors(),
                        leadingContent = { AlbumCover(choice.cover, Modifier.size(40.dp), corner = 8.dp) },
                        trailingContent = { RadioButton(selected = selected, onClick = null) },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    ) {
                        Text(choice.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGroupHeader(stringResource(R.string.header_change_photo))
                val options = listOf(FrameInterval.HOURLY to R.string.interval_hourly, FrameInterval.SIX_HOURS to R.string.interval_6_hours, FrameInterval.DAILY to R.string.interval_daily)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    options.forEachIndexed { index, (value, label) ->
                        ToggleButton(
                            checked = interval == value,
                            onCheckedChange = { if (it) interval = value },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
            }
        }
    }
}
