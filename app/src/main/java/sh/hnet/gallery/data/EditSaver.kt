package sh.hnet.gallery.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.gallery.model.Affine
import sh.hnet.gallery.model.EditGeometry
import sh.hnet.gallery.model.EditState
import sh.hnet.gallery.model.FileNames
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Orientation
import sh.hnet.gallery.model.Pt
import sh.hnet.gallery.model.PxSize
import sh.hnet.gallery.model.editedName

/**
 * Saves an edit at the photo's full resolution (SPEC §15.6, §15.8; LOGIKA §18.4–§18.5): the result made of strips of
 * the original (never the original and the result whole at once), an Ultra HDR photo's gain map given the same
 * geometry, the EXIF carried over; then a copy beside the original, or the original replaced.
 */
class EditSaver(private val context: Context) {
    private val resolver = context.contentResolver

    /** What a save makes of a type: its MIME type and how it's encoded; HEIC, HEIF and AVIF become JPEG (D26). */
    class Output(val mime: String, val format: Bitmap.CompressFormat, val quality: Int, val extension: String)

    /** Whether "Replace original" can keep the type: the platform encodes JPEG, PNG and WebP only (D26). */
    fun canReplace(item: MediaItem): Boolean = item.mime in KEPT_TYPES

    fun outputOf(item: MediaItem): Output = when (item.mime) {
        "image/png" -> Output("image/png", Bitmap.CompressFormat.PNG, 100, ".png")
        "image/webp" -> Output("image/webp", Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, ".webp")
        else -> Output("image/jpeg", Bitmap.CompressFormat.JPEG, QUALITY, ".jpg")
    }

