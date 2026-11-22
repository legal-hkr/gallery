@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.gallery.ui.viewer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.hnet.gallery.R
import sh.hnet.gallery.model.Playback
import sh.hnet.gallery.ui.grid.formatDuration

/**
 * The video player's controls (SPEC §8.1, §8.5; ComfyChair's playback card): in portrait two rows –
 * the time, the seek slider and the length; then sound, frame back, play, frame forward, speed –; in
 * landscape one row beside the toolbar, the frame steps taking the place of speed and sound while
 * paused. The card takes every drag inside it, so seeking never swipes the page or closes the viewer; taps go to its buttons.
 *
 * @param onInteraction Any use of a control (the chrome's 3 s start again, D9)
 */
@Composable
fun PlaybackCard(player: ViewerPlayer, compact: Boolean, onInteraction: () -> Unit, modifier: Modifier = Modifier) {
    val state by player.state.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(player.positionMs()) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var playedBeforeDrag by remember { mutableStateOf(false) }
    // A gesture that began on the slider is the slider's; the card takes every other touch
    val gestureOnSlider = remember { BooleanArray(1) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.playing, state.durationMs, state.ended, refresh) {
        do {
            positionMs = player.positionMs()
            if (state.playing) delay(100)
        } while (state.playing)
    }
    val durationMs = state.durationMs
    val shownMs = dragPosition?.toLong() ?: positionMs
    val loading = durationMs == null

    fun act(action: () -> Unit) {
        onInteraction()
        action()
        refresh++
    }

    val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
        val range = 0f..(durationMs ?: 1L).toFloat()
        val sliderState = remember(range) { SliderState(trackRange = range) }
        sliderState.value = shownMs.toFloat().coerceIn(range)
        Slider(
            state = sliderState,
            // Scrubbing: the picture follows the finger (paused meanwhile), landing exactly on its frame at the end
            onValueChange = { value ->
                if (dragPosition == null) {
                    playedBeforeDrag = state.playing
                    if (state.playing) player.togglePlay()
                }
                dragPosition = value
                player.scrubTo(value.toLong())
            },
            onValueChangeFinished = {
                dragPosition?.let { player.endScrub(it.toLong()) }
                dragPosition = null
                if (playedBeforeDrag) player.togglePlay()
                playedBeforeDrag = false
                act {}
            },
            enabled = !loading,
            track = { SliderDefaults.Track(sliderState = it, modifier = Modifier.height(16.dp), drawStopIndicator = null) },
            modifier = sliderModifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    gestureOnSlider[0] = true
                }
            },
        )
    }
    val time = formatDuration(shownMs)
    val length = durationMs?.let { formatDuration(it) } ?: "–:––"
    val timeStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .widthIn(max = 600.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = false
                    do {
                        val event = awaitPointerEvent()
                        if (!gestureOnSlider[0]) {
                            // A tap's jitter left alone: a move consumed here made the button under the finger cancel its
                            // click (a clickable's check in the final pass), so Pause took several taps (owner's test of
                            // S9b). From half the touch slop on it is a drag, taken before the swipe down would start
                            if (!dragged) dragged = event.changes.size > 1 || event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop / 2 }
                            if (dragged) event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    gestureOnSlider[0] = false
                }
            },
    ) {
        if (compact) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(state, loading, size = 48) { act { player.togglePlay() } }
                Text(time, style = timeStyle, modifier = Modifier.padding(start = 8.dp))
                slider(Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(length, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.playing) {
                    SpeedChip(state.speed) { speed -> act { player.setSpeed(speed) } }
                    SoundButton(state) { act { player.setMuted(!state.muted) } }
                } else {
                    FrameStep(R.drawable.ic_arrow_left, stringResource(R.string.content_description_frame_back), !loading) { act { player.step(false) } }
                    FrameStep(R.drawable.ic_arrow_right, stringResource(R.string.content_description_frame_forward), !loading) { act { player.step(true) } }
                }
            }
        } else {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = timeStyle)
                    slider(Modifier.weight(1f).padding(horizontal = 12.dp))
                    Text(length, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SoundButton(state) { act { player.setMuted(!state.muted) } }
                    FrameStep(R.drawable.ic_arrow_left, stringResource(R.string.content_description_frame_back), !loading && !state.playing) { act { player.step(false) } }
                    PlayButton(state, loading, size = 64) { act { player.togglePlay() } }
                    FrameStep(R.drawable.ic_arrow_right, stringResource(R.string.content_description_frame_forward), !loading && !state.playing) { act { player.step(true) } }
                    SpeedChip(state.speed) { speed -> act { player.setSpeed(speed) } }
                }
            }
        }
    }
}

