package sh.hnet.gallery.ui.vault

import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.VaultEntry
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.EmptyState
import sh.hnet.gallery.ui.components.FormPage
import sh.hnet.gallery.ui.components.RowIcon
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.settingsRowColors
import sh.hnet.gallery.ui.grid.SelectableFrame
import sh.hnet.gallery.ui.grid.SelectionEffects
import sh.hnet.gallery.ui.grid.SelectionTopBar
import sh.hnet.gallery.ui.viewer.ViewerRequest
import sh.hnet.gallery.ui.viewer.mediaOrigin

/**
 * While any of the vault is on screen: no Recents preview, no screenshots or recording (SPEC §18.4). Counted: the
 * viewer closing over the vault's page must not clear what the page still needs.
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        if (window != null && secureScreens++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (window != null && --secureScreens == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** How many vault screens are shown now (the main thread's alone). */
private var secureScreens = 0

/**
 * Albums › Vault (SPEC §18): the setup page the first time, the system's prompt when locked (the lock's page when it
 * is cancelled), the lost key's card, else the grid of what is inside – 3 fixed columns, moved in last first – with
 * selection; a tap opens the viewer over the vault's items (S8b2). Import and export come with S8c.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, onOpen: (ViewerRequest) -> Unit) {
    SecureWindow()
    val app = LocalContext.current.applicationContext as GalleryApplication
    val state by app.vault.state.collectAsStateWithLifecycle()
    val access = LocalVaultActions.current
    // The prompt once on the way in; cancelled, the lock's page offers it again
    var prompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is Vault.State.Locked && !prompted) {
            prompted = true
            access?.open(then = {})
        }
    }
    when (val shown = state) {
        Vault.State.NotSetUp -> VaultSetup(onBack)
        else -> Column(Modifier.fillMaxSize()) {
            when (shown) {
                is Vault.State.Open -> VaultGrid(shown.entries, onBack, onLock = app.vault::lock, onOpen = onOpen)
                Vault.State.Locked -> {
                    VaultTopBar(onBack, onLock = null)
                    EmptyState(R.drawable.ic_lock, stringResource(R.string.msg_vault_locked)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.desc_vault_locked),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 296.dp),
                            )
                            Button(onClick = { access?.open(then = {}) }, modifier = Modifier.padding(top = 16.dp)) {
                                Icon(painterResource(R.drawable.ic_fingerprint), contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.button_unlock), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                is Vault.State.Lost -> {
                    VaultTopBar(onBack, onLock = null)
                    KeyLost(shown.files)
                }
                Vault.State.NotSetUp -> {}
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultTopBar(onBack: () -> Unit, onLock: (() -> Unit)?) {
    TopAppBar(
        navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        title = { Text(stringResource(R.string.label_vault), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
        actions = {
            if (onLock != null) {
                // Import from outside comes with S8c (D5)
                TooltipIconButton(R.drawable.ic_input, stringResource(R.string.content_description_import), {}, enabled = false)
                TooltipIconButton(R.drawable.ic_lock, stringResource(R.string.content_description_lock_now), onLock)
            }
        },
    )
}

/**
 * The setup page (SPEC §18.2): what the vault is, the four things to know first, then Create vault – or Set screen
 * lock while the phone has none (a key that needs the user can't exist without one).
 */
@Composable
private fun VaultSetup(onBack: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val app = activity.application as GalleryApplication
    val scope = rememberCoroutineScope()
    var secure by remember { mutableStateOf(VaultPrompt.secure(activity)) }
    // Back from the system's settings: the screen lock may be there now
    LifecycleResumeEffect(Unit) {
        secure = VaultPrompt.secure(activity)
        onPauseOrDispose {}
    }
    val createTitle = stringResource(R.string.prompt_create_vault)
    FormPage(
        title = stringResource(R.string.label_vault),
        confirmLabel = stringResource(if (secure) R.string.button_create_vault else R.string.button_set_screen_lock),
        confirmIcon = if (secure) R.drawable.ic_fingerprint else R.drawable.ic_lock,
        onBack = onBack,
        onConfirm = {
            if (!secure) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } else {
                val cipher = app.vault.prepareCreate()
                if (cipher == null) {
                    secure = VaultPrompt.secure(activity)
                } else {
                    VaultPrompt.show(activity, createTitle, cipher, onPassed = { passed -> scope.launch { app.vault.created(passed) } })
                }
            }
        },
    ) {
        Text(stringResource(R.string.desc_vault_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.header_vault_before), style = MaterialTheme.typography.titleSmall)
        SettingsGroup {
            row { shapes -> Warning(shapes, R.drawable.ic_backup, R.string.title_vault_no_backup, R.string.desc_vault_no_backup) }
            row { shapes -> Warning(shapes, R.drawable.ic_delete, R.string.title_vault_uninstall, R.string.desc_vault_uninstall) }
            row { shapes -> Warning(shapes, R.drawable.ic_lock_open, R.string.title_vault_screen_lock, R.string.desc_vault_screen_lock) }
            row { shapes -> Warning(shapes, R.drawable.ic_key_off, R.string.title_vault_no_recovery, R.string.desc_vault_no_recovery) }
        }
    }
}

