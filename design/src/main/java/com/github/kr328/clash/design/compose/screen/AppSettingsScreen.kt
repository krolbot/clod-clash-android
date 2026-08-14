package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SelectRow
import com.github.kr328.clash.design.compose.component.SwitchRow
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint

/**
 * @param darkMode индекс в `DarkMode.values()`: экран не знает про сам enum,
 *   чтобы не тащить в разметку модель настроек.
 * @param notificationEditable уведомление со скоростью нельзя переключать
 *   на ходу — оно собирается при запуске службы.
 */
@Immutable
data class AppSettingsState(
    val autoRestart: Boolean = false,
    val darkMode: Int = 0,
    val hideAppIcon: Boolean = false,
    val hideFromRecents: Boolean = false,
    val dynamicNotification: Boolean = false,
    val notificationEditable: Boolean = true,
    val enableHwid: Boolean = true,
    val subNotifications: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val diagnosticsAvailable: Boolean = false,
    val diagnosticsConfigured: Boolean = false,
    val diagnosticsEndpoint: String = "",
    val vpnServiceRunning: Boolean = false,
    val diagnosticsState: DiagnosticsState = DiagnosticsState.STOPPED,
)

sealed interface AppSettingsAction {
    data object Back : AppSettingsAction
    data class SetAutoRestart(val enabled: Boolean) : AppSettingsAction
    data class SetDarkMode(val index: Int) : AppSettingsAction
    data class SetHideAppIcon(val enabled: Boolean) : AppSettingsAction
    data class SetHideFromRecents(val enabled: Boolean) : AppSettingsAction
    data class SetDynamicNotification(val enabled: Boolean) : AppSettingsAction
    data class SetEnableHwid(val enabled: Boolean) : AppSettingsAction
    data class SetSubNotifications(val enabled: Boolean) : AppSettingsAction
    data class SetDiagnostics(val enabled: Boolean) : AppSettingsAction
    data class SaveDiagnosticsCredential(
        val endpoint: String,
        val username: String,
        val password: String,
    ) : AppSettingsAction
    data object ClearDiagnosticsCredential : AppSettingsAction
}

/**
 * Настройки приложения.
 *
 * Первый экран настроек на Compose. Строит его тот же набор строк, что и
 * остальные экраны, — вместо самодельного DSL `preferenceScreen`, который
 * собирал вьюхи в рантайме и держал ссылки на свойства хранилищ.
 */
