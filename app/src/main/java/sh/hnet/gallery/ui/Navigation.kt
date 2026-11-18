package sh.hnet.gallery.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import sh.hnet.gallery.R

/** Navigation bar destinations; each keeps its own back stack. Placeholder set until the handoff settles them. */
enum class Tab(val root: Screen, @param:StringRes val label: Int, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int) {
    PHOTOS(Screen.Photos, R.string.label_nav_photos, R.drawable.ic_photo_library, R.drawable.ic_photo_library_filled),
    ALBUMS(Screen.Albums, R.string.label_nav_albums, R.drawable.ic_photo_album, R.drawable.ic_photo_album_filled),
    SEARCH(Screen.Search, R.string.label_nav_search, R.drawable.ic_search, R.drawable.ic_search_filled),
    SETTINGS(Screen.Settings, R.string.label_nav_settings, R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Photos : Screen
    @Serializable data object Albums : Screen
    @Serializable data object Search : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsLicenses : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
}
