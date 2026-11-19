package sh.hnet.gallery.ui.viewer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.Box
import sh.hnet.gallery.model.Dim
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Orientation
import sh.hnet.gallery.model.Tile

/** What a page shows of a photo beyond its thumbnail (LOGIKA §13.2, level 2). */
sealed interface Picture {
    /**
     * The whole photo decoded for the screen, upright, with its gain map when it has one.
     *
     * @param full The displayed photo's own size in pixels (upright)
     */
    class Still(val bitmap: Bitmap, val full: IntSize) : Picture {
        val gainmap: Boolean get() = bitmap.hasGainmap()

        /** The same bitmap for drawing (the page and the flight share it). */
        val image: ImageBitmap by lazy { bitmap.asImageBitmap() }

        /** Image pixels per bitmap pixel: how much the screen decode is sampled down. */
        val sampled: Float get() = if (bitmap.width > 0) full.width.toFloat() / bitmap.width else 1f
    }

    /** An animated GIF or WebP, looping (SPEC §6.1). */
    class Animated(val drawable: AnimatedImageDrawable, val full: IntSize) : Picture

    /** Can't be decoded (SPEC §6.9). */
    data object Broken : Picture
}

object PhotoDecoder {
    /** Types that may be animated: decoded as a drawable, which says whether it is. */
    private val MAYBE_ANIMATED = setOf("image/gif", "image/webp")

    /**
     * The photo at about [longSide] pixels on its longer side (never above its own size), upright,
     * with the gain map; or the animation; or [Picture.Broken].
     */
    suspend fun screen(resolver: ContentResolver, item: MediaItem, longSide: Int): Picture = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(resolver, item.uri())
            var full = IntSize.Zero
            val header = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                full = IntSize(info.size.width, info.size.height)
                // Exactly the screen's long side (never more than the photo's own): a power-of-two sampling left a 50 MP
                // photo at twice the pixels the screen shows (owner's test of S3 on the Pixel: frames dropped while swiping)
                val longer = maxOf(info.size.width, info.size.height)
                if (longer > longSide) {
                    val scale = longSide.toFloat() / longer
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
                // The default allocator gives a hardware bitmap, uploaded to the GPU here on the decoding thread, not by the render thread
                // while the pager moves; nothing reads its pixels (the tiles come from the file, the flight from the thumbnail)
            }
            if (item.mime in MAYBE_ANIMATED) {
                val drawable = ImageDecoder.decodeDrawable(source, header)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    return@withContext Picture.Animated(drawable, full)
                }
            }
            val bitmap = ImageDecoder.decodeBitmap(source, header)
            // The header's size is the stored one; the decoder turns the photo upright, so its own size follows the bitmap's shape
            val upright = if ((bitmap.width > bitmap.height) == (full.width > full.height)) full else IntSize(full.height, full.width)
            Picture.Still(bitmap, upright)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Picture.Broken
        }
    }
}

/**
 * Sharp tiles of the zoomed photo (LOGIKA §13.2, level 3): a region decoder over the stored file,
 * mapped through the EXIF orientation, which the region decoder doesn't apply. Null when the
 * format has no region decoding (DNG, some AVIF): the screen decode is then all there is.
 */
class TileSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val decoder: BitmapRegionDecoder,
    val orientation: Orientation,
) : Closeable {
    private val stored = Dim(decoder.width.toFloat(), decoder.height.toFloat())

    /** The displayed photo's size (upright). */
    val displayed: IntSize get() = orientation.displayed(stored).let { IntSize(it.width.roundToInt(), it.height.roundToInt()) }

    /** One tile, upright, about [Tile.region]'s size divided by its sampling; null when it can't be decoded. */
    fun decode(tile: Tile): Bitmap? = synchronized(this) {
        if (decoder.isRecycled) return null
        val source = orientation.toSource(tile.region, stored)
        val rect = Rect(source.left.roundToInt(), source.top.roundToInt(), source.right.roundToInt(), source.bottom.roundToInt())
        if (rect.isEmpty) return null
        val options = BitmapFactory.Options().apply { inSampleSize = tile.sample }
        val bitmap = try {
            decoder.decodeRegion(rect, options)
        } catch (_: Exception) {
            null
        } ?: return null
        if (orientation == Orientation.NORMAL) return bitmap
        val matrix = Matrix().apply {
            setRotate(orientation.degrees.toFloat())
            if (orientation.mirrored) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    override fun close() = synchronized(this) {
        decoder.recycle()
        descriptor.close()
    }

    companion object {
        suspend fun open(resolver: ContentResolver, item: MediaItem): TileSource? = withContext(Dispatchers.IO) {
            if (item.video) return@withContext null
            try {
                // The orientation from its own descriptor: the region decoder reads from the start of the other one
                val exif = resolver.openFileDescriptor(item.uri(), "r")?.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    ?: ExifInterface.ORIENTATION_NORMAL
                val descriptor = resolver.openFileDescriptor(item.uri(), "r") ?: return@withContext null
                try {
                    TileSource(descriptor, BitmapRegionDecoder.newInstance(descriptor), Orientation.of(exif))
                } catch (_: Exception) {
                    descriptor.close()
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Decoded tiles in memory only (SPEC §22.8), an eighth of the app's memory, by item, version, sampling and place. */
object TileCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun key(item: MediaItem, tile: Tile) = "${item.id}.${item.generation}.${tile.sample}.${tile.col}.${tile.row}"

    operator fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/** Where a tile of the image lands in the fitted image drawn in a container: image pixels times [fit], from [left], [top]. */
internal fun Box.inFitted(left: Float, top: Float, fit: Float) = Box(left + this.left * fit, top + this.top * fit, left + this.right * fit, top + this.bottom * fit)
