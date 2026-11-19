package sh.hnet.gallery.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import sh.hnet.gallery.R
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.GridStep
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Timeline
import sh.hnet.gallery.model.TimelineEntry
import sh.hnet.gallery.ui.components.MediaThumbnail

/** Where the grid should stand after a change of step: the item under the fingers, at their height (SPEC §4.4). */
private data class Anchor(val id: Long, val y: Float, val months: Boolean)

/**
 * The photo grid of every list of items (SPEC §4.4, §4.5): day or month headers spanning the
 * width, square tiles, the pinch between steps. The pinch scales the grid with the fingers and
 * settles on the nearest step when they lift, keeping the tile under them under them. There is no
 * standard component for it: a gesture over `LazyVerticalGrid`, taking two fingers before the grid
 * scrolls with them.
 *
 * @param today The phone's today, for "Today" and "Yesterday"
 * @param top Items before the entries (cards that scroll away with the grid)
 * @param onOpen A tap on a tile outside the month view
 */
@Composable
fun MediaGrid(
    timeline: Timeline,
    step: GridStep,
    onStepChange: (GridStep) -> Unit,
    facts: Map<Long, Fact>,
    state: LazyGridState,
    today: LocalDate,
    modifier: Modifier = Modifier,
    top: LazyGridScope.() -> Unit = {},
    topCount: Int = 0,
    onOpen: (MediaItem) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val stepNow by rememberUpdatedState(step)
    val onStepChangeNow by rememberUpdatedState(onStepChange)

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Tablets get more columns at every step (SPEC §23)
        val widthFactor = when {
            maxWidth >= 840.dp -> 2f
            maxWidth >= 600.dp -> 1.5f
            else -> 1f
        }
        val columns = (step.columns * widthFactor).toInt()
        val gap = if (step.months) 1.dp else 2.dp
        val corner = if (step.months) 2.dp else 6.dp
        val density = LocalDensity.current
        val cellPx = with(density) { ((maxWidth - gap * (columns - 1)) / columns).toPx() }

        /** The tile at a point of the grid, or the nearest one. */
        fun tileAt(point: Offset): Long? = state.layoutInfo.visibleItemsInfo
            .mapNotNull { info -> (info.key as? String)?.toLongOrNull()?.let { it to info } }
            .minByOrNull { (_, info) ->
                val cx = info.offset.x + info.size.width / 2f
                val cy = info.offset.y + info.size.height / 2f
                abs(cx - point.x) + abs(cy - point.y)
            }?.first

        // After a change of step: the anchored tile back under the point it was at, once the entries are those of the new step
        LaunchedEffect(timeline, columns) {
            val target = anchor ?: return@LaunchedEffect
            if (timeline.months != target.months) return@LaunchedEffect
            val index = timeline.indexOf[target.id]
            if (index != null) {
                state.scrollToItem(index + topCount)
                state.scrollBy(-(target.y - cellPx / 2f))
            }
            anchor = null
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoom = 1f
                        var pinching = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2) {
                                pinching = true
                                zoom *= event.calculateZoom()
                                pivot = event.calculateCentroid(useCurrent = true)
                                scale = zoom.coerceIn(0.4f, 2.5f)
                            }
                            // Two fingers belong to the pinch, also while one of them lifts first: the grid doesn't scroll with them
                            if (pinching) event.changes.forEach { it.consume() }
                        }
                        if (pinching) {
                            val current = stepNow
                            val target = GridStep.nearest(current.columns / zoom)
                            if (target != current) {
                                tileAt(pivot)?.let { anchor = Anchor(it, pivot.y, target.months) }
                                onStepChangeNow(target)
                            }
                            scale = 1f
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (size.width > 0 && size.height > 0) transformOrigin = TransformOrigin(pivot.x / size.width, pivot.y / size.height)
                },
        ) {
            top()
            items(
                timeline.entries,
                key = { it.key },
                span = { if (it is TimelineEntry.Tile) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
                contentType = { it::class },
            ) { entry ->
                when (entry) {
                    is TimelineEntry.Day -> DayHeader(entry.date, today, Modifier.animateItem())
                    is TimelineEntry.Month -> MonthHeader(entry.month, today, Modifier.animateItem())
                    is TimelineEntry.Tile -> Tile(
                        entry.item,
                        facts[entry.item.id],
                        cellPx.toInt(),
                        corner,
                        marks = !step.months,
                        onClick = {
                            if (step.months) {
                                // A tile of the month view opens step 2 at its day, not the viewer (SPEC §4.4)
                                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.key }
                                val y = info?.let { it.offset.y + it.size.height / 2f } ?: 0f
                                anchor = Anchor(entry.item.id, y, months = false)
                                onStepChange(GridStep.MEDIUM)
                            } else {
                                onOpen(entry.item)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** A day's header (SPEC §4.3): "Today", "Yesterday", the date with the weekday, the year only when it isn't this one. */
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val text = when (date) {
        today -> stringResource(R.string.label_today)
        today.minusDays(1) -> stringResource(R.string.label_yesterday)
        else -> formatDate(stringResource(if (date.year == today.year) R.string.format_day else R.string.format_day_year), date, locale)
    }
    Header(text, modifier)
}

/** A month's header in the month view (SPEC §4.4). */
@Composable
private fun MonthHeader(month: YearMonth, today: LocalDate, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Header(formatDate(stringResource(if (month.year == today.year) R.string.format_month else R.string.format_month_year), month.atDay(1), locale), modifier)
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A date by a CLDR pattern in the app's language, its first letter capitalized (SPEC §4.3). */
fun formatDate(pattern: String, date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(date).replaceFirstChar { it.titlecase(locale) }

/** A video's length as the tile shows it: 0:42, 12:05, 1:02:33. */
fun formatDuration(ms: Long): String {
    val total = (ms + 500) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds) else "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * A square tile (SPEC §4.5): the thumbnail cut to the square, rounded corners, and in steps 1–3 the
 * marks: at the top end the video's length, a motion photo or RAW; at the bottom start a favorite.
 */
@Composable
private fun Tile(item: MediaItem, fact: Fact?, px: Int, corner: Dp, marks: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).clickable(onClick = onClick)) {
        MediaThumbnail(item, px, Modifier.fillMaxSize())
        if (!marks) return@Box
        when {
            item.video -> Mark(Modifier.align(Alignment.TopEnd)) {
                Text(formatDuration(item.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            fact?.motion == true -> Mark(Modifier.align(Alignment.TopEnd)) {
                Icon(painterResource(R.drawable.ic_motion_photos_on), contentDescription = stringResource(R.string.content_description_motion_photo), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
            }
            item.raw -> Mark(Modifier.align(Alignment.TopEnd)) {
                Text(stringResource(R.string.label_raw), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (item.favorite) {
            Mark(Modifier.align(Alignment.BottomStart)) {
                Icon(painterResource(R.drawable.ic_favorite_filled), contentDescription = stringResource(R.string.content_description_favorite), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A small opaque pill over the tile (SPEC §4.5): `surface-container`, 20 dp high, 10 dp corners. */
@Composable
private fun Mark(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .padding(4.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
