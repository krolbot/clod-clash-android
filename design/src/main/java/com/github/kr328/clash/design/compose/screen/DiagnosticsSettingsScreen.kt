package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SwitchRow
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint

@Immutable
data class DiagnosticsSettingsState(
    val diagnosticsEnabled: Boolean = false,
    val diagnosticsConfigured: Boolean = false,
    val diagnosticsEndpoint: String = "",
    val vpnServiceRunning: Boolean = false,
    val diagnosticsState: DiagnosticsState = DiagnosticsState.STOPPED,
)

sealed interface DiagnosticsSettingsAction {
    data object Back : DiagnosticsSettingsAction
    data class SetDiagnostics(val enabled: Boolean) : DiagnosticsSettingsAction
    data class SaveDiagnosticsCredential(
        val endpoint: String,
        val username: String,
        val password: String,
        val controllerSecret: String,
        val remotePort: Int,
    ) : DiagnosticsSettingsAction
    data object ClearDiagnosticsCredential : DiagnosticsSettingsAction
}

@Composable
fun DiagnosticsSettingsScreen(
    state: DiagnosticsSettingsState,
    onAction: (DiagnosticsSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var endpoint by remember(state.diagnosticsEndpoint) { mutableStateOf(state.diagnosticsEndpoint) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var controllerSecret by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }
    var diagnosticsWarning by remember { mutableStateOf(false) }

    fun clearCredentialInputs() {
        username = ""
        password = ""
        controllerSecret = ""
        remotePort = ""
    }

    ActivityScaffold(
        title = stringResource(R.string.diagnostics_credential_title),
        onBack = { onAction(DiagnosticsSettingsAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SwitchRow(
                title = stringResource(R.string.diagnostics_tunnel_title),
                subtitle = when {
                    !state.diagnosticsConfigured || state.diagnosticsEndpoint.isBlank() ->
                        stringResource(R.string.diagnostics_tunnel_needs_credential)
                    !state.vpnServiceRunning -> stringResource(R.string.diagnostics_tunnel_service_stopped)
                    state.diagnosticsEnabled && state.diagnosticsState == DiagnosticsState.ERROR ->
                        stringResource(R.string.diagnostics_tunnel_error)
                    state.diagnosticsEnabled && state.diagnosticsState == DiagnosticsState.RUNNING ->
                        stringResource(R.string.diagnostics_tunnel_running)
                    state.diagnosticsEnabled -> stringResource(R.string.diagnostics_tunnel_connecting)
                    else -> stringResource(R.string.diagnostics_tunnel_ready)
                },
                icon = painterResource(R.drawable.ic_baseline_adb),
                checked = state.diagnosticsEnabled,
                enabled = state.diagnosticsEnabled || (
                    state.diagnosticsConfigured &&
                        state.diagnosticsEndpoint.isNotBlank() &&
                        state.vpnServiceRunning
                ),
                onCheckedChange = { enabled ->
                    if (enabled) diagnosticsWarning = true
                    else onAction(DiagnosticsSettingsAction.SetDiagnostics(false))
                },
            )

            SectionHeader(
                stringResource(
                    if (state.diagnosticsConfigured) {
                        R.string.diagnostics_credential_replace
                    } else {
                        R.string.diagnostics_credential_setup
                    },
                ),
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.diagnostics_endpoint)) },
                enabled = !state.vpnServiceRunning,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.diagnostics_username)) },
                enabled = !state.vpnServiceRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.diagnostics_password)) },
                enabled = !state.vpnServiceRunning,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = controllerSecret,
                onValueChange = { controllerSecret = it },
                label = { Text(stringResource(R.string.diagnostics_controller_secret)) },
                enabled = !state.vpnServiceRunning,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = remotePort,
                onValueChange = { remotePort = it },
                label = { Text(stringResource(R.string.diagnostics_remote_port)) },
                enabled = !state.vpnServiceRunning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
            if (state.diagnosticsConfigured) {
                TextButton(
                    enabled = !state.vpnServiceRunning,
                    onClick = {
                        onAction(DiagnosticsSettingsAction.ClearDiagnosticsCredential)
                        clearCredentialInputs()
                    },
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
            Button(
                enabled = !state.vpnServiceRunning &&
                    normalizeDiagnosticsEndpoint(endpoint) != null && run {
                        val allCredentialFieldsBlank = username.isBlank() &&
                            password.isBlank() &&
                            controllerSecret.isBlank() &&
                            remotePort.isBlank()
                        (state.diagnosticsConfigured && allCredentialFieldsBlank) ||
                            DiagnosticsCredential.create(
                                username,
                                password,
                                controllerSecret,
                                remotePort.toIntOrNull() ?: -1,
                            ) != null
                    },
                onClick = {
                    onAction(
                        DiagnosticsSettingsAction.SaveDiagnosticsCredential(
                            endpoint = endpoint,
                            username = username,
                            password = password,
                            controllerSecret = controllerSecret,
                            remotePort = remotePort.toIntOrNull() ?: -1,
                        ),
                    )
                    clearCredentialInputs()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.save))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (diagnosticsWarning) {
        AlertDialog(
            onDismissRequest = { diagnosticsWarning = false },
            title = { Text(stringResource(R.string.diagnostics_access_warning_title)) },
            text = { Text(stringResource(R.string.diagnostics_access_warning)) },
            confirmButton = {
                Button(onClick = {
                    diagnosticsWarning = false
                    onAction(DiagnosticsSettingsAction.SetDiagnostics(true))
                }) {
                    Text(stringResource(R.string.diagnostics_access_enable))
                }
            },
            dismissButton = {
                Button(onClick = { diagnosticsWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
