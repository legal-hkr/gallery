@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.gallery.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.model.PlaceData
import sh.hnet.gallery.model.PlaceLanguage
import sh.hnet.gallery.model.TownGroup
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsNavigationRow
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.placeData
import sh.hnet.gallery.ui.components.placeLanguage
import sh.hnet.gallery.ui.map.MapCard

/**
 * Places (SPEC §13.1, LOGIKA §17.5): countries by the number of items, each a header to its page and a row of its
 * towns; "No location" last. The map card first opens the map page (S6b2); no map button over it (owner's test of
 * S6b2: it did what the card does).
 */
@Composable
fun PlacesScreen(onBack: () -> Unit, onOpenCountry: (Int) -> Unit, onOpenTown: (Int) -> Unit, onOpenNoLocation: () -> Unit, onOpenMap: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val groups by app.placeGroups.collectAsStateWithLifecycle()
    val data = placeData()
    val language = placeLanguage()
    val canLocate = remember { app.media.canLocate() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            title = { Text(stringResource(R.string.label_places), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
        )
        val shown = groups
        when {
            // Without the right to the photos' location there are no places (SPEC §19.2)
            !canLocate -> EmptyState(R.drawable.ic_location_off, stringResource(R.string.desc_place_unavailable))
            shown == null || data == null -> {}
            shown.countries.isEmpty() && shown.noLocation.isEmpty() -> EmptyState(R.drawable.ic_location_off, stringResource(R.string.msg_no_places))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (shown.countries.isEmpty()) item { EmptyState(R.drawable.ic_location_off, stringResource(R.string.msg_no_places), Modifier.height(240.dp)) }
                // The map card first (SPEC §13.1), opening the map page
                else item(key = "map") { MapCard(onOpenMap, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
                items(shown.countries, key = { "c${it.country}" }) { country ->
                    val name = data.countryName(country.country, language)
                    PlaceHeader(name, country.count, onOpen = { onOpenCountry(country.country) })
                    TownRow(country.towns, data, language, onOpenTown)
                }
                if (shown.noLocation.isNotEmpty()) {
                    item(key = "none") {
                        // The settings pages' 16 dp at the sides: the group has none of its own (owner's test of S6b1)
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                            SettingsGroup {
                                row { shapes ->
                                    SettingsNavigationRow(
                                        shapes, R.drawable.ic_location_off, stringResource(R.string.label_no_location),
                                        stringResource(R.string.desc_no_location_count, shown.noLocation.size),
                                        onClick = onOpenNoLocation,
                                    )
                                }
                            }
                        }
                    }
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

/**
 * A country's page (SPEC §13.1): Places one level down – its regions as headers, each with a row of its towns;
 * towns outside every region last, under the country's name.
 */
@Composable
fun CountryScreen(country: Int, onBack: () -> Unit, onOpenTown: (Int) -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val groups by app.placeGroups.collectAsStateWithLifecycle()
    val data = placeData()
    val language = placeLanguage()
    val group = groups?.country(country)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            title = { Text(data?.countryName(country, language).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
        )
        if (group == null || data == null) return@Column
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(group.regions, key = { "r${it.region}" }) { region ->
                val name = if (region.region >= 0) data.regionName(region.region, language) else data.countryName(country, language)
                PlaceHeader(name, region.count, onOpen = null)
                TownRow(region.towns, data, language, onOpenTown)
            }
            item { Box(Modifier.navigationBarsPadding()) }
        }
    }
}

/**
 * A country's or region's header (owner's test of S6b1, over SPEC §13.1's name-and-number line): the name in
 * `primary`, "Items: n" under it as everywhere in the app, and the chevron at the end when it leads to a page.
 */
@Composable
private fun PlaceHeader(name: String, count: Int, onOpen: (() -> Unit)?) {
    val label = stringResource(R.string.content_description_show_place, name)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = label, onClick = onOpen) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.label_album_count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (onOpen != null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Towns as a carousel (owner's choice after the test of S6b1): two and more as SPEC §13.1's multi-browse carousel,
 * one as an uncontained one (an uncontained carousel of two didn't line up; with items this wide two multi-browse well); each
 * town's name and "Items: n" on its own picture ([TownBadge]) – one caption under the carousel read as the first
 * picture's. 156 dp high, as Search's (SPEC §12.1): at 112 the badge covered half the picture.
 */
@Composable
private fun TownRow(towns: List<TownGroup>, data: PlaceData, language: PlaceLanguage, onOpenTown: (Int) -> Unit) {
    if (towns.isEmpty()) return
    val shape = MaterialTheme.shapes.extraLarge
    val state = rememberCarouselState { towns.size }
    val modifier = Modifier.fillMaxWidth().height(156.dp)
    val item: @Composable CarouselItemScope.(Int) -> Unit = { index ->
        val town = towns[index]
        Box(Modifier.fillMaxSize().maskClip(shape).clickable { onOpenTown(town.settlement) }) {
            MediaThumbnail(town.items.first(), THUMB_PX, Modifier.fillMaxSize())
            TownBadge(data.settlementName(town.settlement, language), town.items.size, carouselItemDrawInfo)
        }
    }
    if (towns.size == 1) {
        HorizontalUncontainedCarousel(state, itemWidth = 220.dp, modifier = modifier, itemSpacing = 8.dp, contentPadding = PaddingValues(horizontal = 16.dp), content = item)
    } else {
        HorizontalMultiBrowseCarousel(state, preferredItemWidth = 220.dp, modifier = modifier, itemSpacing = 8.dp, contentPadding = PaddingValues(horizontal = 16.dp), content = item)
    }
}

/**
 * A town's name and count on its picture, at the bottom start of the part the carousel shows (an item keeps its full
 * size under the carousel's mask). Theme roles only, no see-through: `surfaceContainerHigh` under `onSurface` text.
 * It fades out as the item narrows below [BADGE_FULL] and is gone below [BADGE_GONE]; read while drawing and laying
 * out, so scrolling doesn't compose it again.
 */
@Composable
private fun BoxScope.TownBadge(name: String, count: Int, info: CarouselItemDrawInfo) {
    val density = LocalDensity.current
    val gone = with(density) { BADGE_GONE.toPx() }
    val full = with(density) { BADGE_FULL.toPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .layout { measurable, constraints ->
                // As wide as its text, at most the shown part less the margins; placed at that part's start
                val shown = info.maskRect
                val max = (shown.width.roundToInt() - margin * 2).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = max.coerceAtMost(constraints.maxWidth)))
                layout(placeable.width, placeable.height) { placeable.place(shown.left.roundToInt() + margin, -margin) }
            }
            .graphicsLayer { alpha = ((info.size - gone) / (full - gone)).coerceIn(0f, 1f) },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.label_album_count, count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val BADGE_GONE = 88.dp
private val BADGE_FULL = 128.dp

/** The carousel's largest item is about 300 dp wide on a phone. */
private const val THUMB_PX = 768
