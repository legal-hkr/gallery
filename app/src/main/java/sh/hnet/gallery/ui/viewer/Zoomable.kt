package sh.hnet.gallery.ui.viewer

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.gallery.model.Box
import sh.hnet.gallery.model.Dim
import sh.hnet.gallery.model.Vec
import sh.hnet.gallery.model.ZoomMath

/**
 * The viewer's zoom (SPEC §6.1, LOGIKA §13.2): News' zoom state, with the limits and the turn of
 * [ZoomMath] – up to the photo's own pixels, a double tap between fitted and filling the screen, the pager taking
 * the rest of a drag at the zoomed image's edge ([PagerHandoff]), and a turn of the phone keeping the centre point and the pixel size.
 *
 * Everything the gesture handler needs is read from this state when a gesture happens, so the handler
 * never works with values from an earlier composition.
 */
@Stable
class ZoomState internal constructor() {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The container in pixels; read for drawing the tiles of the visible part. */
    var container by mutableStateOf(IntSize.Zero)
        private set

    /** The displayed image's full size in pixels (the photo's own, turned upright); zero while unknown. */
    internal var image = IntSize.Zero
    internal var onSingleTap: () -> Unit = {}
    internal var onHold: (Boolean) -> Unit = {}
    internal var pager: PagerHandoff? = null

    private val containerDim get() = Dim(container.width.toFloat(), container.height.toFloat())
    private val imageDim get() = Dim(image.width.toFloat(), image.height.toFloat())
    private val maxScale get() = ZoomMath.maxScale(containerDim, imageDim)

    val zoomed: Boolean get() = scale > 1.01f

    /** The part of the image on screen, in image pixels. */
    fun visibleRegion(): Box = ZoomMath.visibleRegion(scale, offset.vec(), containerDim, imageDim)

    /** Screen pixels per image pixel when fitted. */
    fun fitScale(): Float = ZoomMath.fitScale(containerDim, imageDim)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    internal fun resize(size: IntSize) {
        val old = containerDim
        container = size
        if (old.empty || image == IntSize.Zero) return
        val (newScale, newOffset) = ZoomMath.resized(scale, offset.vec(), old, containerDim, imageDim)
        scale = newScale
        offset = newOffset.offset()
    }

    internal fun transformBy(zoom: Float, pan: Offset, centroid: Offset) {
        val newScale = (scale * zoom).coerceIn(1f, maxScale)
        val moved = ZoomMath.offsetFor(offset.vec(), scale, newScale, centroid.vec(), containerDim) + pan.vec()
        offset = ZoomMath.constrain(moved, newScale, containerDim, imageDim).offset()
        scale = newScale
    }

    internal fun panBy(pan: Offset) {
        offset = ZoomMath.constrain(offset.vec() + pan.vec(), scale, containerDim, imageDim).offset()
    }

    /** Whether the image's edge is reached in the direction of a sideways drag. */
    internal fun atEdge(dx: Float): Boolean = ZoomMath.atEdge(offset.vec(), scale, containerDim, imageDim, dx)

    /** Double tap (owner, 19 Nov 2026): back to fitted when zoomed, else filling the screen around the tapped point; more is the pinch's. */
    internal suspend fun toggleZoom(tap: Offset) {
        val targetScale = if (zoomed) 1f else ZoomMath.fillScale(containerDim, imageDim)
        val targetOffset = if (targetScale == 1f) Offset.Zero else {
            ZoomMath.constrain(ZoomMath.offsetFor(offset.vec(), scale, targetScale, tap.vec(), containerDim), targetScale, containerDim, imageDim).offset()
        }
        val startScale = scale
        val startOffset = offset
        animate(0f, 1f, animationSpec = tween(ANIMATION_MS)) { fraction, _ ->
            scale = startScale + (targetScale - startScale) * fraction
            offset = startOffset + (targetOffset - startOffset) * fraction
        }
    }

    private companion object {
        const val ANIMATION_MS = 250
    }
}

/**
 * A zoomed image's drag handed to the pages once the image's edge is reached (the owner, 19 Nov 2026):
 * the image only says so ([active]); the rest of that drag is read and applied by the viewer above the
 * pager ([pagerHandoff]), whose place doesn't move with the pages – read on the page, the finger's
 * movement shrank by the page's own movement and the handover stalled.
 */
@Stable
class PagerHandoff {
    /** From the image reaching its edge until the pages have settled after the finger lifted. */
    var active by mutableStateOf(false)
}

private fun Offset.vec() = Vec(x, y)

private fun Vec.offset() = Offset(x, y)

/**
 * @param image The displayed image's full size in pixels, zero while unknown
 * @param onSingleTap A tap that didn't become a double tap (toggles the chrome)
 * @param onHold A press held still for a moment starts (true) and its release ends (false) – a motion photo plays meanwhile
 */
@Composable
fun rememberZoomState(image: IntSize, onSingleTap: () -> Unit = {}, onHold: (Boolean) -> Unit = {}, pager: PagerHandoff? = null): ZoomState {
    val state = remember { ZoomState() }
    SideEffect {
        state.image = image
        state.onSingleTap = onSingleTap
        state.onHold = onHold
        state.pager = pager
    }
    return state
}