@Composable
fun AppSettingsScreen(
    state: AppSettingsState,
    onAction: (AppSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var credentialDialog by remember { mutableStateOf(false) }
    var endpoint by remember(state.diagnosticsEndpoint) { mutableStateOf(state.diagnosticsEndpoint) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    fun closeCredentialDialog() {
        username = ""
        password = ""
        endpoint = state.diagnosticsEndpoint
        credentialDialog = false
    }

    ActivityScaffold(
        title = stringResource(R.string.app),
        onBack = { onAction(AppSettingsAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.behavior))
            SwitchRow(
                title = stringResource(R.string.auto_restart),
                subtitle = stringResource(R.string.allow_clash_auto_restart),
                icon = painterResource(R.drawable.ic_baseline_restore),
                checked = state.autoRestart,
                onCheckedChange = { onAction(AppSettingsAction.SetAutoRestart(it)) },
            )

            SectionHeader(stringResource(R.string.interface_))
            SelectRow(
                title = stringResource(R.string.dark_mode),
                icon = painterResource(R.drawable.ic_baseline_brightness_4),
                options = listOf(
                    stringResource(R.string.follow_system_android_10),
                    stringResource(R.string.always_light),
                    stringResource(R.string.always_dark),
                ),
                selectedIndex = state.darkMode,
                onSelect = { onAction(AppSettingsAction.SetDarkMode(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.hide_app_icon_title),
                subtitle = stringResource(R.string.hide_app_icon_desc),
                icon = painterResource(R.drawable.ic_baseline_hide),
                checked = state.hideAppIcon,
                onCheckedChange = { onAction(AppSettingsAction.SetHideAppIcon(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.hide_from_recents_title),
                subtitle = stringResource(R.string.hide_from_recents_desc),
                icon = painterResource(R.drawable.ic_baseline_stack),
                checked = state.hideFromRecents,
                onCheckedChange = { onAction(AppSettingsAction.SetHideFromRecents(it)) },
            )

            SectionHeader(stringResource(R.string.service))
            SwitchRow(
                title = stringResource(R.string.show_traffic),
                subtitle = if (state.notificationEditable) {
                    stringResource(R.string.show_traffic_summary)
                } else {
                    // Молча погашенная строка выглядит как поломка: объясняем,
                    // почему её нельзя тронуть прямо сейчас.
                    stringResource(R.string.clod_setting_needs_stop)
                },
                icon = painterResource(R.drawable.ic_baseline_domain),
                checked = state.dynamicNotification,
                enabled = state.notificationEditable,
                onCheckedChange = { onAction(AppSettingsAction.SetDynamicNotification(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.diagnostics_tunnel_title),
                subtitle = when {
                    !state.diagnosticsAvailable -> stringResource(R.string.diagnostics_tunnel_unavailable)
                    !state.diagnosticsConfigured || state.diagnosticsEndpoint.isBlank() ->
                        stringResource(R.string.diagnostics_tunnel_needs_credential)
                    !state.vpnServiceRunning -> stringResource(R.string.diagnostics_tunnel_service_stopped)
                    state.diagnosticsEnabled && state.diagnosticsState == DiagnosticsState.CONNECTED ->
                        stringResource(R.string.diagnostics_tunnel_connected)
                    state.diagnosticsEnabled -> stringResource(R.string.diagnostics_tunnel_connecting)
                    else -> stringResource(R.string.diagnostics_tunnel_ready)
                },
                icon = painterResource(R.drawable.ic_baseline_adb),
                checked = state.diagnosticsEnabled,
                enabled = state.diagnosticsEnabled || (
                    state.diagnosticsAvailable &&
                        state.diagnosticsConfigured &&
                        state.diagnosticsEndpoint.isNotBlank() &&
                        state.vpnServiceRunning
                ),
                onCheckedChange = { onAction(AppSettingsAction.SetDiagnostics(it)) },
            )
            Button(
                onClick = {
                    endpoint = state.diagnosticsEndpoint
                    credentialDialog = true
                },
                enabled = state.diagnosticsAvailable,
            ) {
                Text(
                    stringResource(
                        if (state.diagnosticsConfigured) {
                            R.string.diagnostics_credential_replace
                        } else {
                            R.string.diagnostics_credential_setup
                        },
                    ),
                )
            }

            SectionHeader(stringResource(R.string.clod_tab_subscriptions))
            SwitchRow(
                title = stringResource(R.string.clod_hwid_title),
                subtitle = stringResource(R.string.clod_hwid_summary),
                icon = painterResource(R.drawable.ic_baseline_key),
                checked = state.enableHwid,
                onCheckedChange = { onAction(AppSettingsAction.SetEnableHwid(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.clod_sub_notify_title),
                subtitle = stringResource(R.string.clod_sub_notify_summary),
                icon = painterResource(R.drawable.ic_baseline_notifications),
                checked = state.subNotifications,
                onCheckedChange = { onAction(AppSettingsAction.SetSubNotifications(it)) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (credentialDialog) {
        AlertDialog(
            onDismissRequest = ::closeCredentialDialog,
            title = { Text(stringResource(R.string.diagnostics_credential_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(stringResource(R.string.diagnostics_endpoint)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.diagnostics_username)) },
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.diagnostics_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (state.diagnosticsConfigured) {
                        TextButton(onClick = {
                            onAction(AppSettingsAction.ClearDiagnosticsCredential)
                            closeCredentialDialog()
                        }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = normalizeDiagnosticsEndpoint(endpoint) != null && (
                        (state.diagnosticsConfigured && username.isBlank() && password.isBlank()) ||
                            (username.isNotBlank() && ':' !in username && password.isNotBlank())
                    ),
                    onClick = {
                        onAction(
                            AppSettingsAction.SaveDiagnosticsCredential(
                                endpoint = endpoint,
                                username = username,
                                password = password,
                            ),
                        )
                        closeCredentialDialog()
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Button(onClick = ::closeCredentialDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
