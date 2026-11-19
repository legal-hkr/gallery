package sh.hnet.gallery.ui.viewer

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.ui.components.Thumb
import sh.hnet.gallery.ui.components.Thumbnails

/**
 * What opens the viewer: the list's ids in its order, the item tapped, and how the list follows
 * the item shown (it scrolls there, so the flight back lands on its tile).
 */
class ViewerRequest(val ids: List<Long>, val start: Long, val onPage: (Long) -> Unit)

/** The item flying between its tile and the viewer: its thumbnail, cropped to a moving rect, over a fading backdrop. */
private class Flight(
    val image: ImageBitmap,
    val from: Rect,
    val to: Rect,
    val fromCornerPx: Float,
    val toCornerPx: Float,
    val fromBackdrop: Float,
    val toBackdrop: Float,
) {
    val progress = Animatable(0f)

    suspend fun run() {
        progress.animateTo(1f, tween(FLIGHT_MS, easing = StandardEasing))
    }
}

private const val FLIGHT_MS = 350

/** Longest wait, after landing, for the viewer to show the item before it takes over from the still. */
private const val LANDING_WAIT_MS = 500L

/**
 * The viewer over the shell (LOGIKA §1, §13.1; News' image viewer host): it flies the item out of
 * its tile on open and back into it on close – from wherever a swipe down left it –, and fades
 * instead when the tile isn't on screen or the phone is turned (the grid is portrait: SPEC §6.8).
 * While it is open the activity turns with the phone, unless the system's rotation lock is on (D1).
 */
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun ViewerHost(request: ViewerRequest, snackbars: SnackbarHostState, messageScope: CoroutineScope, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val activity = LocalActivity.current
    val origins = LocalMediaOrigins.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var flight by remember { mutableStateOf<Flight?>(null) }
    // The viewer itself is visible (not while the still flies)
    var shown by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(request.start) }
    val loaded = remember { mutableStateOf(emptySet<Long>()) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Turns with the phone while open, portrait again after (owner's decision D1: the system's lock is respected; the rest of the app is portrait by the family's rule)
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    // The tile stays empty while its item is in the viewer or flying
    SideEffect { origins.hiddenKey = if (shown || flight != null) current.toString() else null }
    DisposableEffect(origins) { onDispose { origins.hiddenKey = null } }

    /** The thumbnail already in memory for an item, the largest there is. */
    fun still(id: Long): ImageBitmap? {
        val item = app.index.items.value?.firstOrNull { it.id == id } ?: return null
        for (px in intArrayOf(512, 384, 256, 128)) (Thumbnails.peek(item, px) as? Thumb.Image)?.let { return it.bitmap }
        return null
    }

    // Fly out of the tile on open
    LaunchedEffect(Unit) {
        val key = request.start
        val origin = origins[key.toString()]
        val picture = still(key)
        val screen = snapshotFlow { size }.first { it != IntSize.Zero }
        if (origin != null && origin.visible && picture != null && picture.height > 0 && !landscape) {
            val out = Flight(
                image = picture,
                from = origin.bounds,
                to = Rect(Offset.Zero, screen.toSize()).fitted(picture.width.toFloat() / picture.height),
                fromCornerPx = origin.cornerPx, toCornerPx = 0f, fromBackdrop = 0f, toBackdrop = 1f,
            )
            flight = out
            out.run()
            // Closed meanwhile (Back during the flight): the flight back has taken over
            if (flight !== out) return@LaunchedEffect
            // Let the viewer show the item under the still before it goes, so nothing blinks
            withTimeoutOrNull(LANDING_WAIT_MS) { snapshotFlow { key in loaded.value }.first { it } }
            if (flight !== out) return@LaunchedEffect
            shown = true
            withFrameNanos { }
            if (flight === out) flight = null
        } else {
            shown = true
        }
    }

    // Fly back into the tile, from where the swipe left the item; in landscape the viewer fades instead
    val requestClose: (ViewerCloseFrom, Long) -> Boolean = requestClose@{ from, id ->
        current = id
        val origin = origins[id.toString()]
        val picture = still(id)
        if (landscape || origin == null || !origin.visible || picture == null || picture.height <= 0 || size == IntSize.Zero) return@requestClose false
        val screen = Rect(Offset.Zero, size.toSize())
        val back = Flight(
            image = picture,
            from = screen.fitted(picture.width.toFloat() / picture.height).transformed(screen.center, from.scale, from.offsetY),
            to = origin.bounds,
            fromCornerPx = from.cornerPx, toCornerPx = origin.cornerPx, fromBackdrop = from.backdropAlpha, toBackdrop = 0f,
        )
        flight = back
        shown = false
        scope.launch {
            back.run()
            // The flight stays until the viewer is gone, then the grid shows the tile again in its place
            onClose()
        }
        true
    }

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (shown) 1f else 0f }) {
            ViewerScreen(
                request.ids, request.start, snackbars, messageScope,
                onPage = {
                    current = it
                    request.onPage(it)
                },
                onLoaded = { loaded.value += it },
                onClose = onClose,
                onRequestClose = requestClose,
            )
        }
        flight?.let { FlightLayer(it) }
    }
}

@Composable
private fun FlightLayer(flight: Flight) {
    val progress = flight.progress.value
    val rect = lerp(flight.from, flight.to, progress)
    val corner = with(LocalDensity.current) { lerp(flight.fromCornerPx, flight.toCornerPx, progress).toDp() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = lerp(flight.fromBackdrop, flight.toBackdrop, progress))))
    Image(
        bitmap = flight.image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
            }
            .clip(RoundedCornerShape(corner)),
    )
}

/** The rect after the swipe's transform of the viewer: scaled about [pivot], then moved down by [offsetY]. */
private fun Rect.transformed(pivot: Offset, scale: Float, offsetY: Float): Rect = Rect(
    left = pivot.x + (left - pivot.x) * scale,
    top = pivot.y + (top - pivot.y) * scale + offsetY,
    right = pivot.x + (right - pivot.x) * scale,
    bottom = pivot.y + (bottom - pivot.y) * scale + offsetY,
)
