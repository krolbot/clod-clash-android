package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsAction
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsScreen
import com.github.kr328.clash.design.compose.screen.DiagnosticsSettingsState
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.sendDiagnosticsChanged

class DiagnosticsSettingsDesign(
    context: Context,
    private val srvStore: ServiceStore,
    vpnServiceRunning: Boolean,
    diagnosticsState: DiagnosticsState,
) : Design<DiagnosticsSettingsDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
    }

    private val credentials = DiagnosticsCredentialStore(context)

    private var state by mutableStateOf(
        DiagnosticsSettingsState(
            diagnosticsEnabled = diagnosticsState != DiagnosticsState.STOPPED,
            diagnosticsConfigured = credentials.read() != null,
            diagnosticsEndpoint = normalizeDiagnosticsEndpoint(srvStore.diagnosticsEndpoint).orEmpty(),
            vpnServiceRunning = vpnServiceRunning,
            diagnosticsState = diagnosticsState,
        ),
    )

    override val root: View = composeRoot {
        DiagnosticsSettingsScreen(state = state, onAction = ::onAction)
    }

    private fun onAction(action: DiagnosticsSettingsAction) {
        when (action) {
            DiagnosticsSettingsAction.Back -> requests.trySend(Request.Back)
            is DiagnosticsSettingsAction.SetDiagnostics -> {
                if (
                    action.enabled &&
                    (
                        !state.diagnosticsConfigured ||
                            state.diagnosticsEndpoint.isBlank() ||
                            !state.vpnServiceRunning
                    )
                ) return

                if (action.enabled && credentials.read() == null) return
                state = state.copy(diagnosticsEnabled = action.enabled)
                context.sendDiagnosticsChanged(action.enabled)
            }
            is DiagnosticsSettingsAction.SaveDiagnosticsCredential -> {
                if (state.vpnServiceRunning) return
                val endpoint = normalizeDiagnosticsEndpoint(action.endpoint) ?: return
                val replacesCredentials = action.username.isNotBlank() ||
                    action.password.isNotBlank() ||
                    action.controllerSecret.isNotBlank() ||
                    action.remotePort >= 0
                if (!replacesCredentials && !state.diagnosticsConfigured) return

                val replacement = if (replacesCredentials) {
                    DiagnosticsCredential.create(
                        action.username,
                        action.password,
                        action.controllerSecret,
                        action.remotePort,
                    ) ?: return
                } else {
                    null
                }

                val saved = replacement == null || credentials.save(replacement)
                if (!saved) {
                    state = state.copy(diagnosticsEnabled = false)
                    context.sendDiagnosticsChanged(false)
                    return
                }

                srvStore.diagnosticsEndpoint = endpoint
                state = state.copy(
                    diagnosticsEnabled = false,
                    diagnosticsConfigured = credentials.read() != null,
                    diagnosticsEndpoint = endpoint,
                )
                context.sendDiagnosticsChanged(false)
            }
            DiagnosticsSettingsAction.ClearDiagnosticsCredential -> {
                if (state.vpnServiceRunning) return
                credentials.clear()
                state = state.copy(diagnosticsEnabled = false, diagnosticsConfigured = false)
                context.sendDiagnosticsChanged(false)
            }
        }
    }

    fun updateDiagnosticsStatus(status: DiagnosticsState) {
        state = state.copy(
            diagnosticsEnabled = status != DiagnosticsState.STOPPED,
            diagnosticsState = status,
        )
    }
}
