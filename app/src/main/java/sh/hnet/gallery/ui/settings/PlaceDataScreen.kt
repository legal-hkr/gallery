package sh.hnet.gallery.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsLinkRow
import sh.hnet.gallery.ui.components.SettingsNote
import sh.hnet.gallery.ui.components.SettingsPage

/** The place data's sources (LOGIKA §17.1): name, what comes from it, its license, its address. */
private class DataSource(val name: String, val address: String, val description: Int)

private val SOURCES = listOf(
    DataSource("Wikidata", "www.wikidata.org", R.string.desc_wikidata),
    DataSource("Natural Earth", "www.naturalearthdata.com", R.string.desc_natural_earth),
)

/**
 * Settings › About › Place data (the owner, 20 Nov 2026: say where the place names come from and under what
 * license). Neither license asks for it; the app says so all the same.
 */
@Composable
fun PlaceDataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val data by app.places.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val noBrowser = stringResource(R.string.msg_no_browser)
    SettingsPage(title = stringResource(R.string.title_place_data), onBack = onBack) {
        SettingsNote(stringResource(R.string.desc_place_data))
        SettingsGroup {
            SOURCES.forEach { source ->
                row { shapes ->
                    SettingsLinkRow(shapes, if (source.name == "Wikidata") R.drawable.ic_location_on else R.drawable.ic_public, source.name, stringResource(source.description)) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://${source.address}".toUri()))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        // The data's date, known while place names are on (the file is read only then)
        data?.version?.let { version ->
            val date = runCatching { LocalDate.of(version / 10000, version / 100 % 100, version % 100) }.getOrNull()
            if (date != null) SettingsNote(stringResource(R.string.label_place_data_date, DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date)))
        }
    }
}
