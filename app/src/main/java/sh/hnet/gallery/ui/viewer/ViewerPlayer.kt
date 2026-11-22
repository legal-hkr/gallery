package sh.hnet.gallery.ui.viewer

import android.content.ContentResolver
import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.gallery.data.ToneMapping
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
                // Playing as wanted, not as running: a video buffering for a moment isn't "paused" – Pause read it so and
                // played it again, so pausing took several taps (owner's test of S9b, on the emulator's software decoder)
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    _state.value = _state.value.copy(playing = wanted())
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) _state.value = _state.value.copy(playing = wanted())
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

    // Not after an error: the player goes idle still wanting to play, and the viewer hid its chrome over the problem card
    // as if the video played (owner's test of S9b)
    private fun wanted(): Boolean = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null

    // Where each video was left in this opening, to go on from there (owner's decision D10)
    private val positions = HashMap<Long, Long>()

    // The loaded video's file, by its size: written over (the editor's Replace), it is loaded again. Not by the row's
    // generation – a favorite or a new name raise it too, and the video started over (review of S9b)
    private var loadedSize = 0L

    /**
     * The video now on screen: loaded and played from where it was left in this opening (from the start the first
     * time). A video changed since it was loaded – trimmed over its original – is loaded again from the start (owner's
     * test of S9b: the old one's player failed on the new file and the page showed the problem card).
     */
    fun show(item: MediaItem) {
        val current = _state.value.item
        if (current == item.id && loadedSize != item.size) {
            positions.remove(item.id)
            _state.value = _state.value.copy(item = null)
            return show(item)
        }
        if (current == item.id) {
            // Back on the same video: the page's toggle starts at HDR again, so the decoder does too (review before v0.1.0)
            if (sdrRequested) setSdr(false)
            player.play()
            return
        }
        if (current != null) positions[current] = player.currentPosition
        sdrRequested = false
        player.setPlaybackParameters(PlaybackParameters(1f))
        player.setSeekParameters(SeekParameters.DEFAULT)
        loadedSize = item.size
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
        } else if (player.playWhenReady) player.pause() else player.play()
    }

    /** One frame back or forward, paused only (SPEC §8.2). */
    fun step(forward: Boolean) {
        if (wanted()) return
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
        val playing = wanted()
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
        /** Whether the phone's decoder can turn this HDR video into SDR itself (owner's decision D11): [ToneMapping]. */
        suspend fun decoderToneMaps(resolver: ContentResolver, item: MediaItem): Boolean = ToneMapping.byDecoder(resolver, item)
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
