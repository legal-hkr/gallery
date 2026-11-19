@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.gallery.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.gallery.R

/** "Media management" (SPEC §10.6): whether the app has it, and the system's page to give or take it. */
object MediaManagement {
    fun granted(context: Context): Boolean = MediaStore.canManageMedia(context)

    /** The system's page for this app's special access; false where a system has none. */
    fun openSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * The second and last offer of Media management (SPEC §10.6), right after the user confirmed the
 * system's dialog for the first time: what the access saves, Not now or Open settings. Either answer
 * ends the offers for good; afterwards the access lives only in Settings.
 */
@Composable
fun MediaManagementOfferSheet(onNotNow: () -> Unit, onOpenSettings: () -> Unit) {
    val state = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onNotNow, sheetState = state, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(48.dp).background(scheme.primaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_folder_managed), contentDescription = null, tint = scheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.title_media_management_offer), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.desc_media_management_offer), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Text(stringResource(R.string.desc_media_management_revoke), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.button_not_now)) }
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.button_open_settings)) }
            }
        }
    }
}
