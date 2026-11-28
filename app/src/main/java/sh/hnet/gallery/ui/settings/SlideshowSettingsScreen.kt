package sh.hnet.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.model.SlideTransition
import sh.hnet.gallery.model.Slideshow
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.settingsRowColors

/**
 * Settings › Slideshow (SPEC §20.6): a photo's time and the transition, each as connected toggle buttons (as Contacts'
 * list settings); a show's own choices start from these.
 */
@Composable
fun SlideshowSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val seconds by app.preferences.slideSeconds.collectAsStateWithLifecycle(Slideshow.DEFAULT_SECONDS)
    val transition by app.preferences.slideTransition.collectAsStateWithLifecycle(SlideTransition.CROSSFADE)
    SettingsPage(title = stringResource(R.string.title_slideshow), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                ChoiceRow(
                    shapes, stringResource(R.string.header_photo_time),
                    Slideshow.SECONDS.map { stringResource(R.string.format_seconds, it) }, Slideshow.SECONDS.indexOf(seconds),
                ) { scope.launch { app.preferences.setSlideSeconds(Slideshow.SECONDS[it]) } }
            }
            row { shapes ->
                ChoiceRow(
                    shapes, stringResource(R.string.header_transition),
                    listOf(stringResource(R.string.transition_crossfade), stringResource(R.string.transition_ken_burns)), transition.ordinal,
                ) { scope.launch { app.preferences.setSlideTransition(SlideTransition.entries[it]) } }
            }
        }
    }
}

/** A setting's title, then its choices as connected toggle buttons, one chosen ([chosen]). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChoiceRow(shapes: ListItemShapes, title: String, labels: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    SegmentedListItem(shapes = shapes, colors = settingsRowColors()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text(title)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                labels.forEachIndexed { index, label ->
                    ToggleButton(
                        checked = index == chosen,
                        onCheckedChange = { if (it) onChoose(index) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // One container level above the row (the owner, 27 Nov 2026): M3's unchecked surfaceContainer is the row's own color, so the choice not taken vanished into it
                        colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
