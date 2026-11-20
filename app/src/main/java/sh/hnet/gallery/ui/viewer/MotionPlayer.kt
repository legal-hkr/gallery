package sh.hnet.gallery.ui.viewer

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.FileInputStream
import java.io.IOException
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.MediaItem

/**
 * Reads a motion photo's video straight out of the photo file (SPEC §7.5, LOGIKA §13.5): the last
 * [length] bytes of the file, without copying them anywhere.
 */
@OptIn(UnstableApi::class)
private class TailDataSource(private val resolver: ContentResolver, private val length: Long) : BaseDataSource(false) {
    private var descriptor: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var remaining = 0L
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val file = resolver.openFileDescriptor(dataSpec.uri, "r") ?: throw IOException("No descriptor")
        descriptor = file
        val start = file.statSize - length
        if (start < 0) throw IOException("Motion longer than the file")
        val stream = FileInputStream(file.fileDescriptor)
        stream.channel.position(start + dataSpec.position)
        input = stream
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val read = input?.read(buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (read < 0) return C.RESULT_END_OF_INPUT
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
            descriptor?.close()
        } finally {
            input = null
            descriptor = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * A motion photo's video over its still, muted unless [sound] (SPEC §7.5): shown only while [playing]; [loop] plays
 * it round while a finger holds the photo. [onEnded] when it played through once. Drawn on a
 * TextureView, so it zooms and moves with the photo.
 */
@OptIn(UnstableApi::class)
@Composable
fun MotionVideo(item: MediaItem, length: Long, playing: Boolean, loop: Boolean, sound: Boolean, onEnded: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val ended by rememberUpdatedState(onEnded)
    var started by remember(item.id, item.generation) { mutableStateOf(false) }
    val player = remember(item.id, item.generation) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory { TailDataSource(resolver, length) })
            .build()
            .apply {
                volume = 0f
                setMediaItem(PlayerItem.fromUri(item.uri()))
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onRenderedFirstFrame() {
                started = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Sound only for a hold in an opening whose sound was turned on (SPEC §7.5); never the audio focus taken from other apps' music
    LaunchedEffect(sound) { player.volume = if (sound) 1f else 0f }
    LaunchedEffect(playing, loop) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (playing) {
            player.seekTo(0)
            player.play()
        } else {
            player.pause()
            started = false
        }
    }
    // Hidden until its first frame is drawn, so the still doesn't blink to black
    AndroidView(
        // The surface given to the player of now: a new one comes with a new version of the file (a favorite, a
        // rename), and the view made once never reached it (review before v0.1.0: the motion stopped moving)
        factory = { TextureView(it) },
        update = { view ->
            if (view.tag !== player) {
                player.setVideoTextureView(view)
                view.tag = player
            }
        },
        modifier = modifier.graphicsLayer { alpha = if (playing && started) 1f else 0f },
    )
}