/** One of the setup's warnings: an icon box on the error container, nothing to tap. */
@Composable
private fun Warning(shapes: ListItemShapes, icon: Int, title: Int, text: Int) {
    SegmentedListItem(
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon, error = true) },
        supportingContent = { Text(stringResource(text)) },
    ) {
        Text(stringResource(title))
    }
}

/**
 * The key is gone (SPEC §18.8): the screen lock was switched off or the credentials reset, so the files can't be read;
 * the family's problem card with Delete the unreadable content, then the vault can be set up again.
 */
@Composable
private fun KeyLost(files: Int) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_key_off), contentDescription = null)
                    Text(stringResource(R.string.error_vault_key_lost, files), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { asking = true }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.button_delete_unreadable), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
    if (asking) {
        ConfirmDestructiveDialog(
            R.drawable.ic_delete_forever,
            stringResource(R.string.title_delete_unreadable),
            AnnotatedString(stringResource(R.string.desc_delete_unreadable, files)),
            stringResource(R.string.button_delete),
            onConfirm = {
                asking = false
                scope.launch { app.vault.forget() }
            },
            onDismiss = { asking = false },
        )
    }
}

/** The open vault (SPEC §18.3): 3 fixed columns, no day headers; long press and taps select. */
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VaultGrid(entries: List<VaultEntry>, onBack: () -> Unit, onLock: () -> Unit, onOpen: (ViewerRequest) -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val access = LocalVaultActions.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val all = remember(entries) { entries.map { it.file } }
    LaunchedEffect(all) { selected = selected.intersect(all.toSet()) }
    SelectionEffects(selected.isNotEmpty()) { selected = emptySet() }
    var deleting by remember { mutableStateOf<List<VaultEntry>?>(null) }

    if (selected.isNotEmpty()) {
        val allSelected = selected.containsAll(all)
        SelectionTopBar(selected.size, allSelected, onClose = { selected = emptySet() }, onSelectAll = { selected = if (allSelected) emptySet() else all.toSet() })
    } else {
        VaultTopBar(onBack, onLock)
    }
    if (entries.isEmpty()) {
        EmptyState(R.drawable.ic_lock, stringResource(R.string.msg_vault_empty)) {
            Text(
                stringResource(R.string.desc_vault_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 296.dp),
            )
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = if (selected.isNotEmpty()) 96.dp else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.file }) { entry ->
                val chosen = entry.file in selected
                SelectableFrame(
                    selected.isNotEmpty(),
                    chosen,
                    6.dp,
                    Modifier.aspectRatio(1f).mediaOrigin(Vault.idOf(entry.file).toString(), 6.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selected = if (chosen) selected - entry.file else selected + entry.file
                        },
                        onClick = {
                            if (selected.isNotEmpty()) {
                                selected = if (chosen) selected - entry.file else selected + entry.file
                            } else {
                                onOpen(ViewerRequest(entries.map { Vault.idOf(it.file) }, Vault.idOf(entry.file), vault = true) {})
                            }
                        },
                    ),
                ) {
                    val thumbnail by produceState<ImageBitmap?>(null, entry.file) { value = app.vault.thumbnail(entry) }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxSize()) {
                        thumbnail?.let { Image(it, contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            val chosen = entries.filter { it.file in selected }
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                expandedShadowElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            ) {
                // Export comes with S8c (D5)
                TooltipIconButton(R.drawable.ic_drive_file_move, stringResource(R.string.action_move_out), { access?.takeOut(chosen, move = true) { selected = emptySet() } })
                TooltipIconButton(R.drawable.ic_file_copy, stringResource(R.string.action_copy_out), { access?.takeOut(chosen, move = false) { selected = emptySet() } })
                TooltipIconButton(R.drawable.ic_output, stringResource(R.string.action_export), {}, enabled = false)
                TooltipIconButton(R.drawable.ic_delete_forever, stringResource(R.string.action_delete_forever), { deleting = chosen }, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error))
            }
        }
    }
    deleting?.let { chosen ->
        // The family's question for good (SPEC §11.2); no system dialog – they are the app's own files
        ConfirmDestructiveDialog(
            R.drawable.ic_delete_forever,
            if (chosen.size == 1) stringResource(R.string.title_delete_forever_one) else stringResource(R.string.title_delete_forever, chosen.size),
            AnnotatedString(stringResource(R.string.desc_delete_forever)),
            stringResource(R.string.button_delete_forever),
            onConfirm = {
                deleting = null
                selected = emptySet()
                scope.launch { app.vault.remove(chosen) }
            },
            onDismiss = { deleting = null },
        )
    }
}
