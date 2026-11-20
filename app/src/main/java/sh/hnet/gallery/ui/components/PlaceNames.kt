package sh.hnet.gallery.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.PlaceData
import sh.hnet.gallery.model.PlaceLanguage

/** The names' language: the app's (LOGIKA §17.4). */
@Composable
fun placeLanguage(): PlaceLanguage = if (LocalConfiguration.current.locales[0].language == "pl") PlaceLanguage.POLISH else PlaceLanguage.ENGLISH

/** The place data while place names are on; null while off (SPEC §20.5) or in a build without it. */
@Composable
fun placeData(): PlaceData? {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val data by app.places.collectAsStateWithLifecycle()
    return data
}

/** A fact's place with the data it was placed by; anything placed by other data waits for the new placing. */
private fun Fact.placedBy(data: PlaceData): Boolean = places == data.version

/**
 * A fact's place in words (LOGIKA §17.5): [full] "Kraków, małopolskie, Polska" (Info), else "Kraków, Polska" (under
 * the viewer's date); the parts there are. Null when the item has no place.
 */
fun PlaceData.label(fact: Fact?, language: PlaceLanguage, full: Boolean): String? {
    if (fact == null || !fact.placedBy(this)) return null
    val parts = listOfNotNull(
        fact.settlement?.let { settlementName(it, language) },
        fact.region?.takeIf { full }?.let { regionName(it, language) },
        fact.country?.let { countryName(it, language) },
    ).filter { it.isNotEmpty() }.distinct()
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** A fact's settlement for counting places (the day headers); null when not placed by this data. */
fun PlaceData.settlementOf(fact: Fact?): Int? = fact?.takeIf { it.placedBy(this) }?.settlement
