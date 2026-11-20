package sh.hnet.gallery.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.vault.Vault
import sh.hnet.gallery.model.VaultLockDelay
import sh.hnet.gallery.ui.components.RowIcon
import sh.hnet.gallery.ui.components.SettingsGroup
import sh.hnet.gallery.ui.components.SettingsNavigationRow
import sh.hnet.gallery.ui.components.SettingsPage
import sh.hnet.gallery.ui.components.SettingsRadioRow
import sh.hnet.gallery.ui.components.settingsRowColors
import sh.hnet.gallery.ui.vault.LocalVaultActions
import sh.hnet.gallery.ui.vault.SecureWindow

/**
 * Settings › Vault (SPEC §20.7): opens after the system's prompt (cancelled, it goes back). When the vault locks, one
 * single-choice group; the key's Forget comes with S8c (disabled, D5). Not set up or its key lost: one row to the
 * vault's page instead.
 */
@Composable
fun VaultSettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit) {
    val app = LocalContext.current.applicationContext as GalleryApplication
    val scope = rememberCoroutineScope()
    val state by app.vault.state.collectAsStateWithLifecycle()
    val delay by app.preferences.vaultLockDelay.collectAsStateWithLifecycle(VaultLockDelay.AT_ONCE)
    val access = LocalVaultActions.current
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
                SettingsGroup(stringResource(R.string.header_key)) {
                    row { shapes ->
                        // Forget the key comes with S8c (D5)
                        SegmentedListItem(
                            onClick = {},
                            enabled = false,
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { RowIcon(R.drawable.ic_key_off, error = true) },
                            supportingContent = { Text(stringResource(R.string.desc_forget_key)) },
                        ) {
                            Text(stringResource(R.string.label_forget_key), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
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
