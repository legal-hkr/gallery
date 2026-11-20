package sh.hnet.gallery.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.gallery.BuildConfig
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.SlideTransition
import sh.hnet.gallery.model.Slideshow
import sh.hnet.gallery.model.VaultLockDelay
import sh.hnet.gallery.ui.components.MediaManagement
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsNavigationRow
import sh.hnet.gallery.ui.components.SettingsPage

/**
 * The Settings root (SPEC §20.1), grouped as the family's: the app's own groups (Photos so far; the
 * rest come with their stages), App (language), Data (reset), then About on its own. Opened over a
 * tab by the top bar's icon, so with Back.
 */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenPhotos: () -> Unit, onOpenViewer: () -> Unit, onOpenSharing: () -> Unit, onOpenSlideshow: () -> Unit, onOpenPlaces: () -> Unit, onOpenVault: () -> Unit, onOpenLanguage: () -> Unit, onOpenBackup: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val removeLocation by (context.applicationContext as GalleryApplication).preferences.shareWithoutLocation.collectAsStateWithLifecycle(true)
    val placeNames by (context.applicationContext as GalleryApplication).preferences.placeNames.collectAsStateWithLifecycle(true)
    val vaultState by (context.applicationContext as GalleryApplication).vault.state.collectAsStateWithLifecycle()
    val vaultDelay by (context.applicationContext as GalleryApplication).preferences.vaultLockDelay.collectAsStateWithLifecycle(VaultLockDelay.AT_ONCE)
    val slideSeconds by (context.applicationContext as GalleryApplication).preferences.slideSeconds.collectAsStateWithLifecycle(Slideshow.DEFAULT_SECONDS)
    val slideTransition by (context.applicationContext as GalleryApplication).preferences.slideTransition.collectAsStateWithLifecycle(SlideTransition.CROSSFADE)
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_photo_library, stringResource(R.string.label_nav_photos), stringResource(R.string.settings_photos_desc), onClick = onOpenPhotos)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_imagesmode, stringResource(R.string.settings_viewer), stringResource(R.string.settings_viewer_desc), onClick = onOpenViewer)
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, R.drawable.ic_share, stringResource(R.string.settings_sharing),
                    stringResource(if (removeLocation) R.string.settings_sharing_removed else R.string.settings_sharing_included),
                    onClick = onOpenSharing,
                )
            }
            row { shapes ->
                // "5 s, crossfade": the transition in lower case after the comma (SPEC's note), except a name (Ken Burns)
                val locale = LocalConfiguration.current.locales[0]
                val transition = when (slideTransition) {
                    SlideTransition.CROSSFADE -> stringResource(R.string.transition_crossfade).lowercase(locale)
                    SlideTransition.KEN_BURNS -> stringResource(R.string.transition_ken_burns)
                }
                SettingsNavigationRow(shapes, R.drawable.ic_slideshow, stringResource(R.string.title_slideshow), stringResource(R.string.settings_slideshow_desc, slideSeconds, transition), onClick = onOpenSlideshow)
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, R.drawable.ic_location_on, stringResource(R.string.label_places),
                    stringResource(if (placeNames) R.string.settings_places_on else R.string.settings_places_off),
                    onClick = onOpenPlaces,
                )
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_lock, stringResource(R.string.label_vault), vaultSettingsLine(vaultState, vaultDelay), error = vaultState is Vault.State.Lost, onClick = onOpenVault)
            }
        }
        // Media management (SPEC §20.1, LOGIKA §15.11): whether it is given, and the system's page; read again on every return
        var granted by remember { mutableStateOf(MediaManagement.granted(context)) }
        LifecycleResumeEffect(Unit) {
            granted = MediaManagement.granted(context)
            onPauseOrDispose {}
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(
                    shapes,
                    R.drawable.ic_folder_managed,
                    stringResource(R.string.title_media_management),
                    stringResource(if (granted) R.string.settings_media_granted else R.string.settings_media_not_granted),
                    onClick = { MediaManagement.openSettings(context) },
                )
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup), stringResource(R.string.settings_backup_desc), onClick = onOpenBackup)
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
