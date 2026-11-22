package sh.hnet.gallery.data

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.hnet.gallery.model.FileNames
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Trim
import sh.hnet.gallery.model.editedName

/**
 * A video's trim saved (SPEC §15.7, LOGIKA §20.7) through Media3's Transformer: the kept part only, cut at the exact
 * frames, with only the pieces at the cuts re-encoded where the file and the phone allow it – the middle copied as it
 * is (D34) – and the whole re-encoded where they don't. HDR stays HDR where the phone has an HDR encoder; where it
 * hasn't, after a question, it is saved in SDR (D35). Sound is copied. The date and the place go over from the file's
 * own metadata (the in-app MP4 muxer writes what the extractor read; the place only from the original, [located]). The
 * result is always MP4.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class VideoTrimmer(private val context: Context) {
    private val resolver = context.contentResolver

    /**
     * What a save needs to know before it starts.
     *
     * @param hdr The video is HDR (HLG, PQ)
     * @param hdrSavable The phone has an encoder that keeps it HDR
     * @param sdrMode How the phone brings it down to SDR (Transformer's HDR mode: OpenGL where the GPU samples YUV –
     *   `GL_EXT_YUV_target`, which the emulator lacks (owner's test of S9b) –, else the decoder's own tone mapping);
     *   null when it can do neither, and so can't save this video at all
     */
    data class Facts(val hdr: Boolean, val hdrSavable: Boolean, val sdrMode: Int? = null)

    /**
     * Only an MP4 is written back in its own type – by its MIME type and its name: a QuickTime file (.mov) offered
     * Replace on the owner's emulator (S9b), so the type the media gave it isn't trusted alone.
     */
    fun canReplace(item: MediaItem): Boolean =
        item.mime == MimeTypes.VIDEO_MP4 && FileNames.split(item.name).second.lowercase() in setOf(".mp4", ".m4v")

    /**
     * The facts from the video track's format as Media3 read it – the editor's player, the same extractors Transformer
     * uses (owner's test of S9b: the platform's MediaExtractor didn't see HLG tagged in an H.264 stream, so no question).
     */
    suspend fun facts(item: MediaItem, format: Format): Facts = withContext(Dispatchers.IO) {
        val color = format.colorInfo
        val transfer = color?.colorTransfer
        val hdr = transfer == C.COLOR_TRANSFER_HLG || transfer == C.COLOR_TRANSFER_ST2084 || format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        if (!hdr) return@withContext Facts(hdr = false, hdrSavable = true)
        val wanted = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(if (transfer == C.COLOR_TRANSFER_ST2084) C.COLOR_TRANSFER_ST2084 else C.COLOR_TRANSFER_HLG)
            .setColorRange(color?.colorRange?.takeIf { it != Format.NO_VALUE } ?: C.COLOR_RANGE_LIMITED)
            .build()
        // The video's own type, or HEVC – what Transformer falls back to
        val savable = try {
            listOfNotNull(format.sampleMimeType, MimeTypes.VIDEO_H265).distinct().any { EncoderUtil.getSupportedEncodersForHdrEditing(it, wanted).isNotEmpty() }
        } catch (_: Exception) {
            false
        }
        val sdrMode = when {
            savable -> null
            runCatching { GlUtil.isYuvTargetExtensionSupported() }.getOrDefault(false) -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            ToneMapping.byDecoder(resolver, item) -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
            else -> null
        }
        Facts(hdr = true, hdrSavable = savable, sdrMode = sdrMode)
    }

    /**
     * A copy beside the original: the name + [suffix] (+ a number when taken), `.mp4`. The new item's id, or null.
     *
     * @param sdrMode Saved in SDR this way ([Facts.sdrMode]: an HDR video on a phone without an HDR encoder, after the question – D35); null keeps it as it is
     * @param onProgress Done so far, 0–1, or null while it can't be told
     */
    suspend fun saveCopy(item: MediaItem, trim: Trim, sdrMode: Int?, suffix: String, taken: Collection<String>, located: Boolean, onProgress: (Float?) -> Unit): Long? {
        val file = export(item, trim, sdrMode, located, onProgress)
        // Once made, the copy is written whole: a cancel now would leave it saved but reported as not (review of S9b)
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                val (base, _) = FileNames.split(item.name)
                val name = editedName("$base.mp4", suffix, taken)
                resolver.insertBeside(item, file, name, MimeTypes.VIDEO_MP4, MediaStore.Video.Media::getContentUri, Environment.DIRECTORY_MOVIES)
            } finally {
                file.delete()
            }
        }
    }

    /** The original replaced, after the system's consent the caller asked for: the result made whole first, then written over it. */
    suspend fun replace(item: MediaItem, trim: Trim, sdrMode: Int?, located: Boolean, onProgress: (Float?) -> Unit) {
        val file = export(item, trim, sdrMode, located, onProgress)
        // Written over whole: a cancel during it would leave the original replaced but reported as untouched
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                resolver.writeOver(item, file)
            } finally {
                file.delete()
            }
        }
    }

    /** The kept part into a file in the app's cache. Cancelling the coroutine stops the export and the file goes. */
    private suspend fun export(item: MediaItem, trim: Trim, sdrMode: Int?, located: Boolean, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.Main) {
        val folder = File(context.cacheDir, "edit").apply { mkdirs() }
        val file = File(folder, "trim-${System.nanoTime()}.mp4")
        val clipping = PlayerItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trim.startMs)
            .setEndPositionMs(if (trim.endMs >= trim.durationMs) C.TIME_END_OF_SOURCE else trim.endMs)
            .build()
        val source = PlayerItem.Builder().setUri(if (located) item.originalUri() else item.uri()).setClippingConfiguration(clipping).build()
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(EditedMediaItem.Builder(source).build())))
            .setHdrMode(sdrMode ?: Composition.HDR_MODE_KEEP_HDR)
            .build()
        try {
            coroutineScope {
                var done = false
                val transformer = Transformer.Builder(context)
                    // Only the pieces at the cuts re-encoded where it can be done (D34); Transformer falls back to the whole
                    .experimentalSetTrimOptimizationEnabled(sdrMode == null)
                    .build()
                // Progress while it runs: unknown at first, and while the cut pieces are being found
                val progress = launch {
                    val holder = ProgressHolder()
                    while (isActive && !done) {
                        onProgress(if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null)
                        delay(250)
                    }
                }
                try {
                    suspendCancellableCoroutine { continuation ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        })
                        continuation.invokeOnCancellation { transformer.cancel() }
                        transformer.start(composition, file.absolutePath)
                    }
                } finally {
                    done = true
                    progress.cancel()
                }
            }
            onProgress(1f)
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }
}
