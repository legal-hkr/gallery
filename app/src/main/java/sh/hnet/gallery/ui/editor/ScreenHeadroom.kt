package sh.hnet.gallery.ui.editor

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.util.function.Consumer

/**
 * The screen's HDR headroom for this window now (its HDR/SDR ratio; 1 on an SDR screen, and while the window is in its
 * SDR mode – the display's ratio can be up for another layer, and values over SDR's white would clip here: review of
 * S10b), followed as it changes. A reader to call while drawing, so a change of the ratio redraws the preview but
 * doesn't compose it again. The HDR preview of an adjusted Ultra HDR photo is weighted by it, as the platform weights a
 * gain map.
 */
@Composable
internal fun rememberScreenHeadroom(): () -> Float {
    val display = LocalView.current.display
    val window = LocalActivity.current?.window
    val ratio = remember(display) { mutableFloatStateOf(if (display?.isHdrSdrRatioAvailable == true) display.hdrSdrRatio else 1f) }
    DisposableEffect(display) {
        if (display == null || !display.isHdrSdrRatioAvailable) return@DisposableEffect onDispose { }
        val listener = Consumer<android.view.Display> { ratio.floatValue = it.hdrSdrRatio }
        display.registerHdrSdrRatioChangedListener({ it.run() }, listener)
        onDispose { display.unregisterHdrSdrRatioChangedListener(listener) }
    }
    return remember(ratio, window) { { if (window?.colorMode == ActivityInfo.COLOR_MODE_HDR) ratio.floatValue else 1f } }
}
