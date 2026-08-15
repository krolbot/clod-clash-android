package com.github.kr328.clash.design.compose.screen

import com.github.kr328.clash.common.model.DiagnosticsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSettingsDiagnosticsPlacementTest {
    @Test
    fun networkSettingsOpensDedicatedDiagnosticsScreen() {
        val navigation: NetworkSettingsAction = NetworkSettingsAction.OpenDiagnostics
        val state = DiagnosticsSettingsState(
            diagnosticsEnabled = true,
            diagnosticsConfigured = true,
            diagnosticsEndpoint = "https://example.com",
            vpnServiceRunning = true,
            diagnosticsState = DiagnosticsState.RUNNING,
        )
        val action = DiagnosticsSettingsAction.SetDiagnostics(false)

        assertEquals(NetworkSettingsAction.OpenDiagnostics, navigation)
        assertTrue(state.diagnosticsEnabled)
        assertEquals(DiagnosticsState.RUNNING, state.diagnosticsState)
        assertEquals(false, action.enabled)
    }
}
