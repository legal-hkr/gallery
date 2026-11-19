@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.gallery.R

/**
 * The status card (SPEC §10.4): work that takes a while, floating over the content until it ends –
 * its title, a line of progress, the wavy indicator, and Cancel when the work can stop (what is done
 * stays done). Drawn on the highest container with the level-3 shadow, the theme's roles only.
 *
 * @param progress Done so far, 0–1; null while the amount isn't known
 */
@Composable
fun StatusCard(title: String, line: String, progress: Float?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (onCancel != null) 4.dp else 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (progress != null) LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (onCancel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.button_cancel)) }
            }
        }
    }
}
