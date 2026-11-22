@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.gallery.ui.map

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.model.MapCluster
import sh.hnet.gallery.model.MapClusters
import sh.hnet.gallery.model.MapPoint
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Mercator
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.placeData
import sh.hnet.gallery.ui.components.placeLanguage
import sh.hnet.gallery.ui.components.settlementOf
import sh.hnet.gallery.ui.viewer.ViewerRequest
import sh.hnet.gallery.ui.viewer.mediaOrigin

/** The library's located items on the map, newest first, by id; empty until the library and the facts are there. */
@Composable
fun rememberMapItems(): Pair<List<MapPoint>, Map<Long, MediaItem>> {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val library by app.library.collectAsStateWithLifecycle()
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val result by produceState(emptyList<MapPoint>() to emptyMap<Long, MediaItem>(), library, facts) {
        val items = library?.visible
        val known = facts
        value = if (items == null || known == null) emptyList<MapPoint>() to emptyMap() else withContext(Dispatchers.Default) {
            val shown = items.filter { !it.trashed }
            shown.mapNotNull { item ->
                val fact = known[item.id] ?: return@mapNotNull null
                val lat = fact.latitude ?: return@mapNotNull null
                val lon = fact.longitude ?: return@mapNotNull null
                MapPoint(item.id, Mercator.x(lon), Mercator.y(lat))
            } to shown.associateBy { it.id }
        }
    }
    return result
}

/**
 * The map page (SPEC §13.3, LOGIKA §17.5): the map edge to edge under the top bar, the located items in clusters
 * of ~64 dp cells made again at each half step of zoom; a cluster's tap rings it and opens the sheet with its items
 * in four columns, over which the viewer opens. Opened at a point (Info's small map) it starts there at town level,
 * else showing every located item.
 *
 * The sheet is the scaffold's, in the page's own composition (not a window of its own as `ModalBottomSheet`'s): the
 * viewer opened from it covers it, as the album page's lesson of S4b1 taught.
 */
