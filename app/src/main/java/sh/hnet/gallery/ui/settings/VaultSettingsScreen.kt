package sh.hnet.gallery.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.VaultLockDelay
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.DestructiveButton
import sh.hnet.gallery.ui.components.SettingsDescription
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsGroupHeader
import sh.hnet.gallery.ui.components.SettingsNavigationRow
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.SettingsRadioRow
import sh.hnet.gallery.ui.vault.LocalVaultActions
import sh.hnet.gallery.ui.vault.SecureWindow

/**
 * Settings › Vault (SPEC §20.7): opens after the system's prompt (cancelled, it goes back). When the vault locks, one
 * single-choice group; Forget the key (SPEC §18.8) asks in the error colour, then deletes the Keystore key and every
 * file – the vault is new again. Not set up or its key lost: one row to the vault's page instead.
 */
@Composable
fun VaultSettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val state by app.vault.state.collectAsStateWithLifecycle()
    val delay by app.preferences.vaultLockDelay.collectAsStateWithLifecycle(VaultLockDelay.AT_ONCE)
    val access = LocalVaultActions.current
    var forgetting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (app.vault.state.value is Vault.State.Locked) access?.open(then = {}, cancelled = onBack) ?: onBack()
    }
    SettingsPage(title = stringResource(R.string.label_vault), onBack = onBack) {
        when (state) {
            Vault.State.NotSetUp -> SettingsGroup {
                row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_lock, stringResource(R.string.label_vault), stringResource(R.string.settings_vault_not_set_up), onClick = onOpenVault) }
            }
            // Its key lost: the vault's page has the card and what can be done
            is Vault.State.Lost -> SettingsGroup {
                row { shapes -> SettingsNavigationRow(shapes, R.drawable.ic_key_off, stringResource(R.string.label_vault), stringResource(R.string.button_delete_unreadable), error = true, onClick = onOpenVault) }
            }
            Vault.State.Locked -> {}
            is Vault.State.Open -> {
                SecureWindow()
                SettingsGroup(stringResource(R.string.header_lock_vault)) {
                    val choices = listOf(VaultLockDelay.AT_ONCE to R.string.lock_at_once, VaultLockDelay.ONE_MINUTE to R.string.lock_1_min, VaultLockDelay.FIVE_MINUTES to R.string.lock_5_min)
                    choices.forEach { (value, label) ->
                        row { shapes ->
                            SettingsRadioRow(shapes, stringResource(label), delay == value, onSelect = { scope.launch { app.preferences.setVaultLockDelay(value) } })
                        }
                    }
                }
                // A page's single destructive action: the family's outlined button in the error colour (owner's decision)
                SettingsGroupHeader(stringResource(R.string.header_key))
                SettingsDescription(stringResource(R.string.desc_forget_key))
                DestructiveButton(stringResource(R.string.label_forget_key), R.drawable.ic_key_off, { forgetting = true }, Modifier.padding(top = 8.dp))
            }
        }
    }
    if (forgetting) {
        ConfirmDestructiveDialog(
            R.drawable.ic_key_off,
            stringResource(R.string.title_forget_key),
            AnnotatedString(stringResource(R.string.desc_forget_key_confirm, app.vault.count)),
            stringResource(R.string.button_forget_key),
            onConfirm = {
                forgetting = false
                scope.launch { app.vault.forget() }
            },
            onDismiss = { forgetting = false },
        )
    }
}

/** The root's line for the vault (SPEC §20.1): when it locks, or that it isn't there. */
@Composable
fun vaultSettingsLine(state: Vault.State, delay: VaultLockDelay): String = when (state) {
    Vault.State.NotSetUp -> stringResource(R.string.settings_vault_not_set_up)
    is Vault.State.Lost -> stringResource(R.string.button_delete_unreadable)
    else -> stringResource(
        when (delay) {
            VaultLockDelay.AT_ONCE -> R.string.settings_vault_at_once
            VaultLockDelay.ONE_MINUTE -> R.string.settings_vault_1_min
            VaultLockDelay.FIVE_MINUTES -> R.string.settings_vault_5_min
        },
    )
}
