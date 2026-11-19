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

/**
 * Settings › Sharing (SPEC §14.1, board 21f): shares go as copies without location, or as the originals; the share
 * sheet's action changes it for one share.
 */
@Composable
fun SharingSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val remove by app.preferences.shareWithoutLocation.collectAsStateWithLifecycle(true)
    SettingsPage(title = stringResource(R.string.settings_sharing), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_location_off, stringResource(R.string.label_remove_location), remove,
                    onCheckedChange = { scope.launch { app.preferences.setShareWithoutLocation(it) } },
                    supporting = stringResource(R.string.desc_remove_location),
                )
            }
        }
    }
}
