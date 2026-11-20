package sh.hnet.gallery.ui.viewer

import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.ui.components.MediaThumbnail
import sh.hnet.gallery.ui.components.Thumbnails

/**
 * A video's page (SPEC §8, LOGIKA §14.1): its frame, fitted; while it is the page shown and
 * nothing moves the viewer, the shared player's surface over it – a SurfaceView, so HDR video shows
 * in HDR. The frame covers the surface until the first picture is drawn, and during the swipe down
 * and the flight (owner's decision D12). Videos don't zoom; a tap toggles the chrome.
 *
 * @param live The player's picture is wanted here now
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(item: MediaItem, player: ViewerPlayer, live: Boolean, onTap: () -> Unit, onOpenWith: (() -> Unit)?) {
    val px = Thumbnails.sizeFor(Int.MAX_VALUE)
    val state by player.state.collectAsStateWithLifecycle()
    // A video the phone can't play (no decoder for it, a damaged file): the problem card, as a photo's (SPEC §6.9)
    if (state.item == item.id && state.error) {
        BrokenFile(onOpenWith)
        return
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        MediaThumbnail(item, px, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (live) {
            val presentation = rememberPresentationState(player.player)
            val size = presentation.videoSizeDp
            val fitted = if (size != null && size.isSpecified && size.width > 0f && size.height > 0f) Modifier.aspectRatio(size.width / size.height) else Modifier.fillMaxSize()
            PlayerSurface(player.player, fitted, SURFACE_TYPE_SURFACE_VIEW)
            if (presentation.coverSurface) MediaThumbnail(item, px, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
