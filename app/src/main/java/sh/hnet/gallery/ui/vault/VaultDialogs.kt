package sh.hnet.gallery.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import sh.hnet.gallery.R
import sh.hnet.gallery.ui.components.DialogConfirmButton
import sh.hnet.gallery.ui.components.DialogDismissButton

/**
 * Import's question (SPEC §18.6): what happens to the files, and a checkbox to delete the originals afterwards (off);
 * Cancel, Import.
 */
@Composable
fun ImportDialog(count: Int, onConfirm: (deleteOriginals: Boolean) -> Unit, onDismiss: () -> Unit) {
    var delete by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_input), contentDescription = null) },
        title = { Text(stringResource(R.string.title_import_vault, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.desc_import_vault))
                Row(
                    Modifier.fillMaxWidth().toggleable(delete, role = Role.Checkbox) { delete = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = delete, onCheckedChange = null)
                    Text(stringResource(R.string.label_delete_originals), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        dismissButton = { DialogDismissButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { DialogConfirmButton(onClick = { onConfirm(delete) }) { Text(stringResource(R.string.button_import)) } },
    )
}

/** Export's question (SPEC §18.7): the copies go unencrypted to a folder of the user's choice; Cancel, Choose folder. */
@Composable
fun ExportDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_output), contentDescription = null) },
        title = { Text(stringResource(R.string.title_export_vault, count)) },
        text = { Text(stringResource(R.string.desc_export_vault)) },
        dismissButton = { DialogDismissButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { DialogConfirmButton(onClick = onConfirm) { Text(stringResource(R.string.button_choose_folder)) } },
    )
}
