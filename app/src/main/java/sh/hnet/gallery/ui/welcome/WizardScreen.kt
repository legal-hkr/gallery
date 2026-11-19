@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.welcome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.Access
import sh.hnet.gallery.ui.components.AppLogo
import sh.hnet.gallery.ui.components.FormPage
import sh.hnet.gallery.ui.components.RowIcon
import sh.hnet.gallery.ui.components.SettingsRadioRow
import sh.hnet.gallery.ui.components.groupShapes
import sh.hnet.gallery.ui.components.rememberMediaAccessAsk
import sh.hnet.gallery.ui.components.settingsRowColors
import sh.hnet.gallery.ui.settings.rememberRestoreAction

private enum class Step { ACCESS, MEDIA_MANAGEMENT, BACKUP, READING }

/**
 * The first run (SPEC §19), the family's wizard as Contacts', lying over the timeline until it is
 * done: access, Media management (optional), backup or a fresh start, and the first reading of the
 * photos (owner's decision D4). Access already given skips the first step, Media management already
 * given the second; without access there is nothing to read, so the wizard ends with the backup.
 */
@Composable
fun WizardScreen(snackbars: SnackbarHostState, messageScope: CoroutineScope, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val startedWithAccess = rememberSaveable { app.media.access() != Access.NONE }
    val startedManaging = rememberSaveable { MediaStore.canManageMedia(context) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    // Asked of the system at every step change: the answer to step 1 has to count at once, not a frame later.
    // On step 1 itself the answer isn't given yet: the count assumes access, and shrinks only after a refusal (as Contacts')
    val hasAccess = app.media.access() != Access.NONE || index == 0
    val steps = buildList {
        if (!startedWithAccess) add(Step.ACCESS)
        // Asked only once access is there: without it there is nothing to manage
        if (!startedManaging && hasAccess) add(Step.MEDIA_MANAGEMENT)
        add(Step.BACKUP)
        if (startedWithAccess || hasAccess) add(Step.READING)
    }
    val step = steps.getOrElse(index) { steps.last() }
    val next: () -> Unit = { if (index >= steps.lastIndex) onDone() else index++ }
    val label = stringResource(R.string.label_step, index + 1, steps.size)
    val last = index == steps.lastIndex
    when (step) {
        Step.ACCESS -> AccessStep(label, onNext = {
            app.index.recheck()
            next()
        })
        Step.MEDIA_MANAGEMENT -> MediaManagementStep(label, onNext = next)
        Step.BACKUP -> BackupStep(label, last, snackbars, messageScope, onNext = next)
        Step.READING -> ReadingStep(label, onDone = onDone)
    }
}

/** A step's frame (SPEC §19): the app's mark, "Step n of 4", the title and its paragraph, then the step's own content. */
@Composable
private fun WizardStep(
    step: String,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    FormPage(title = null, confirmLabel = confirmLabel, onConfirm = onConfirm, contentSpacing = 0.dp) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(step, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = TITLE_INSET))
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 4.dp).semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp, bottom = 20.dp))
        content()
    }
}

/** A segmented row that only informs, with its icon (SPEC §19.1). */
@Composable
private fun InfoRow(index: Int, count: Int, @DrawableRes icon: Int, title: String, supporting: String) {
    SegmentedListItem(
        shapes = groupShapes(index, count),
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        supportingContent = { Text(supporting) },
    ) {
        Text(title)
    }
}

/** Step 1 (SPEC §19.1): what the access is for, Allow access (photos, videos and the location in them in the system's dialogs), and Not now. */
@Composable
private fun AccessStep(step: String, onNext: () -> Unit) {
    val ask = rememberMediaAccessAsk(onNext)
    WizardStep(step, stringResource(R.string.app_name), stringResource(R.string.msg_wizard_access), stringResource(R.string.button_allow_access), ask) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InfoRow(0, 2, R.drawable.ic_cloud_off, stringResource(R.string.title_no_internet), stringResource(R.string.desc_no_internet))
            InfoRow(1, 2, R.drawable.ic_location_on, stringResource(R.string.title_location_in_photos), stringResource(R.string.desc_location_in_photos))
        }
        TextButton(onClick = onNext, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.button_not_now)) }
    }
}

/**
 * Step 2 (SPEC §19.2): Media management, optional. Open settings leads to the system's page for
 * the app; whatever is chosen there, the wizard goes on. The first of the two offers (SPEC §10.6).
 */
@Composable
private fun MediaManagementStep(step: String, onNext: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    LaunchedEffect(Unit) { app.preferences.markMediaManagementOffered() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onNext() }
    WizardStep(step, stringResource(R.string.title_media_management), stringResource(R.string.msg_wizard_media_management), stringResource(R.string.button_open_settings), onConfirm = {
        try {
            settings.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            // A system without the page (a stripped-down build): nothing to choose, the wizard goes on
            onNext()
        }
    }) {
        InfoRow(0, 1, R.drawable.ic_folder_managed, stringResource(R.string.title_optional_access), stringResource(R.string.desc_optional_access))
        TextButton(onClick = onNext, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.button_skip)) }
    }
}

/** Step 3 (SPEC §19.3, the family's question): start fresh or restore a backup, and Next (Start when it is the last step), which acts on the choice. */
@Composable
private fun BackupStep(step: String, last: Boolean, snackbars: SnackbarHostState, messageScope: CoroutineScope, onNext: () -> Unit) {
    var restore by rememberSaveable { mutableStateOf(false) }
    val restoreAction = rememberRestoreAction(snackbars, messageScope, onRestored = onNext)
    WizardStep(step, stringResource(R.string.title_wizard_backup), stringResource(R.string.desc_backup_contents), stringResource(if (last) R.string.button_start else R.string.button_next), onConfirm = { if (restore) restoreAction.pick() else onNext() }) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsRadioRow(groupShapes(0, 2), stringResource(R.string.title_start_fresh), selected = !restore, onSelect = { restore = false }, supporting = stringResource(R.string.desc_start_fresh))
            SettingsRadioRow(groupShapes(1, 2), stringResource(R.string.title_restore_backup), selected = restore, onSelect = { restore = true }, supporting = stringResource(R.string.desc_restore_backup))
        }
    }
}

/**
 * Step 4 (owner's decision D4): the first reading of the photos, started when access was given, with
 * its progress. Start is enabled at once: the reading goes on, and Photos shows its status card until it ends.
 */
@Composable
private fun ReadingStep(step: String, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val items by app.index.items.collectAsStateWithLifecycle()
    val facts by app.facts.facts.collectAsStateWithLifecycle()
    val progress by app.facts.progress.collectAsStateWithLifecycle()
    WizardStep(step, stringResource(R.string.title_wizard_reading), stringResource(R.string.msg_wizard_reading), stringResource(R.string.button_start), onDone) {
        val running = progress
        // Done only when every item's facts are there and current; before the reading starts, the line waits
        val complete = remember(items, facts) {
            val known = facts
            val list = items
            known != null && list != null && list.all { it.trashed || known[it.id]?.generation == it.generation }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = TITLE_INSET), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                running != null -> {
                    LinearWavyProgressIndicator(progress = { running.done.toFloat() / running.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.progress_read, running.done, running.total), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                complete -> {
                    LinearWavyProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.label_read_done), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The photos are still being listed
                else -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.desc_wizard_reading_later), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Start inset of the title and texts, which the logo lines up with. */
private val TITLE_INSET = 8.dp