/**
 * Pinch to zoom towards the fingers, pan when zoomed (the image always stays on screen; at its edge the rest of the
 * drag moves the pages), double tap between fitted and filling the screen, single tap after the double-tap timeout, press and hold.
 * Gestures are read in the container's coordinates, before the zoom is applied. Single-finger drags at fitted
 * scale are left to the parent (the pager, the swipe down).
 */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .onSizeChanged { state.resize(it) }
    .pointerInput(state) { zoomGestures(state) }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

private const val TAP_TIMEOUT_MS = 300L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val HOLD_MS = 400L
private const val TAP_SLOP_PX = 30f
private const val DOUBLE_TAP_SLOP_PX = 100f

private suspend fun PointerInputScope.zoomGestures(state: ZoomState) = coroutineScope {
    var pendingSingleTap: Job? = null
    var animation: Job? = null
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A new touch takes over from a running double-tap animation
        animation?.cancel()
        // A touch the pager took before us – to stop its pages still moving – is never a tap: the pager keeps the rest of
        // that drag to itself, so the finger seemed not to move (owner's phone test: every second quick swipe toggled the chrome)
        val stolen = down.isConsumed
        var transformed = false
        var multiTouch = false
        var movement = 0f
        var holding = false
        var upTime = down.uptimeMillis
        // Zoomed at the image's edge, the rest of the drag moves the pages
        var paging = false

        while (true) {
            // Held still with one finger: after a moment it is a hold (SPEC §7.5)
            val event = if (!holding && !multiTouch && movement < TAP_SLOP_PX) {
                withTimeoutOrNull((HOLD_MS - (SystemClock.uptimeMillis() - down.uptimeMillis)).coerceAtLeast(1)) { awaitPointerEvent() } ?: run {
                    holding = true
                    pendingSingleTap?.cancel()
                    state.onHold(true)
                    null
                }
            } else {
                awaitPointerEvent()
            } ?: continue
            upTime = event.changes.first().uptimeMillis
            if (event.changes.size >= 2) {
                multiTouch = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    transformed = true
                    state.transformBy(zoom, pan, event.calculateCentroid())
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else {
                val change = event.changes.first()
                // The finger's way counts whoever moves with it
                movement += change.positionChangeIgnoreConsumed().getDistance()
                if (change.positionChanged()) {
                    val pan = change.position - change.previousPosition
                    // At fitted scale the drag belongs to the parent (the pager, the swipe down); zoomed, it pans the image
                    // until a sideways drag meets its edge, and from then on the whole rest of the drag moves the pages
                    val pager = state.pager
                    if (paging) {
                        // Handed over: every move of this gesture stays consumed here, whatever becomes of the zoom, so the pager's own
                        // drag never starts as well – both moving the pages took them two photos on (owner's phone test, 19 Nov 2026)
                        change.consume()
                    } else if (state.zoomed && !holding) {
                        if (pager != null && abs(pan.x) > abs(pan.y) && state.atEdge(pan.x)) {
                            paging = true
                            pager.active = true
                        } else {
                            state.panBy(pan)
                        }
                        transformed = true
                        change.consume()
                    }
                }
            }
            if (event.changes.none { it.pressed }) break
        }
        if (holding) {
            state.onHold(false)
            lastTapTime = 0L
            return@awaitEachGesture
        }
        if (paging) {
            lastTapTime = 0L
            return@awaitEachGesture
        }

        val isTap = !stolen && !transformed && !multiTouch && upTime - down.uptimeMillis < TAP_TIMEOUT_MS && movement < TAP_SLOP_PX
        pendingSingleTap?.cancel()
        when {
            !isTap -> lastTapTime = 0L
            lastTapTime > 0L && upTime - lastTapTime < DOUBLE_TAP_TIMEOUT_MS &&
                (down.position - lastTapPosition).getDistance() < DOUBLE_TAP_SLOP_PX -> {
                lastTapTime = 0L
                animation = launch { state.toggleZoom(down.position) }
            }
            else -> {
                lastTapTime = upTime
                lastTapPosition = down.position
                pendingSingleTap = launch {
                    delay(DOUBLE_TAP_TIMEOUT_MS)
                    state.onSingleTap()
                }
            }
        }
    }
}

/**
 * The viewer's side of [PagerHandoff], on an element that doesn't move with the pages: while the
 * handoff is active it moves the pages by the finger's sideways movement, read before the page sees it,
 * and on lifting settles them by how far and how fast ([onRelease] gets the velocity in pixels a second and ends
 * the handoff once they have settled).
 */
fun Modifier.pagerHandoff(handoff: PagerHandoff, onDrag: (Float) -> Unit, onRelease: (Float) -> Unit): Modifier = pointerInput(handoff) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val velocity = VelocityTracker()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            if (handoff.active && event.changes.size == 1) {
                velocity.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - change.previousPosition.x
                if (dx != 0f) onDrag(dx)
            }
            if (event.changes.none { it.pressed }) break
        }
        // The viewer ends the handoff once the pages have settled
        if (handoff.active) onRelease(velocity.calculateVelocity().x)
    }
}

