package sh.hnet.gallery.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.gallery.BuildConfig
import sh.hnet.gallery.R
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsNavigationRow
import sh.hnet.gallery.ui.components.SettingsPage

/**
 * The Settings root (SPEC §20.1), grouped as the family's: the app's own groups (Photos so far; the
 * rest come with their stages), App (language), Data (reset), then About on its own. Opened over a
 * tab by the top bar's icon, so with Back.
 */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenPhotos: () -> Unit, onOpenLanguage: () -> Unit, onOpenBackup: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_photo_library, stringResource(R.string.label_nav_photos), stringResource(R.string.settings_photos_desc), onClick = onOpenPhotos)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup), stringResource(R.string.desc_backup_row), onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}
