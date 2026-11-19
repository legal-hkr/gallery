@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.settings

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.backup.BackupCheck
import sh.hnet.gallery.backup.BackupManager
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.ErrorDetails
import sh.hnet.gallery.ui.components.ErrorDetailsDialog
import sh.hnet.gallery.ui.components.RowIcon
import sh.hnet.gallery.ui.components.SettingsDescription
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.boldNamesResource
import sh.hnet.gallery.ui.components.reportFailure
import sh.hnet.gallery.ui.components.settingsRowColors

/** The picker's types for a backup: JSON plus what files often arrive as. */
private val BACKUP_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/** "gallery_backup_20261119_142005.json", as the family names its backups. */
private fun backupFileName(): String = "gallery_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"

/** "16 Nov 2026, 14:20" in the app's language. */
private fun backupDate(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context, millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
)

/** A backup's ISO date as [backupDate], or as written when it can't be read. */
private fun backupDate(context: Context, iso: String): String = runCatching { backupDate(context, Instant.parse(iso).toEpochMilli()) }.getOrDefault(iso)

/**
 * Restoring from a file (SPEC §19.3, §20.2), wherever it starts – Settings › Backup or the
 * first-run wizard: the picker, the check of the picked file, the confirmation with what the file
 * brings, then the restore. Errors come as a snackbar with Show.
 */
class RestoreAction internal constructor(val pick: () -> Unit)

@Composable
fun rememberRestoreAction(snackbars: SnackbarHostState, messageScope: CoroutineScope, onRestored: () -> Unit = {}): RestoreAction {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as GalleryApplication
    var pending by remember { mutableStateOf<BackupManager.Picked?>(null) }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    var missingShown by remember { mutableStateOf<Map<String, Int>?>(null) }
    val notOurs = stringResource(R.string.error_backup_not_ours)
    val newer = stringResource(R.string.error_backup_newer)
    val unreadable = stringResource(R.string.error_backup_unreadable)
    val restored = stringResource(R.string.msg_settings_restored)
    val show = stringResource(R.string.button_show)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        messageScope.launch {
            val picked = app.backup.read(uri)
            val name = picked.fileName.htmlEncode()
            when (val check = picked.check) {
                is BackupCheck.Valid -> pending = picked
                null -> messageScope.reportFailure(snackbars, AnnotatedString.fromHtml(unreadable.format(name)).text, picked.details.orEmpty(), show) { errorShown = it }
                is BackupCheck.NotOurs -> messageScope.reportFailure(snackbars, AnnotatedString.fromHtml(notOurs.format(name)).text, check.details, show) { errorShown = it }
                is BackupCheck.Newer -> messageScope.reportFailure(snackbars, newer, "formatVersion ${check.formatVersion}", show) { errorShown = it }
            }
        }
    }

    pending?.let { picked ->
        val backup = (picked.check as BackupCheck.Valid).backup
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_settings_backup_restore,
            title = AnnotatedString(stringResource(R.string.title_restore_confirm)),
            confirmLabel = stringResource(R.string.button_restore),
            onConfirm = {
                pending = null
                messageScope.launch {
                    val missing = app.backup.restore(backup)
                    onRestored()
                    snackbars.currentSnackbarData?.dismiss()
                    if (missing.isEmpty()) {
                        snackbars.showSnackbar(restored, duration = SnackbarDuration.Short)
                    } else {
                        // Some album items aren't on this phone: how many, and Show lists them per album (SPEC-albums §20.2)
                        val result = snackbars.showSnackbar(resources.getString(R.string.msg_restored_missing, missing.values.sum()), actionLabel = show, duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) missingShown = missing
                    }
                }
            },
            onDismiss = { pending = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(boldNamesResource(R.string.msg_restore_from, picked.fileName, backupDate(context, backup.createdAt), backup.appVersion))
                Text(stringResource(R.string.msg_restore_summary))
            }
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
    missingShown?.let { missing ->
        AlertDialog(
            onDismissRequest = { missingShown = null },
            title = { Text(stringResource(R.string.title_missing_items)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((album, count) in missing) Text(stringResource(R.string.format_missing_per_album, album, count))
                }
            },
            confirmButton = { TextButton(onClick = { missingShown = null }) { Text(stringResource(R.string.button_close)) } },
        )
    }

    return remember(picker) { RestoreAction { picker.launch(BACKUP_MIME_TYPES) } }
}

/**
 * Settings › Backup (SPEC §20.2, Contacts' page): what a backup holds (photos, videos and the
 * vault are not in it), Create backup and Restore from backup.
 */
@Composable
fun BackupScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val lastBackup by app.backup.lastBackup.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    val savedText = stringResource(R.string.msg_backup_saved)
    val writeError = stringResource(R.string.error_backup_write)
    val show = stringResource(R.string.button_show)
    val restore = rememberRestoreAction(snackbars, messageScope)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        messageScope.launch {
            // The row shows Saving… only when it takes a moment
            val indicator = launch { delay(300); saving = true }
            val result = app.backup.create(uri)
            indicator.cancel()
            saving = false
            when (result) {
                is BackupManager.Created.Saved -> {
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(AnnotatedString.fromHtml(savedText.format(result.fileName.htmlEncode())).text, duration = SnackbarDuration.Short)
                }
                is BackupManager.Created.Failed -> messageScope.reportFailure(
                    snackbars, AnnotatedString.fromHtml(writeError.format(result.fileName.htmlEncode())).text, result.details, show,
                ) { errorShown = it }
            }
        }
    }

    SettingsPage(title = stringResource(R.string.title_backup), onBack = onBack) {
        SettingsDescription(stringResource(R.string.desc_backup_contents))
        SettingsGroup {
            row { shapes ->
                CreateBackupRow(shapes, saving, lastBackup?.let { stringResource(R.string.label_last_backup, backupDate(context, it)) } ?: stringResource(R.string.label_no_backup)) {
                    create.launch(backupFileName())
                }
            }
            row { shapes ->
                SegmentedListItem(
                    onClick = restore.pick,
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_settings_backup_restore) },
                    supportingContent = { Text(stringResource(R.string.desc_restore_backup)) },
                ) {
                    Text(stringResource(R.string.label_restore_backup))
                }
            }
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
}

@Composable
private fun CreateBackupRow(shapes: ListItemShapes, saving: Boolean, supporting: String, onClick: () -> Unit) {
    val savingText = stringResource(R.string.label_saving_backup)
    SegmentedListItem(
        onClick = onClick,
        enabled = !saving,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            if (saving) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
            else RowIcon(R.drawable.ic_save)
        },
        supportingContent = { Text(if (saving) savingText else supporting) },
        modifier = Modifier.semantics { if (saving) stateDescription = savingText },
    ) {
        Text(stringResource(R.string.label_create_backup))
    }
}
