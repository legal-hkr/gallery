package sh.hnet.gallery.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import sh.hnet.gallery.R

/** Navigation bar destinations (SPEC §3.1); each keeps its own back stack. Settings open over a tab from its top bar. */
enum class Tab(val root: Screen, @param:StringRes val label: Int, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int) {
    PHOTOS(Screen.Photos, R.string.label_nav_photos, R.drawable.ic_photo_library, R.drawable.ic_photo_library_filled),
    ALBUMS(Screen.Albums, R.string.label_nav_albums, R.drawable.ic_photo_album, R.drawable.ic_photo_album_filled),
    SEARCH(Screen.Search, R.string.label_nav_search, R.drawable.ic_search, R.drawable.ic_search_filled),
}

@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Photos : Screen
    @Serializable data object Albums : Screen
    @Serializable data object Search : Screen
    /** A collection of the Albums tab, by [sh.hnet.gallery.model.CollectionKind] name. */
    @Serializable data class Collection(val kind: String) : Screen
    @Serializable data object Trash : Screen

    /** Places (SPEC §13.1) and its pages; a country's or town's index is valid for the place data of [version] only. */
    @Serializable data object Places : Screen
    @Serializable data class PlaceCountry(val country: Int, val version: Int) : Screen
    @Serializable data class PlaceTown(val settlement: Int, val version: Int) : Screen
    @Serializable data object NoLocation : Screen
    /** A source's album (SPEC-albums §9.10), by [sh.hnet.gallery.model.FolderKey.key]. */
    @Serializable data class Folder(val key: String) : Screen
    /** A user album (SPEC-albums §9.7), by its id. */
    @Serializable data class Album(val id: Long) : Screen

    /** The picker adding to a user album (SPEC-albums §9.6, §9.9; LOGIKA §16.6). */
    @Serializable data class PickForAlbum(val id: Long) : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsPhotos : Screen
    @Serializable data object SettingsViewer : Screen
    @Serializable data object SettingsSharing : Screen
    @Serializable data object SettingsPlaces : Screen
    @Serializable data object SettingsBackup : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsLicenses : Screen
    @Serializable data object SettingsPlaceData : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
}