    /**
     * A copy beside the original (SPEC §15.6): the name + [suffix] (+ a number when taken), written while pending and
     * shown once whole; its date and place from the EXIF carried over. The new item's id, or null.
     */
    suspend fun saveCopy(item: MediaItem, state: EditState, suffix: String, taken: Collection<String>, located: Boolean): Long? = withContext(Dispatchers.IO) {
        val output = outputOf(item)
        val file = render(item, state, output, located)
        try {
            val (base, _) = FileNames.split(item.name)
            val name = editedName(base + output.extension, suffix, taken)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, output.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, item.path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_TAKEN, item.taken)
            }
            val target = resolver.insert(MediaStore.Images.Media.getContentUri(item.volume), values) ?: return@withContext null
            try {
                resolver.openOutputStream(target)?.use { to -> file.inputStream().use { it.copyTo(to, 256 * 1024) } } ?: error("No stream")
                resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null)
                ContentUris.parseId(target)
            } catch (e: Exception) {
                // Half a copy is no file: it goes
                try { resolver.delete(target, null) } catch (_: Exception) { }
                throw e
            }
        } finally {
            file.delete()
        }
    }

    /**
     * The original replaced (SPEC §15.6), after the system's consent the caller asked for: the result made whole
     * first, so a failure to make it leaves the original as it was; then written over it. Its name stays.
     */
    suspend fun replace(item: MediaItem, state: EditState, located: Boolean) = withContext(Dispatchers.IO) {
        val file = render(item, state, outputOf(item), located)
        try {
            resolver.openOutputStream(item.uri(), "wt")?.use { to -> file.inputStream().use { it.copyTo(to, 256 * 1024) } } ?: error("No stream")
        } finally {
            file.delete()
        }
    }

    /** The edited photo at full resolution, encoded, with its EXIF, in the app's cache. */
    private fun render(item: MediaItem, state: EditState, output: Output, located: Boolean): File {
        val source = if (located) item.originalUri() else item.uri()
        val exif = resolver.openFileDescriptor(source, "r")?.use { ExifInterface(it.fileDescriptor) } ?: error("No file")
        val orientation = Orientation.of(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        val result = resolver.openFileDescriptor(source, "r")?.use { descriptor ->
            val decoder = BitmapRegionDecoder.newInstance(descriptor)
            try {
                draw(decoder, item, orientation, state, source)
            } finally {
                decoder.recycle()
            }
        } ?: error("No file")
        val folder = File(context.cacheDir, "edit").apply { mkdirs() }
        val file = File(folder, "edit-${System.nanoTime()}${output.extension}")
        try {
            file.outputStream().use { if (!result.compress(output.format, output.quality, it)) error("Not encoded") }
        } finally {
            result.recycle()
        }
        carryExif(exif, file, result.width, result.height)
        return file
    }

    /** The result drawn strip by strip from the original's regions, its gain map beside it. */
    private fun draw(decoder: BitmapRegionDecoder, item: MediaItem, orientation: Orientation, state: EditState, source: Uri): Bitmap {
        val stored = PxSize(decoder.width, decoder.height)
        val upright = EditGeometry.upright(stored, orientation)
        val size = EditGeometry.output(upright, state)
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        val map = EditGeometry.matrix(stored, orientation, state)
        val back = map.inverse()
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var gain: Bitmap? = null
        var gainCanvas: Canvas? = null
        var gainScale = 0.0
        var gainTemplate: Gainmap? = null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var y = 0
        while (y < height) {
            val y1 = min(height, y + STRIP)
            // The original's part this strip comes from: the strip's corners mapped back, a pixel more around
            val corners = listOf(Pt(0.0, y.toDouble()), Pt(width.toDouble(), y.toDouble()), Pt(0.0, y1.toDouble()), Pt(width.toDouble(), y1.toDouble())).map(back::map)
            val region = Rect(
                max(0, floor(corners.minOf { it.x }).toInt() - 2),
                max(0, floor(corners.minOf { it.y }).toInt() - 2),
                min(decoder.width, ceil(corners.maxOf { it.x }).toInt() + 2),
                min(decoder.height, ceil(corners.maxOf { it.y }).toInt() + 2),
            )
            if (region.width() > 0 && region.height() > 0) {
                val part = decoder.decodeRegion(region, options) ?: error("Not decoded")
                canvas.withClip(0, y, width, y1) {
                    concat(map.toMatrix())
                    translate(region.left.toFloat(), region.top.toFloat())
                    drawBitmap(part, 0f, 0f, paint)
                }
                // The gain map's part, by the same map at its own scale (LOGIKA §18.4)
                part.gainmap?.let { partGain ->
                    val contents = partGain.gainmapContents
                    if (gain == null) {
                        gainScale = contents.width.toDouble() / part.width
                        gain = createBitmap(max(1, (width * gainScale).roundToInt()), max(1, (height * gainScale).roundToInt()), contents.config ?: Bitmap.Config.ARGB_8888)
                        gainCanvas = Canvas(gain)
                        gainTemplate = partGain
                    }
                    gainCanvas!!.withClip(0f, (y * gainScale).toFloat(), (width * gainScale).toFloat(), (y1 * gainScale).toFloat()) {
                        scale(gainScale.toFloat(), gainScale.toFloat())
                        concat(map.toMatrix())
                        translate(region.left.toFloat(), region.top.toFloat())
                        scale((part.width.toDouble() / contents.width).toFloat(), (part.height.toDouble() / contents.height).toFloat())
                        drawBitmap(contents, 0f, 0f, paint)
                    }
                }
                part.recycle()
            }
            y = y1
        }
        // The region decoder gave no gain map though the photo has one: the whole photo's, decoded small (it is small)
        if (gain == null) {
            wholeGainmap(source, upright)?.let { (template, contents) ->
                val uprightMap = EditGeometry.matrix(upright, Orientation.NORMAL, state)
                gainScale = contents.width.toDouble() / upright.width
                gain = createBitmap(max(1, (width * gainScale).roundToInt()), max(1, (height * gainScale).roundToInt()), contents.config ?: Bitmap.Config.ARGB_8888)
                Canvas(gain).apply {
                    scale(gainScale.toFloat(), gainScale.toFloat())
                    concat(uprightMap.toMatrix())
                    scale((upright.width / contents.width).toFloat(), (upright.height / contents.height).toFloat())
                    drawBitmap(contents, 0f, 0f, paint)
                }
                gainTemplate = template
            }
        }
        val finished = gain
        val template = gainTemplate
        if (finished != null && template != null) out.gainmap = copyOf(template, finished)
        return out
    }

    /** The whole photo's gain map, its base decoded at a quarter so it costs little; null when it has none. */
    private fun wholeGainmap(source: Uri, upright: PxSize): Pair<Gainmap, Bitmap>? = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(max(1, (upright.width / 4).roundToInt()), max(1, (upright.height / 4).roundToInt()))
        }
        val gainmap = bitmap.gainmap
        val contents = gainmap?.gainmapContents?.copy(gainmap.gainmapContents.config ?: Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        if (gainmap != null && contents != null) gainmap to contents else null
    } catch (_: Exception) {
        null
    }

    /** A gain map of [contents] with [template]'s parameters (its constructor taking both is Android 15's). */
    private fun copyOf(template: Gainmap, contents: Bitmap): Gainmap = Gainmap(contents).apply {
        val min = template.ratioMin
        val maxRatio = template.ratioMax
        val gamma = template.gamma
        val sdr = template.epsilonSdr
        val hdr = template.epsilonHdr
        setRatioMin(min[0], min[1], min[2])
        setRatioMax(maxRatio[0], maxRatio[1], maxRatio[2])
        setGamma(gamma[0], gamma[1], gamma[2])
        setEpsilonSdr(sdr[0], sdr[1], sdr[2])
        setEpsilonHdr(hdr[0], hdr[1], hdr[2])
        displayRatioForFullHdr = template.displayRatioForFullHdr
        minDisplayRatioForHdrTransition = template.minDisplayRatioForHdrTransition
    }

    /** The original's EXIF on the result: dates, place, camera, exposure; upright now, its new size. */
    private fun carryExif(from: ExifInterface, file: File, width: Int, height: Int) {
        try {
            val to = ExifInterface(file)
            for (tag in CARRIED) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            to.saveAttributes()
        } catch (_: Exception) {
            // A format ExifInterface doesn't write: the photo stays without them
        }
    }

    private fun Affine.toMatrix(): Matrix = Matrix().apply { setValues(values()) }

    companion object {
        private const val QUALITY = 95
        private const val STRIP = 256
        private val KEPT_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        private val CARRIED = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        )
    }
}
