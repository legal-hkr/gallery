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
 * Settings › Places (SPEC §20.5): place names from the data in the app. Off: only coordinates, and the place index is
 * cleared; on again: rebuilt in the background (LOGIKA §17.4).
 */
@Composable
fun PlacesSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val on by app.preferences.placeNames.collectAsStateWithLifecycle(true)
    SettingsPage(title = stringResource(R.string.label_places), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes, R.drawable.ic_location_on, stringResource(R.string.label_place_names), on,
                    onCheckedChange = { scope.launch { app.preferences.setPlaceNames(it) } },
                    supporting = stringResource(R.string.desc_place_names),
                )
            }
        }
    }
}
