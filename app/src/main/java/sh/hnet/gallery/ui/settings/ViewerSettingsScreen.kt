package sh.hnet.gallery.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.SettingsSwitchRow

/** Settings › Viewer (SPEC §20.4): whether motion photos play once when shown. Nothing else (the backdrop is fixed). */
@Composable
fun ViewerSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val autoplay by app.preferences.autoplayMotion.collectAsStateWithLifecycle(true)
    SettingsPage(title = stringResource(R.string.settings_viewer), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_motion_photos_on, stringResource(R.string.label_autoplay_motion), autoplay,
                    onCheckedChange = { scope.launch { app.preferences.setAutoplayMotion(it) } },
                    supporting = stringResource(R.string.desc_autoplay_motion),
                )
            }
        }
    }
}
