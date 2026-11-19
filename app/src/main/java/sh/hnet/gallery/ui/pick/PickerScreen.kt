@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.gallery.ui.pick

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.Collator
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PickRequest
import sh.hnet.gallery.model.Timeline
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.ui.albums.AlbumEntry
import sh.hnet.gallery.ui.albums.AlbumTile
import sh.hnet.gallery.ui.albums.AlbumsViewModel
import sh.hnet.gallery.ui.albums.CollectionTile
import sh.hnet.gallery.ui.albums.PageKey
import sh.hnet.gallery.ui.albums.PageState
import sh.hnet.gallery.ui.albums.PageViewModel
import sh.hnet.gallery.ui.albums.icon
import sh.hnet.gallery.ui.albums.sortAlbums
import sh.hnet.gallery.ui.albums.title
import sh.hnet.gallery.ui.components.GridFastScroller
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.grid.MediaGrid
import sh.hnet.gallery.ui.grid.SelectionState
import sh.hnet.gallery.ui.grid.rememberSaveableSelection
import sh.hnet.gallery.ui.photos.NoAccess
import sh.hnet.gallery.ui.photos.PhotosState
import sh.hnet.gallery.ui.photos.PhotosViewModel
import sh.hnet.gallery.ui.viewer.ViewerRequest

/**
 * Picking photos and videos (SPEC §17.4, LOGIKA §16.6): for another app (`PickerActivity`) and inside the app, for a
 * user album. Two tabs – Photos (the timeline) and Albums (the collections and both kinds of albums; never Trash or
 * the vault) –, the app's own grids with the pinch. Several: every tile has its circle and Done returns them; one:
 * a tap returns it at once. Types not asked for are dimmed. A long press opens the viewer to check the item, with
 * Info alone.
 *
 * @param title The request's title, shown while nothing is chosen
 * @param onLook Opens the read-only viewer over the picker
 */
@Composable
fun PickerScreen(request: PickRequest, title: String, onClose: () -> Unit, onDone: (List<MediaItem>) -> Unit, onLook: (ViewerRequest) -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val library by app.library.collectAsStateWithLifecycle()
    val selection = rememberSaveableSelection()
    var albums by rememberSaveable { mutableStateOf(false) }
    // The album opened on the Albums tab, kept as its name across a turn
    var pageKey by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageKey?.let(::pageOf)
    BackHandler(enabled = page != null) { pageKey = null }

    // Chosen items as the library knows them now: one gone meanwhile leaves the choice
    val byId = remember(library) { library?.visible.orEmpty().filter { !it.trashed }.associateBy { it.id } }
    LaunchedEffect(byId) {
        if (selection.selected.any { it !in byId }) selection.selected = selection.selected.filterTo(HashSet()) { it in byId }
    }
    val chosen = selection.selected.mapNotNull(byId::get)

    fun pick(item: MediaItem) {
        if (!request.accepts(item.mime)) return
        if (request.multiple) selection.toggle(item.id) else onDone(listOf(item))
    }

    // A surface of its own: in PickerActivity nothing else gives the content its colour (owner's test of S5c: black names in the dark theme)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(if (chosen.isEmpty()) title else stringResource(R.string.title_selected, chosen.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    page?.let { Text(pageTitle(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            navigationIcon = {
                if (page != null) TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), { pageKey = null })
                else TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_close), onClose)
            },
        )
        SecondaryTabRow(selectedTabIndex = if (albums) 1 else 0) {
            Tab(selected = !albums, onClick = {
                albums = false
                pageKey = null
            }, text = { Text(stringResource(R.string.label_nav_photos)) })
            Tab(selected = albums, onClick = { albums = true }, text = { Text(stringResource(R.string.label_nav_albums)) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bottom = 0.dp
            when {
                !albums -> PhotosPick(request, selection, bottom, ::pick, onLook)
                page != null -> PagePick(page, request, selection, bottom, ::pick, onLook)
                else -> AlbumsPick(bottom) { pageKey = it.name() }
            }
        }
        if (request.multiple) {
            // Done, pinned at the bottom under the grid on the page's surface, enabled once anything is chosen (SPEC §17.4);
            // over the tiles its disabled container let them show through (owner's test of S5c)
            Button(
                onClick = { onDone(chosen) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.button_done)) }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    }
}

@Composable
private fun PhotosPick(request: PickRequest, selection: SelectionState, bottom: Dp, pick: (MediaItem) -> Unit, onLook: (ViewerRequest) -> Unit, viewModel: PhotosViewModel = viewModel(key = "pick:photos")) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    when (val shown = state) {
        PhotosState.NoAccess -> NoAccess(onAnswer = viewModel::recheck)
        is PhotosState.Shown -> PickGrid(shown.days, shown.months, step, viewModel::setStep, facts, request, selection, bottom, pick, onLook)
        else -> {}
    }
}

