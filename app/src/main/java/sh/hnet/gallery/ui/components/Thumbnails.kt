package sh.hnet.gallery.ui.components

import android.content.ContentResolver
import android.graphics.Bitmap
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
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Items whose thumbnail the system couldn't make, by the same key: not asked again until the file changes. */
    private val broken = HashSet<String>()

    /** Four thumbnails read at a time (LOGIKA §3). */
    private val reading = Dispatchers.IO.limitedParallelism(4)

    // 768 for the two-column step (cells of ~540 px on a phone; 512 would be stretched)
    private val STEPS = intArrayOf(128, 256, 384, 512, 768)

    /** The size actually read for a cell of [px] pixels. */
    fun sizeFor(px: Int): Int = STEPS.firstOrNull { it >= px } ?: STEPS.last()

    private fun key(item: MediaItem, px: Int) = "${item.id}.${item.generation}@${sizeFor(px)}"

    /** What is known without reading: the thumbnail in memory, [Thumb.Broken], or null. */
    fun peek(item: MediaItem, px: Int): Thumb? {
        val key = key(item, px)
        cache.get(key)?.let { return Thumb.Image(it.asImageBitmap()) }
        return if (synchronized(broken) { key in broken }) Thumb.Broken else null
    }

    /** The largest thumbnail of the item already in memory, at any size (the pinch's layer reads nothing). */
    fun peekAny(item: MediaItem): Thumb? {
        for (size in STEPS.reversed()) (peek(item, size) as? Thumb.Image)?.let { return it }
        return null
    }

    suspend fun load(resolver: ContentResolver, item: MediaItem, px: Int): Thumb {
        peek(item, px)?.let { return it }
        val key = key(item, px)
        val size = sizeFor(px)
        return withContext(reading) {
            // A tile scrolled away cancels its reading, also inside the system's call
            val signal = CancellationSignal()
            val handle = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
            try {
                val bitmap = resolver.loadThumbnail(item.uri(), Size(size, size), signal)
                cache.put(key, bitmap)
                Thumb.Image(bitmap.asImageBitmap())
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
    val thumb by produceState(Thumbnails.peek(item, px) ?: Thumbnails.peekAny(item), item.id, item.generation, Thumbnails.sizeFor(px)) {
        if (Thumbnails.peek(item, px) == null) value = Thumbnails.load(resolver, item, px)
    }
    when (val current = thumb) {
        is Thumb.Image -> Image(current.bitmap, contentDescription = null, contentScale = contentScale, modifier = modifier)
        Thumb.Broken -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_broken_image), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        null -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer))
    }
}
