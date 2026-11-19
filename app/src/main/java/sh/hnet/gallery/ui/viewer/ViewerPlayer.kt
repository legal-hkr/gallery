package sh.hnet.gallery.ui.viewer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Playback

/**
 * The viewer's video player (SPEC §8, LOGIKA §14): one ExoPlayer for one opening of the viewer,
 * handed to whichever video page settles. Muted by default; with sound it takes the audio focus
 * (and pauses when another app takes it), muted it takes none, so other apps' music goes on.
 */
@OptIn(UnstableApi::class)
class ViewerPlayer(context: Context) {
    /**
     * What the controls show for the video loaded. The position isn't here: the card reads it while shown.
     *
     * @param hdr The video's HDR kind ("HLG", "HDR10", "Dolby Vision"), null for SDR
     * @param ended Stopped on its last frame (videos over 10 s)
     */
    data class State(
        val item: Long? = null,
        val playing: Boolean = false,
        val durationMs: Long? = null,
        val frameRate: Float? = null,
        val hasAudio: Boolean? = null,
        val hdr: String? = null,
        val ended: Boolean = false,
        val speed: Float = 1f,
        val muted: Boolean = true,
        val sdr: Boolean = false,
        /** The video can't be played (no decoder for it, a damaged file): the page shows the problem card. */
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Asked by the renderer when a decoder is set up: the user switched the HDR video to SDR. */
    @Volatile private var sdrRequested = false

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext, SdrRenderersFactory(context.applicationContext) { sdrRequested })
        .build()
        .apply {
            volume = 0f
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(playing = isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val length = duration.takeIf { it != C.TIME_UNSET && it > 0 }
                        // Short videos loop (SPEC §8.2); longer ones stop on their last frame
                        repeatMode = if (length != null && Playback.loops(length)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        _state.value = _state.value.copy(durationMs = length, ended = false)
                    }
                    if (playbackState == Player.STATE_ENDED) _state.value = _state.value.copy(ended = true, playing = false)
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = _state.value.copy(error = true, playing = false)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)
                    val audio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                    _state.value = _state.value.copy(
                        frameRate = video?.frameRate?.takeIf { it > 0 },
                        hasAudio = audio,
                        hdr = video?.let(::hdrKind),
                    )
                }
            })
        }

    // Where each video was left in this opening, to go on from there (owner's decision D10)
    private val positions = HashMap<Long, Long>()

    /** The video now on screen: loaded and played from where it was left in this opening (from the start the first time). */
    fun show(item: MediaItem) {
        val current = _state.value.item
        if (current == item.id) {
            player.play()
            return
        }
        if (current != null) positions[current] = player.currentPosition
        sdrRequested = false
        player.setPlaybackParameters(PlaybackParameters(1f))
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.setMediaItem(PlayerItem.fromUri(item.uri()), positions[item.id] ?: 0L)
        player.prepare()
        player.play()
        _state.value = State(item = item.id, muted = _state.value.muted)
    }

    /** The page was swiped away (SPEC §8.2): the video pauses where it is. */
    fun pauseFor(id: Long) {
        if (_state.value.item == id) player.pause()
    }

    fun positionMs(): Long = player.currentPosition

    fun togglePlay() {
        if (_state.value.ended) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying) player.pause() else player.play()
    }

    /** One frame back or forward, paused only (SPEC §8.2). */
    fun step(forward: Boolean) {
        if (player.isPlaying) return
        player.setSeekParameters(SeekParameters.EXACT)
        val frame = Playback.frameMs(_state.value.frameRate)
        val target = (player.currentPosition + if (forward) frame else -frame).coerceIn(0L, _state.value.durationMs ?: Long.MAX_VALUE)
        player.seekTo(target)
        _state.value = _state.value.copy(ended = false)
    }

    /** Scrubbing (SPEC §8.4): the nearest key frame while the finger moves, the exact frame when it lifts. */
    fun scrubTo(ms: Long) {
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.seekTo(ms)
    }

    fun endScrub(ms: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(ms)
        _state.value = _state.value.copy(ended = false)
    }

    /** For this video only (SPEC §8.2); the pitch stays. */
    fun setSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed))
        _state.value = _state.value.copy(speed = speed)
    }

    /**
     * Sound for this opening of the viewer (SPEC §8.3). With sound the player handles the audio focus,
     * pausing when another app takes it; muted it takes none.
     */
    fun setMuted(muted: Boolean) {
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        player.setAudioAttributes(attributes, !muted)
        player.volume = if (muted) 0f else 1f
        _state.value = _state.value.copy(muted = muted)
    }

    /** The HDR video shown in SDR, or back (D11): the decoder is set up again where the video is. */
    fun setSdr(sdr: Boolean) {
        if (sdrRequested == sdr) return
        sdrRequested = sdr
        val position = player.currentPosition
        val playing = player.isPlaying
        player.currentMediaItem?.let { player.setMediaItem(it, position) }
        player.prepare()
        if (playing) player.play()
        _state.value = _state.value.copy(sdr = sdr)
    }

    fun release() = player.release()

    private fun hdrKind(format: Format): String? = when {
        format.sampleMimeType == "video/dolby-vision" -> "Dolby Vision"
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        else -> null
    }

    companion object {
        // Whether a decoder tone-maps a kind of video, by its type and profile: asked once per process
        private val toneMapping = ConcurrentHashMap<String, Boolean>()

        /**
         * Whether the phone's decoder can turn this HDR video into SDR itself (owner's decision D11): a
         * test decoder set up with the request, without a surface, says so in its input format – the
         * requested transfer when it does, 0 when it doesn't.
         */
        suspend fun decoderToneMaps(resolver: ContentResolver, item: MediaItem): Boolean = withContext(Dispatchers.IO) {
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
}

/** The renderers, with the video one asking its decoder for SDR when the user switched an HDR video to SDR (D11). */
@OptIn(UnstableApi::class)
private class SdrRenderersFactory(context: Context, private val sdr: () -> Boolean) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out.add(SdrVideoRenderer(builder, sdr))
    }
}

@OptIn(UnstableApi::class)
private class SdrVideoRenderer(builder: Builder, private val sdr: () -> Boolean) : MediaCodecVideoRenderer(builder) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId).apply {
        if (sdr()) setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    }
}
