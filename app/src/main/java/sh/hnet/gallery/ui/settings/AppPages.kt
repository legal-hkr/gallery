@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.DestructiveButton
import sh.hnet.gallery.ui.components.SettingsDescription
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsGroupHeader
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.SettingsRadioRow

/** A language the app is translated into; an empty tag follows the system. */
class AppLanguage(val tag: String, @param:StringRes val name: Int)

val appLanguages = listOf(
    AppLanguage("", R.string.language_system_default),
    AppLanguage("en", R.string.language_name_en),
    AppLanguage("pl", R.string.language_name_pl),
)

/** The app's language tag; empty when it follows the system. */
fun currentLanguageTag(context: Context): String {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) "" else locales.get(0)?.language.orEmpty()
}

private fun setLanguage(context: Context, tag: String) {
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

/**
 * Language (as ComfyChair): one choice among the translations. The activity isn't recreated by a change of language
 * (configChanges, so the editor stays open), so the choice is held here, set by the tap and read again at each change
 * of configuration (owner, after v0.1.1: the list kept the old language marked – remembered once, as it was entered).
 */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var current by remember(configuration) { mutableStateOf(currentLanguageTag(context)) }
    SettingsPage(title = stringResource(R.string.title_settings_language), onBack = onBack) {
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                appLanguages.forEach { language ->
                    row { shapes ->
                        SettingsRadioRow(shapes, stringResource(language.name), selected = language.tag == current, onSelect = {
                            if (language.tag != current) {
                                setLanguage(context, language.tag)
                                current = language.tag
                            }
                        })
                    }
                }
            }
        }
    }
}

/**
 * Reset: Restore defaults, confirmed (as ComfyChair). Settings go back to their defaults and the
 * language follows the system.
 */
@Composable
fun ResetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val done = stringResource(R.string.msg_defaults_restored)
    SettingsPage(title = stringResource(R.string.title_settings_reset), onBack = onBack) {
        SettingsGroupHeader(stringResource(R.string.title_settings))
        SettingsDescription(stringResource(R.string.desc_settings_reset))
        // A single destructive action: an outlined button in the error color (owner's decision)
        DestructiveButton(stringResource(R.string.button_restore_defaults), R.drawable.ic_history, { confirming = true }, Modifier.padding(top = 8.dp))
    }
    if (confirming) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_history,
            title = stringResource(R.string.title_confirm_restore_defaults),
            text = AnnotatedString(stringResource(R.string.msg_confirm_restore_defaults)),
            confirmLabel = stringResource(R.string.button_restore),
            onConfirm = {
                confirming = false
                scope.launch {
                    app.preferences.restoreDefaults()
                    Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                    // Last: a language change recreates the activity
                    if (currentLanguageTag(context).isNotEmpty()) setLanguage(context, "")
                }
            },
            onDismiss = { confirming = false },
        )
    }
}
