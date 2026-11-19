package sh.hnet.gallery.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.Bytes
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Mp4Location
import sh.hnet.gallery.model.SharePlan
import sh.hnet.gallery.model.ShareWay
import sh.hnet.gallery.model.XmpGps

/**
 * Prepares a share (LOGIKA §16.1, §16.9): items without a location go as their MediaStore address; the others as
 * copies in `cache/share/<session>/` under their own names, handed out by the app's `FileProvider` – without the
 * location, or the original with it (MediaStore would cut it for the receiving app). Nothing about the files is logged.
 */
class ShareMaker(private val context: Context) {
    private val resolver = context.contentResolver
    private val root = File(context.cacheDir, "share")

    /** Copies made so far of the share being prepared: done and all; null when none is under way. */
    data class Progress(val done: Int, val total: Int, val startedAt: Long)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** What a share is: its addresses and the plan behind the sheet's line, or what went wrong. */
    sealed interface Prepared {
        data class Ready(val uris: List<Uri>, val mimes: List<String>, val plan: SharePlan) : Prepared

        data class Failed(val count: Int, val details: String) : Prepared
    }

    /** The copies of earlier starts go (SPEC §14.1: the cache is cleared on the next start). */
    fun clearOld() {
        root.deleteRecursively()
    }

    suspend fun prepare(items: List<MediaItem>, facts: Map<Long, Fact>, removeLocation: Boolean, canLocate: Boolean): Prepared = withContext(Dispatchers.IO) {
        // Without access to the location the system cuts it from every read, the receiving app's too: nothing to take out
        val located = if (canLocate) items.filter { located(it, facts[it.id]) }.mapTo(HashSet()) { it.id } else emptySet()
        val plan = SharePlan.of(items, located, removeLocation)
        val copies = plan.ways.count { it.value != ShareWay.ORIGINAL }
        val session = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        val uris = ArrayList<Uri>(items.size)
        val failures = ArrayList<String>()
        var done = 0
        if (copies > 0) _progress.value = Progress(0, copies, System.currentTimeMillis())
        try {
            for (item in items) {
                ensureActive()
                when (plan.ways.getValue(item.id)) {
                    // A file from outside goes as a copy too: its grant was the viewer's, not one to pass on
                    ShareWay.ORIGINAL -> uris += if (item.outside) FileProvider.getUriForFile(context, "${context.packageName}.files", copy(item, session)) else item.uri()
                    ShareWay.STRIPPED, ShareWay.WITH_LOCATION -> {
                        val strip = plan.ways.getValue(item.id) == ShareWay.STRIPPED
                        try {
                            val file = copy(item, session)
                            if (strip) strip(item, file)
                            uris += FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        } catch (e: Exception) {
                            failures += e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                        }
                        done++
                        _progress.value = Progress(done, copies, _progress.value?.startedAt ?: System.currentTimeMillis())
                    }
                }
            }
        } finally {
            _progress.value = null
        }
        if (failures.isNotEmpty()) Prepared.Failed(failures.size, failures.distinct().joinToString("\n")) else Prepared.Ready(uris, items.map { it.mime }, plan)
    }

    /** A photo's location from its facts (read at the first start, LOGIKA §2.3), a video's from the file. */
    private fun located(item: MediaItem, fact: Fact?): Boolean {
        if (!item.video) return fact?.latitude != null && fact.longitude != null || fact == null && photoLocated(item)
        return try {
            MediaMetadataRetriever().use { retriever ->
                resolver.openFileDescriptor(item.originalUri(), "r")?.use { retriever.setDataSource(it.fileDescriptor) }
                !retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).isNullOrBlank()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** A photo whose facts weren't read yet: asked of the file itself. */
    private fun photoLocated(item: MediaItem): Boolean = try {
        resolver.openFileDescriptor(item.originalUri(), "r")?.use { ExifInterface(it.fileDescriptor).latLong != null } == true
    } catch (_: Exception) {
        false
    }

    /** The original, location included, into this share's folder under its own name (a second of the same name numbered). */
    private fun copy(item: MediaItem, session: File): File {
        var file = File(session, item.name.ifBlank { "item-${item.id}" })
        var n = 2
        while (file.exists()) {
            val dot = item.name.lastIndexOf('.')
            file = File(session, if (dot > 0) item.name.substring(0, dot) + " ($n)" + item.name.substring(dot) else item.name + " ($n)")
            n++
        }
        val input = resolver.openInputStream(item.originalUri()) ?: error("No stream")
        input.use { from -> file.outputStream().use { to -> from.copyTo(to, 256 * 1024) } }
        return file
    }

    /** The copy's location out: EXIF's GPS tags and XMP's GPS properties for photos, the location boxes for videos. */
    private fun strip(item: MediaItem, file: File) {
        if (item.video) {
            RandomAccessFile(file, "rw").use { Mp4Location.strip(RandomBytes(it)) }
            return
        }
        val exif = ExifInterface(file)
        for (tag in GPS_TAGS) exif.setAttribute(tag, null)
        exif.getAttribute(ExifInterface.TAG_XMP)?.let { xmp -> if (XmpGps.has(xmp)) exif.setAttribute(ExifInterface.TAG_XMP, XmpGps.strip(xmp)) }
        exif.saveAttributes()
    }

    /** The share's intent for the system's sheet, with the read grant and the previews' clip. */
    fun sendIntent(prepared: Prepared.Ready): Intent {
        val uris = prepared.uris
        val type = commonType(prepared.mimes)
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = type
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return send
    }

    private class RandomBytes(private val file: RandomAccessFile) : Bytes {
        override val size: Long get() = file.length()

        override fun read(at: Long, count: Int): ByteArray {
            file.seek(at)
            val bytes = ByteArray(count)
            file.readFully(bytes)
            return bytes
        }

        override fun write(at: Long, bytes: ByteArray) {
            file.seek(at)
            file.write(bytes)
        }
    }

    companion object {
        private val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )

        /** One type for the sheet: the items’ own when they share it, else their kind with any subtype, else anything. */
        fun commonType(mimes: List<String>): String {
            val distinct = mimes.distinct()
            if (distinct.size == 1) return distinct.first()
            val kinds = distinct.map { it.substringBefore('/') }.distinct()
            return if (kinds.size == 1) kinds.first() + "/*" else "*/*"
        }
    }
}
