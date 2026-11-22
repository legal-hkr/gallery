package sh.hnet.gallery.ui.editor

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Label
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import sh.hnet.gallery.R
import sh.hnet.gallery.data.AdjustShader
import sh.hnet.gallery.model.Adjust
import sh.hnet.gallery.model.Adjustments
import sh.hnet.gallery.model.EditState
import sh.hnet.gallery.model.Filter
import sh.hnet.gallery.ui.components.TooltipIconButton

/** An adjustment's name (SPEC §15.3). */
@Composable
private fun nameOf(adjust: Adjust): String = stringResource(
    when (adjust) {
        Adjust.EXPOSURE -> R.string.adj_exposure
        Adjust.CONTRAST -> R.string.adj_contrast
        Adjust.HIGHLIGHTS -> R.string.adj_highlights
        Adjust.SHADOWS -> R.string.adj_shadows
        Adjust.SATURATION -> R.string.adj_saturation
        Adjust.WARMTH -> R.string.adj_warmth
        Adjust.SHARPNESS -> R.string.adj_sharpness
    },
)

/**
 * Adjust's tools (SPEC §15.3, LOGIKA §21.3): the seven adjustments as chips in a scrolling row – a changed one with its
 * value, so the row tells what was done –, the chosen one's centred slider below with its value in a label while
 * dragged, and a reset of that one adjustment. Each slider let go is a step of Undo.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AdjustTools(state: EditState, chosen: Adjust, onChoose: (Adjust) -> Unit, onLive: (EditState) -> Unit, onCommit: (EditState) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (adjust in Adjust.entries) {
                val value = state.adjust[adjust]
                val name = nameOf(adjust)
                val label = if (value == 0) name else stringResource(R.string.format_adjust_chip, name, Adjustments.valueText(adjust, value, locale))
                FilterChip(selected = chosen == adjust, onClick = { onChoose(adjust) }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val latest by rememberUpdatedState(state)
            val range = Adjustments.rangeOf(chosen)
            // The slider's own state, the thumb following the finger as it is (as straightening: written back, it trembled)
            val slider = remember(chosen) { SliderState(state.adjust[chosen].toFloat(), 0, range.first.toFloat()..range.last.toFloat()) }
            var dragging by remember { mutableStateOf(false) }
            // The last state sent: a tap sends and lets go before `latest` is composed again (as straightening)
            var sent by remember { mutableStateOf<EditState?>(null) }
            val value = state.adjust[chosen]
            LaunchedEffect(value, chosen) { if (!dragging) slider.value = value.toFloat() }
            val interaction = remember { MutableInteractionSource() }
            Slider(
                state = slider,
                onValueChange = { v ->
                    dragging = true
                    slider.value = v
                    val next = v.roundToInt()
                    val from = sent ?: latest
                    if (next != from.adjust[chosen]) {
                        val state2 = from.copy(adjust = from.adjust.with(chosen, next))
                        sent = state2
                        onLive(state2)
                    }
                },
                onValueChangeFinished = {
                    dragging = false
                    onCommit(sent ?: latest)
                    sent = null
                },
                interactionSource = interaction,
                // The value in a label over the thumb while it is dragged (SPEC §15.3)
                thumb = {
                    Label(
                        label = { PlainTooltip { Text(Adjustments.valueText(chosen, (sent ?: latest).adjust[chosen], locale)) } },
                        interactionSource = interaction,
                    ) { SliderDefaults.Thumb(interaction) }
                },
                track = { SliderDefaults.CenteredTrack(it) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TooltipIconButton(
                R.drawable.ic_restart_alt,
                stringResource(R.string.content_description_reset_value, nameOf(chosen)),
                { onCommit(state.copy(adjust = state.adjust.with(chosen, 0))) },
                enabled = value != 0,
            )
        }
    }
}

/**
 * Filters (SPEC §15.4, LOGIKA §21.4): six previews of this photo, each with its filter, the name under each. The chosen
 * one keeps its shape and size and is marked only by a border – a 2 dp gap in the panel's colour, then a 2 dp `primary`
 * ring – and its name in `primary`. Choosing one sets the seven adjustments to its values (D38).
 */
@Composable
internal fun FilterTools(image: ImageBitmap, state: EditState, onCommit: (EditState) -> Unit) {
    val names = mapOf(
        Filter.NONE to R.string.filter_none,
        Filter.WARM to R.string.filter_warm,
        Filter.COOL to R.string.filter_cool,
        Filter.BLACK_WHITE to R.string.filter_black_white,
        Filter.FADED to R.string.filter_faded,
        Filter.VIVID to R.string.filter_vivid,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (filter in Filter.entries) {
            val chosen = state.filter == filter
            val name = stringResource(names.getValue(filter))
            Column(
                Modifier
                    .width(80.dp)
                    .selectable(selected = chosen, role = Role.RadioButton) { onCommit(state.copy(adjust = filter.preset, filter = filter)) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 64 dp, its ring outside it: 2 dp gap and 2 dp ring, so the chosen one stays the same size
                Box(
                    Modifier
                        .size(72.dp)
                        .then(if (chosen) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
                        .padding(4.dp),
                ) {
                    FilterPreview(image, filter.preset, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The photo filling a small square (its middle), with [preset]. */
@Composable
private fun FilterPreview(image: ImageBitmap, preset: Adjustments, modifier: Modifier) {
    val adjuster = remember { AdjustShader() }
    val plain = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier) {
        val bitmap = image.asAndroidBitmap()
        val scale = max(size.width / bitmap.width, size.height / bitmap.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
        }
        drawIntoCanvas { adjuster.draw(it.nativeCanvas, bitmap, matrix, preset, plain) }
    }
}