/**
 * Play and pause (SPEC §8.1) as Music's transport: the Expressive filled toggle button, a rounded
 * square while paused and round while playing (the shape tells them apart, the colours stay); replay
 * once a long video stopped at its end.
 */
@Composable
private fun PlayButton(state: ViewerPlayer.State, loading: Boolean, size: Int, onClick: () -> Unit) {
    val label = stringResource(
        when {
            state.ended -> R.string.content_description_replay
            state.playing -> R.string.content_description_pause
            else -> R.string.content_description_play
        },
    )
    val colors = IconButtonDefaults.filledIconToggleButtonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        checkedContainerColor = MaterialTheme.colorScheme.primary,
        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
    )
    FilledIconToggleButton(
        checked = state.playing,
        onCheckedChange = { onClick() },
        enabled = !loading,
        shapes = IconButtonDefaults.toggleableShapes(shape = RoundedCornerShape(PAUSED_RADIUS), checkedShape = CircleShape),
        colors = colors,
        modifier = Modifier.size(width = size.dp, height = if (size > 48) 56.dp else 48.dp),
    ) {
        val icon = when {
            state.ended -> R.drawable.ic_replay
            state.playing -> R.drawable.ic_pause_filled
            else -> R.drawable.ic_play_arrow_filled
        }
        Icon(painterResource(icon), contentDescription = label)
    }
}

/** The paused play button's corners, as Music's square-at-rest transport (scaled to the card's 56 dp). */
private val PAUSED_RADIUS = 16.dp

/** Sound for this opening of the viewer (SPEC §8.3): off by default; a video without sound shows it off, disabled. */
@Composable
private fun SoundButton(state: ViewerPlayer.State, onClick: () -> Unit) {
    val noSound = state.hasAudio == false
    val label = stringResource(if (state.muted) R.string.content_description_sound_on else R.string.content_description_sound_off)
    IconButton(onClick = onClick, enabled = !noSound, modifier = Modifier.semantics { contentDescription = label }) {
        Icon(painterResource(if (state.muted || noSound) R.drawable.ic_volume_off else R.drawable.ic_volume_up), contentDescription = null)
    }
}

/** A frame step (SPEC §8.2): one frame per tap; held, it repeats after 400 ms every 80 ms. */
@Composable
private fun FrameStep(@DrawableRes icon: Int, label: String, enabled: Boolean, onStep: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var steppedByPress by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        steppedByPress = true
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onStep()
        delay(400)
        while (true) {
            onStep()
            delay(80)
        }
    }
    IconButton(
        onClick = {
            if (!steppedByPress) onStep()
            steppedByPress = false
        },
        enabled = enabled,
        interactionSource = interactionSource,
    ) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

/** The speed chip (SPEC §8.2): "1×", opening the speeds in the family's menu, the current one selected. */
@Composable
private fun SpeedChip(speed: Float, onSpeed: (Float) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var open by remember { mutableStateOf(false) }
    val text = stringResource(R.string.label_speed, Playback.speedText(speed, locale))
    val described = stringResource(R.string.content_description_speed, Playback.speedText(speed, locale))
    Box {
        AssistChip(onClick = { open = true }, label = { Text(text) }, modifier = Modifier.semantics { stateDescription = described })
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                Playback.SPEEDS.forEachIndexed { index, value ->
                    SelectableDropdownMenuItem(
                        selected = value == speed,
                        onClick = {
                            open = false
                            onSpeed(value)
                        },
                        text = { Text(stringResource(R.string.label_speed, Playback.speedText(value, locale)), maxLines = 1) },
                        shapes = MenuDefaults.itemShape(index, Playback.SPEEDS.size),
                        trailingContent = { if (value == speed) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}