@Composable
private fun PagePick(key: PageKey, request: PickRequest, selection: SelectionState, bottom: Dp, pick: (MediaItem) -> Unit, onLook: (ViewerRequest) -> Unit) {
    val viewModel: PageViewModel = viewModel(key = "pick:$key")
    LaunchedEffect(key) { viewModel.show(key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val shown = state as? PageState.Shown ?: return
    PickGrid(shown.days, shown.months, step, viewModel::setStep, facts, request, selection, bottom, pick, onLook)
}

/** The app's grid in picking: circles always with several, types not asked for dimmed, a long press to look. */
@Composable
private fun PickGrid(
    days: Timeline,
    months: Timeline,
    step: GridStep,
    onStep: (GridStep) -> Unit,
    facts: Map<Long, Fact>,
    request: PickRequest,
    selection: SelectionState,
    bottom: Dp,
    pick: (MediaItem) -> Unit,
    onLook: (ViewerRequest) -> Unit,
) {
    val grid = rememberLazyGridState()
    val today = remember(days) { LocalDate.now() }
    val timeline = if (step.months) months else days
    val entries = timeline.entries
    val ids = remember(timeline) { entries.mapNotNull { (it as? TimelineEntry.Tile)?.item?.id } }
    val locale = LocalConfiguration.current.locales[0]
    val monthYear = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    Box(Modifier.fillMaxSize()) {
        MediaGrid(
            days, months, step,
            onStepChange = onStep,
            facts = facts,
            state = grid,
            today = today,
            selection = if (request.multiple) selection else null,
            alwaysSelecting = request.multiple,
            pickable = { request.accepts(it.mime) },
            onHold = { item -> onLook(ViewerRequest(ids, item.id, readOnly = true) {}) },
            bottomPadding = bottom,
            onOpen = pick,
        )
        if (entries.isNotEmpty()) {
            GridFastScroller(grid, entries.size, label = { index ->
                val month = entries[index.coerceIn(0, entries.lastIndex)].month
                if (timeline.months) month.year.toString() else monthYear.format(month.atDay(1))
            }, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The Albums tab in picking: Favorites, Videos, Screenshots, then the albums of both kinds; no Places, Trash or vault. */
@Composable
private fun AlbumsPick(bottom: Dp, onOpen: (PageKey) -> Unit) {
    val viewModel: AlbumsViewModel = viewModel(key = "pick:albums")
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    val locale = LocalConfiguration.current.locales[0]
    val number = NumberFormat.getIntegerInstance(locale)
    val names = state.albums.associate { entry -> entry.key to if (entry is AlbumEntry.Source) albumName(entry.folder) else (entry as AlbumEntry.User).name }
    val albums = remember(state.albums, state.sort, names, locale) { sortAlbums(state.albums, state.sort, Collator.getInstance(locale)) { names[it.key].orEmpty() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottom + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for (kind in CollectionKind.entries) {
            if (kind == CollectionKind.SCREENSHOTS && state.screenshotsHidden) continue
            item(key = kind.name) {
                CollectionTile(kind.icon, stringResource(kind.title), state.counts[kind]?.let(number::format)) { onOpen(PageKey.Collection(kind)) }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "header:albums", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.label_nav_albums), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
            items(albums, key = { it.key }) { album ->
                AlbumTile(album, names[album.key].orEmpty(), number.format(album.count), selecting = false, selected = false, onLongClick = {}) {
                    onOpen(
                        when (album) {
                            is AlbumEntry.Source -> PageKey.Folder(album.folder)
                            is AlbumEntry.User -> PageKey.User(album.id)
                        },
                    )
                }
            }
        }
    }
}

/** A page's title in the picker's top bar, under the request's. */
@Composable
private fun pageTitle(key: PageKey): String {
    val app = LocalContext.current.applicationContext as GalleryApplication
    return when (key) {
        is PageKey.Collection -> stringResource(key.kind.title)
        is PageKey.Folder -> albumName(key.folder)
        is PageKey.User -> {
            val albums by app.albums.albums.collectAsStateWithLifecycle()
            albums?.firstOrNull { it.id == key.id }?.name.orEmpty()
        }
    }
}

/** A page's key as a saveable name: `c:FAVORITES`, `f:<volume:path>`, `u:12`. */
private fun PageKey.name(): String = when (this) {
    is PageKey.Collection -> "c:${kind.name}"
    is PageKey.Folder -> "f:${folder.key}"
    is PageKey.User -> "u:$id"
}

private fun pageOf(name: String): PageKey? = when {
    name.startsWith("c:") -> CollectionKind.entries.firstOrNull { it.name == name.drop(2) }?.let { PageKey.Collection(it) }
    name.startsWith("f:") -> FolderKey.of(name.drop(2))?.let { PageKey.Folder(it) }
    name.startsWith("u:") -> name.drop(2).toLongOrNull()?.let { PageKey.User(it) }
    else -> null
}
