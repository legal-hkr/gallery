package sh.hnet.gallery.data

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.gallery.model.MediaItem

/** How the phone brings HDR video down to SDR: the viewer's SDR toggle (D11) and a trimmed HDR video saved in SDR (D35). */
object ToneMapping {
    // Whether a decoder tone-maps a kind of video, by its type and profile: asked once per process
    private val toneMapping = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether the phone's decoder can turn this HDR video into SDR itself (owner's decision D11): a
     * test decoder set up with the request, without a surface, says so in its input format – the
     * requested transfer when it does, 0 when it doesn't.
     */
    suspend fun byDecoder(resolver: ContentResolver, item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val extractor = MediaExtractor()
            try {
                resolver.openFileDescriptor(item.uri(), "r")?.use { extractor.setDataSource(it.fileDescriptor) } ?: return@withContext false
                val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: return@withContext false
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext false
                val key = mime + "/" + (if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1)
                toneMapping[key]?.let { return@withContext it }
                val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) ?: return@withContext false.also { toneMapping[key] = false }
                val codec = MediaCodec.createByCodecName(name)
                val supported = try {
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    codec.configure(format, null, null, 0)
                    codec.inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, 0) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                } finally {
                    codec.release()
                }
                toneMapping[key] = supported
                supported
            } finally {
                extractor.release()
            }
        } catch (_: Exception) {
            false
        }
    }
}
