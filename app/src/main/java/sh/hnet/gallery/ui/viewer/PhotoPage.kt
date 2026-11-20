@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)

package sh.hnet.gallery.ui.viewer

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import sh.hnet.gallery.R
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.Dim
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Tile
import sh.hnet.gallery.model.Tiles
import sh.hnet.gallery.model.ZoomMath
import sh.hnet.gallery.ui.components.Thumb
import sh.hnet.gallery.ui.components.Thumbnails

/**
 * One photo in the viewer (SPEC §6.1, §6.9, §7.5; LOGIKA §13.2): its thumbnail at once, then the
 * whole photo for the screen cross-fading in (a loading indicator only after a second), then sharp
 * tiles of the visible part while zoomed past what the screen decode holds. A motion photo's video
 * plays over it; a file that can't be decoded shows the family's problem card.
 *
 * @param motion Whether the motion should play now (settled, the toggle, a hold)
 * @param onPicture The decoded picture, for the window's HDR mode and the chrome's toggles
 * @param onHold A hold starts (true) or ends (false)
 */
@Composable
fun PhotoPage(
    item: MediaItem,
    fact: Fact?,
    motion: Boolean,
    holding: Boolean,
    sound: Boolean,
    onMotionEnded: () -> Unit,
    onPicture: (Picture) -> Unit,
    onTap: () -> Unit,
    onHold: (Boolean) -> Unit,
    onOpenWith: (() -> Unit)?,
    zoomKey: Any,
    pager: PagerHandoff? = null,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val window = LocalWindowInfo.current.containerSize
    val longSide = maxOf(window.width, window.height)
    val thumbPx = Thumbnails.sizeFor(Int.MAX_VALUE)
    val thumb by produceState(Thumbnails.peek(item, thumbPx), item.id, item.generation) {
        if (value == null) value = Thumbnails.load(resolver, item, thumbPx)
    }
    val picture by produceState<Picture?>(null, item.id, item.generation, longSide) { value = PhotoDecoder.screen(resolver, item, longSide) }
    val tiles by produceState<TileSource?>(null, item.id, item.generation) {
        // Opened whole even if the page leaves meanwhile, and then closed: a result thrown away with the cancellation
        // kept its descriptor and native decoder until a finalizer (review before v0.1.0)
        val opened = withContext(NonCancellable) { TileSource.open(resolver, item) }
        if (isActive) value = opened else opened?.close()
    }
    DisposableEffect(tiles) { onDispose { tiles?.close() } }
    val pictureNow by rememberUpdatedState(onPicture)
    LaunchedEffect(picture) { picture?.let { pictureNow(it) } }

    val current = picture
    if (current is Picture.Broken) {
        BrokenFile(onOpenWith)
        return
    }
    val full = when (current) {
        is Picture.Still -> tiles?.displayed ?: current.full
        is Picture.Animated -> current.full
        else -> (thumb as? Thumb.Image)?.bitmap?.let { IntSize(it.width, it.height) } ?: IntSize.Zero
    }
    val zoom = rememberZoomState(full, onSingleTap = onTap, onHold = onHold, pager = pager)
    LaunchedEffect(zoomKey) { zoom.reset() }

    // The loading indicator only when the screen decode takes over a second (SPEC §6.9)
    var slow by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, current == null) {
        if (current == null) { delay(1000); slow = true } else slow = false
    }
    val fade by animateFloatAsState(if (current != null) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "picture")

    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().zoomable(zoom), contentAlignment = Alignment.Center) {
            // The thumbnail only until the photo has faded in over it: two full-screen layers would be drawn every frame of a swipe
            if (fade < 1f) (thumb as? Thumb.Image)?.let { Image(it.bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            when (current) {
                is Picture.Still -> {
                    Image(current.image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade })
                    tiles?.let { ZoomTiles(item, it, current, zoom) }
                }
                is Picture.Animated -> AndroidView(
                    factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = { view ->
                        view.setImageDrawable(current.drawable)
                        current.drawable.start()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> {}
            }
            if (fact?.motion == true && fact.motionLength != null && full != IntSize.Zero) {
                FittedBox(full, zoom) { modifier ->
                    MotionVideo(item, fact.motionLength, playing = motion, loop = holding, sound = sound, onEnded = onMotionEnded, modifier = modifier)
                }
            }
        }
        if (slow && current == null) LoadingIndicator(Modifier.align(Alignment.Center))
    }
}

/** A layer of the photo's fitted size, centred in the zoomed box: where the motion video lies over the still. */
@Composable
private fun FittedBox(full: IntSize, zoom: ZoomState, content: @Composable (Modifier) -> Unit) {
    val container = zoom.container
    val fit = ZoomMath.fitScale(Dim(container.width.toFloat(), container.height.toFloat()), Dim(full.width.toFloat(), full.height.toFloat()))
    if (fit <= 0f) return
    val density = LocalDensity.current
    val width = with(density) { (full.width * fit).toDp() }
    val height = with(density) { (full.height * fit).toDp() }
    content(Modifier.requiredSize(width, height))
}

/**
 * The sharp tiles of the visible part (LOGIKA §13.2, level 3): only once the zoom asks for more
 * pixels than the screen decode has, decoded two at a time a moment after the zoom settles, drawn
 * over the screen decode in the zoomed layer (the layer's scale then draws them at full sharpness).
 */
@Composable
private fun ZoomTiles(item: MediaItem, source: TileSource, still: Picture.Still, zoom: ZoomState) {
    val loaded = remember(item.id, item.generation) { mutableStateMapOf<String, ImageBitmap>() }
    var wanted by remember(item.id) { mutableStateOf(emptyList<Pair<String, Tile>>()) }
    val full = source.displayed
    val image = Dim(full.width.toFloat(), full.height.toFloat())

    LaunchedEffect(source) {
        snapshotFlow { Triple(zoom.scale, zoom.offset, zoom.container) }
            .debounce(TILE_SETTLE_MS)
            .collectLatest { (scale, _, container) ->
                val fit = ZoomMath.fitScale(Dim(container.width.toFloat(), container.height.toFloat()), image)
                // Enough pixels in the screen decode: no tiles
                if (fit <= 0f || 1f / still.sampled >= fit * scale) {
                    wanted = emptyList()
                    return@collectLatest
                }
                val sample = ZoomMath.sampleFor(scale, fit)
                val tiles = Tiles.covering(zoom.visibleRegion(), sample, image)
                wanted = tiles.map { TileCache.key(item, it) to it }
                for ((key, tile) in wanted) {
                    if (key in loaded) continue
                    val bitmap: Bitmap = TileCache[key] ?: withContext(Dispatchers.IO) { source.decode(tile) }?.also { TileCache.put(key, it) } ?: continue
                    loaded[key] = bitmap.asImageBitmap()
                }
            }
    }

    Canvas(Modifier.fillMaxSize()) {
        val fit = ZoomMath.fitScale(Dim(size.width, size.height), image)
        if (fit <= 0f) return@Canvas
        val left = (size.width - image.width * fit) / 2f
        val top = (size.height - image.height * fit) / 2f
        for ((key, tile) in wanted) {
            val bitmap = loaded[key] ?: continue
            val target = tile.region.inFitted(left, top, fit)
            drawImage(
                bitmap,
                dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
                dstSize = IntSize((target.right - target.left).roundToInt().coerceAtLeast(1), (target.bottom - target.top).roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

/** How long the zoom must rest before the tiles of the new view are decoded. */
private const val TILE_SETTLE_MS = 80L

/** The family's problem card (SPEC §6.9): the file can't be shown; Open with hands it to another app. Videos that can't be played too. */
@Composable
internal fun BrokenFile(onOpenWith: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_broken_image), contentDescription = null)
                    Text(stringResource(R.string.error_cannot_display), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                // Not for the vault's items: they are handed to no other app
                if (onOpenWith != null) TextButton(onClick = onOpenWith, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.menu_open_with), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