@Composable
fun MapScreen(onBack: () -> Unit, onOpen: (ViewerRequest) -> Unit, lat: Double? = null, lon: Double? = null) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val data = placeData()
    val language = placeLanguage()
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val state = remember { MapState() }
    val (points, items) = rememberMapItems()
    var selected by remember { mutableStateOf<MapCluster?>(null) }
    val sheet = rememberBottomSheetScaffoldState(rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)))

    // Where it opens: at the point, or around everything located, once the view and the items are known
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(state.size, points) {
        if (placed || state.size.width == 0) return@LaunchedEffect
        if (lat != null && lon != null) {
            state.show(lat, lon, TOWN_DEGREES)
            placed = true
        } else if (points.isNotEmpty()) {
            state.fit(points)
            placed = true
        } else {
            state.showWorld()
        }
    }

    // Clusters for the zoom's half step, made off the main thread
    val cellPx = with(density) { 64.dp.toPx() }.toDouble()
    val step by remember { derivedStateOf { MapClusters.step(max(state.world, 1.0), cellPx) } }
    val clusters by produceState(emptyList<MapCluster>(), points, step) {
        value = withContext(Dispatchers.Default) { MapClusters.of(points, MapClusters.cellOf(step)) }
    }
    val marker = with(density) { MARKER.toPx() }
    val visible by remember {
        derivedStateOf {
            clusters.filter { c ->
                val sx = state.screenX(c.x)
                val sy = state.screenY(c.y)
                sx > -marker && sy > -marker && sx < state.size.width + marker && sy < state.size.height + marker
            }
        }
    }

    // The sheet's stops come from its content's height and are worked out again only when it is measured (owner's
    // tests of S6b2: a rise stopped as the content grew – thumbnails, facts –; a peek animated instead left it half
    // way, as a new peek alone doesn't measure it again). Its content is therefore always one height, chosen or not,
    // so the stops never move and the sheet's own rise and fall have nothing to stop them. The cluster shown stays
    // while the sheet goes down
    var shownCluster by remember { mutableStateOf<MapCluster?>(null) }
    LaunchedEffect(selected) {
        val chosen = selected
        if (chosen != null) {
            shownCluster = chosen
            sheet.bottomSheetState.partialExpand()
        } else {
            sheet.bottomSheetState.hide()
        }
    }
    // Swiped down out of sight: the cluster is let go too
    LaunchedEffect(sheet) {
        snapshotFlow { sheet.bottomSheetState.currentValue }.collect { if (it == SheetValue.Hidden) selected = null }
    }

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = 320.dp,
        topBar = {
            TopAppBar(
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                title = { Text(stringResource(R.string.title_map), maxLines = 1, modifier = Modifier.semantics { heading() }) },
            )
        },
        sheetContent = {
            val shown = shownCluster
            Column(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT)) {
                if (shown != null) {
                    val ids = shown.ids.filter { it in items }
                    // The town, or "Town and around" when the cluster spans several (SPEC §13.3)
                    val towns = ids.mapNotNull { id -> data?.settlementOf(facts?.get(id)) }
                    val main = towns.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    val title = when {
                        data == null || main == null -> stringResource(R.string.title_map)
                        towns.distinct().size == 1 && towns.size == ids.size -> data.settlementName(main, language)
                        else -> stringResource(R.string.label_area_around, data.settlementName(main, language))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(stringResource(R.string.label_album_count, ids.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(ids, key = { it }) { id ->
                            val item = items[id] ?: return@items
                            MediaThumbnail(
                                item, 256,
                                Modifier.aspectRatio(1f).mediaOrigin(id.toString(), 0.dp).clickable { onOpen(ViewerRequest(ids, id, title = title) {}) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // The gestures on what holds the map and the clusters, so a finger on a cluster pinches too; a tap on the map
        // (not on a cluster) lets the cluster go
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).mapGestures(state) { selected = null }) {
            if (data != null) WorldMap(state, data, language, Modifier.fillMaxSize(), interactive = false)
            for (cluster in visible) {
                key(cluster.newest) {
                    val item = items[cluster.newest]
                    if (item != null) {
                        ClusterMarker(item, cluster.size, selected?.newest == cluster.newest, Modifier.offset {
                            IntOffset((state.screenX(cluster.x) - marker / 2).roundToInt(), (state.screenY(cluster.y) - marker / 2).roundToInt())
                        }) { selected = cluster }
                    }
                }
            }
        }
    }
    // A cluster that changed with the zoom: the sheet keeps the items it was opened with until the map is tapped
}

/**
 * A cluster's marker (SPEC §13.3): 52 dp, corners 14, its newest item, ringed in `surface` (3 dp `primary` while
 * chosen), a shadow of the first elevation, the count on a `primary` badge at the top end.
 */
@Composable
fun ClusterMarker(item: MediaItem, count: Int, chosen: Boolean, modifier: Modifier = Modifier, size: Dp = MARKER, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(size * 14 / 52)
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(1.dp, shape)
                .clip(shape)
                .border(if (chosen) 3.dp else 2.dp, if (chosen) scheme.primary else scheme.surface, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            MediaThumbnail(item, 256, Modifier.fillMaxSize().padding(if (chosen) 3.dp else 2.dp).clip(RoundedCornerShape(size * 12 / 52)))
        }
        if (count > 1) {
            Surface(color = scheme.primary, shape = CircleShape, modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)) {
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
}

val MARKER = 52.dp

/** The sheet's content as a share of the page's height, the same whatever it shows (its stops never move). */
private const val SHEET_HEIGHT = 0.85f

/** About a town and its surroundings across the view (Info's small map, a map opened at a point). */
const val TOWN_DEGREES = 0.4

/**
 * Places' map card (SPEC §13.1: 150 dp high, corners 20): every located item in clusters at the card's zoom, the
 * map fitted around them; not moved by the fingers – a tap opens the map page.
 */
@Composable
fun MapCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val data = placeData() ?: return
    val language = placeLanguage()
    val density = LocalDensity.current
    val state = remember { MapState() }
    val (points, items) = rememberMapItems()
    LaunchedEffect(state.size, points) { state.fit(points) }
    val cellPx = with(density) { 48.dp.toPx() }.toDouble()
    // Off the main thread, as the map's own (review before v0.1.0: the card stopped Places' scrolling while it clustered)
    val clusters by produceState(emptyList<MapCluster>(), points, state.world, cellPx) {
        value = withContext(Dispatchers.Default) { MapClusters.of(points, cellPx / max(state.world, 1.0)) }
    }
    val marker = with(density) { CARD_MARKER.toPx() }
    Box(modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = stringResource(R.string.button_map), onClick = onOpen)) {
        WorldMap(state, data, language, Modifier.fillMaxSize(), interactive = false)
        for (cluster in clusters) {
            val item = items[cluster.newest] ?: continue
            key(cluster.newest) {
                ClusterMarker(item, cluster.size, chosen = false, size = CARD_MARKER, modifier = Modifier.offset {
                    IntOffset((state.screenX(cluster.x) - marker / 2).roundToInt(), (state.screenY(cluster.y) - marker / 2).roundToInt())
                })
            }
        }
    }
}

private val CARD_MARKER = 36.dp

/**
 * Info's small map (SPEC §7.3): 240 × 120 dp, corners 16, the app's map at town level centred on the item with a
 * filled pin in `primary`; a tap opens the map page there.
 */
@Composable
fun SmallMap(lat: Double, lon: Double, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val data = placeData() ?: return
    val language = placeLanguage()
    val state = remember { MapState() }
    LaunchedEffect(state.size, lat, lon) { if (state.size.width > 0) state.show(lat, lon, TOWN_DEGREES) }
    Box(
        modifier.size(240.dp, 120.dp).clip(RoundedCornerShape(16.dp)).then(if (onOpen != null) Modifier.clickable(onClickLabel = stringResource(R.string.button_map), onClick = onOpen) else Modifier),
    ) {
        WorldMap(state, data, language, Modifier.fillMaxSize(), interactive = false)
        // The pin's point on the item's place
        Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_location_on_filled),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).offset(y = (-14).dp).size(32.dp),
        )
    }
}
