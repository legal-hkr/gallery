package sh.hnet.gallery.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val THUMB_HEIGHT = 48.dp
private val THUMB_TOUCH = 48.dp

/**
 * Music's fast scroller as Contacts has it (SPEC §4.6), for a grid: a thumb on the end edge, shown
 * while the grid scrolls and 1.5 s after; dragged, it moves the grid by its place (evenly by item
 * count) and a bubble shows the month there. Hidden from TalkBack, which pages.
 *
 * @param label The bubble's text for the entry at an index
 */
@Composable
fun GridFastScroller(state: LazyGridState, itemCount: Int, label: (Int) -> String, modifier: Modifier = Modifier) {
    if (itemCount < 2) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    val count by rememberUpdatedState(itemCount)
    val labelNow by rememberUpdatedState(label)

    BoxWithConstraints(modifier.fillMaxHeight().clearAndSetSemantics {}) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - THUMB_HEIGHT).toPx() }.coerceAtLeast(1f)
        // Read through derived state: the scroll position changes every frame, the thumb only when its place does
        val listFraction by remember(state, itemCount) {
            derivedStateOf { (state.firstVisibleItemIndex.toFloat() / (itemCount - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)).coerceIn(0f, 1f) }
        }
        val fraction = if (dragging) dragFraction else listFraction
        val thumbY = (fraction * trackPx).roundToInt()
        val trackPxNow by rememberUpdatedState(trackPx)
        val listFractionNow by rememberUpdatedState(listFraction)

        AnimatedVisibility(
            visible = shown || dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (dragging) {
                    Box(
                        Modifier
                            .widthIn(min = 64.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 6.dp, bottomStart = 24.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            labelNow((fraction * (itemCount - 1)).roundToInt()),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    Modifier
                        .size(THUMB_TOUCH, THUMB_HEIGHT)
                        .pointerInput(Unit) {
                            var job: Job? = null
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragFraction = listFractionNow
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragFraction = (dragFraction + amount / trackPxNow).coerceIn(0f, 1f)
                                val target = (dragFraction * (count - 1)).roundToInt()
                                job?.cancel()
                                job = scope.launch { state.scrollToItem(target) }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(4.dp, THUMB_HEIGHT)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
