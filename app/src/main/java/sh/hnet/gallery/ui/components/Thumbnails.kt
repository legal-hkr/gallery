package sh.hnet.gallery.ui.components

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import sh.hnet.gallery.R
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.MediaItem

/**
 * Thumbnails from the system's own cache (`loadThumbnail`), kept in memory only (SPEC §22.8): an LRU
 * of a sixth of the app's memory, by item, its version and the size in pixels. Sizes are rounded up
 * to a few steps, so the grid's steps share what was read.
 */
object Thumbnails {
    // Hardware bitmaps (see [stored]) live in graphics memory, not the app's heap: the budget is half the heap's size rather
    // than a sixth – a sixth (about 40 MB on a Pixel) held a dozen tiles of the two-column step, and the Albums tab's covers
    // pushed the Photos tab's out, which then read again on coming back (owner's phone test)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 2).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * The budget from the app's ordinary memory class, not the large heap the editor needs (D25): with `largeHeap` half
     * the heap meant some 256 MB of graphics buffers, and the emulator ran out of them ("Failed to allocate ColorBuffer",
     * the owner's demo set, 20 Nov 2026). Set once at the start.
     */
    fun budget(memoryClassMb: Int) {
        cache.resize(memoryClassMb * 1024 * 1024 / 2)
    }

    /** Items whose thumbnail the system couldn't make, by the same key: not asked again until the file changes. */
    private val broken = HashSet<String>()

    /** Four thumbnails read at a time (LOGIKA §3). */
    private val reading = Dispatchers.IO.limitedParallelism(4)

    // 768 for the two-column step (cells of ~540 px on a phone; 512 would be stretched)
    private val STEPS = intArrayOf(128, 256, 384, 512, 768)

    /** Everything of items from outside MediaStore dropped (negative ids): the vault's, once it locks (SPEC §18.4). */
    fun forgetOutside() {
        cache.snapshot().keys.filter { it.startsWith("-") }.forEach(cache::remove)
        synchronized(broken) { broken.removeAll { it.startsWith("-") } }
    }

    /** The size actually read for a cell of [px] pixels. */
    fun sizeFor(px: Int): Int = STEPS.firstOrNull { it >= px } ?: STEPS.last()

    /** The whole picture fitted in a square of the size (the system's thumbnail). */
    private fun key(item: MediaItem, px: Int) = "${item.id}.${item.generation}@${sizeFor(px)}"

    /** The picture's middle square, for tiles of pictures far from square (see [load]). */
    private fun squareKey(item: MediaItem, px: Int) = key(item, px) + "#square"

    /**
     * What is known without reading: the thumbnail in memory, [Thumb.Broken], or null. For a tile ([crop]) the
     * whole picture counts only when its shorter side fills the tile well enough; else its middle square.
     */
    fun peek(item: MediaItem, px: Int, crop: Boolean = false): Thumb? {
        if (crop) cache.get(squareKey(item, px))?.let { return Thumb.Image(it.asImageBitmap()) }
        val key = key(item, px)
        cache.get(key)?.let { whole -> return if (crop && thinForTile(whole, item, px)) null else Thumb.Image(whole.asImageBitmap()) }
        return if (synchronized(broken) { key in broken }) Thumb.Broken else null
    }

    /**
     * The largest thumbnail of the item already in memory, at any size: the pinch's layer reads nothing. [whole]
     * only the whole picture (the viewer's flight fits the picture, a middle square would be the wrong shape).
     */
    fun peekAny(item: MediaItem, whole: Boolean = false): Thumb? {
        for (size in STEPS.reversed()) {
            if (!whole) cache.get(squareKey(item, size))?.let { return Thumb.Image(it.asImageBitmap()) }
            cache.get(key(item, size))?.let { return Thumb.Image(it.asImageBitmap()) }
        }
        return null
    }

    /**
     * The thumbnail for a cell of [px] pixels: the system's, fitted in a square of the next size step. A tile ([crop])
     * of a picture far from square – a panorama, a screenshot – would show only a thin middle part of that, stretched
     * (the owner's phone test, 19 Nov 2026: a 4:1 panorama got 768 × 192 for a 540 px tile), so for those the
     * picture's middle square is decoded by the app at the size step.
     */
    suspend fun load(resolver: ContentResolver, item: MediaItem, px: Int, crop: Boolean = false): Thumb {
        peek(item, px, crop)?.let { return it }
        val key = key(item, px)
        val size = sizeFor(px)
        return withContext(reading) {
            // A tile scrolled away cancels its reading, also inside the system's call
            val signal = CancellationSignal()
            val handle = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
            try {
                // The system makes thumbnails of MediaStore's items only: a file from outside is decoded here (SPEC §17.2)
                val whole = cache.get(key) ?: stored(if (item.outside) decodeOutside(resolver, item, size) else resolver.loadThumbnail(item.uri(), Size(size, size), signal)).also { cache.put(key, it) }
                if (crop && thinForTile(whole, item, px)) {
                    decodeSquare(resolver, item, size, signal)?.let(::stored)?.let { square ->
                        cache.put(squareKey(item, px), square)
                        return@withContext Thumb.Image(square.asImageBitmap())
                    }
                }
                Thumb.Image(whole.asImageBitmap())
            } catch (e: CancellationException) {
                throw e
            } catch (_: OperationCanceledException) {
                throw CancellationException()
            } catch (_: Exception) {
                // Unreadable, or not a picture at all (SPEC §4.5)
                synchronized(broken) { broken.add(key) }
                Thumb.Broken
            } finally {
                handle.dispose()
            }
        }
    }
}

/**
 * Whether the whole picture's shorter side falls well short of a tile of [px] pixels (under three quarters) while
 * the photo itself has more to give: the tile would show it stretched. Ordinary 4:3 and 3:2 photos fill their
 * tiles from the system's thumbnail; videos keep the system's frame.
 */
private fun thinForTile(bitmap: Bitmap, item: MediaItem, px: Int): Boolean {
    if (item.video) return false
    val need = minOf(px, Thumbnails.sizeFor(px))
    val short = minOf(bitmap.width, bitmap.height)
    val original = minOf(item.width, item.height)
    return short * 4 < need * 3 && (original == 0 || original * 2 > short * 3)
}

/**
 * The picture's middle square at [size] pixels (smaller only when the picture is), upright by its EXIF: scaled so
 * its shorter side is [size], then cropped – the decoder keeps only the square. Null when it can't be decoded.
 */
private fun decodeSquare(resolver: ContentResolver, item: MediaItem, size: Int, signal: CancellationSignal): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri())) { decoder, info, _ ->
        signal.throwIfCanceled()
        val width = info.size.width
        val height = info.size.height
        val scale = minOf(1f, size.toFloat() / minOf(width, height))
        val targetWidth = maxOf(1, (width * scale).roundToInt())
        val targetHeight = maxOf(1, (height * scale).roundToInt())
        if (scale < 1f) decoder.setTargetSize(targetWidth, targetHeight)
        val side = minOf(targetWidth, targetHeight)
        val left = (targetWidth - side) / 2
        val top = (targetHeight - side) / 2
        decoder.crop = Rect(left, top, left + side, top + side)
        // Software here; kept as a hardware bitmap as every thumbnail is ([stored])
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (e: OperationCanceledException) {
    throw e
} catch (_: Exception) {
    null
}

/** A file from outside fitted in [size] × [size]: a photo by `ImageDecoder`, a video's frame by `MediaMetadataRetriever`. */
private fun decodeOutside(resolver: ContentResolver, item: MediaItem, size: Int): Bitmap {
    if (item.video) {
        return MediaMetadataRetriever().use { retriever ->
            resolver.openFileDescriptor(item.uri(), "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: error("No file")
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size) ?: error("No frame")
        }
    }
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri())) { decoder, info, _ ->
        val scale = minOf(1f, size.toFloat() / maxOf(info.size.width, info.size.height))
        if (scale < 1f) decoder.setTargetSize(maxOf(1, (info.size.width * scale).roundToInt()), maxOf(1, (info.size.height * scale).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

/**
 * The thumbnail as kept: a hardware bitmap, uploaded to the GPU here, off the screen's threads, instead of by the render
 * thread the first time a tile is drawn while scrolling; the software one is let go. Kept as it is where the copy fails.
 */
private fun stored(bitmap: Bitmap): Bitmap {
    if (bitmap.config == Bitmap.Config.HARDWARE) return bitmap
    val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return bitmap
    bitmap.recycle()
    return hardware
}

/** A thumbnail read, or the sign that it can't be. */
sealed interface Thumb {
    data class Image(val bitmap: ImageBitmap) : Thumb

    data object Broken : Thumb
}

/**
 * An item's thumbnail cut to fill [modifier]'s bounds (a square tile, SPEC §4.5): the image when
 * read, `broken_image` on the highest container when it can't be, the lowest container before.
 *
 * @param px The cell's size in pixels
 */
@Composable
fun MediaThumbnail(item: MediaItem, px: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val resolver = LocalContext.current.contentResolver
    // Until its own size is read, a size already in memory stands in: a tile that a pinch's change brought on screen
    // went dark until its size was read, after the change's layer had shown it from memory (owner's phone test, 19 Nov 2026)
    val crop = contentScale == ContentScale.Crop
    // The state outlives a change of size (a tile kept across the grid's steps): the size in memory has to be put in
    // it too, not only one read – a tile whose new size the pinch had already read kept the old, smaller one (owner's
    // test, 19 Nov 2026)
    val thumb by produceState(Thumbnails.peek(item, px, crop) ?: Thumbnails.peekAny(item), item.id, item.generation, Thumbnails.sizeFor(px), crop) {
        val ready = Thumbnails.peek(item, px, crop)
        if (ready != null) value = ready else value = Thumbnails.load(resolver, item, px, crop)
    }
    when (val current = thumb) {
        is Thumb.Image -> Image(current.bitmap, contentDescription = null, contentScale = contentScale, modifier = modifier)
        Thumb.Broken -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_broken_image), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        null -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer))
    }
}
