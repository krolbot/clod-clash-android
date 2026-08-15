package com.github.kr328.clash

import com.github.kr328.clash.design.DiagnosticsSettingsDesign
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class DiagnosticsSettingsActivity : BaseActivity<DiagnosticsSettingsDesign>() {
    override suspend fun main() {
        val design = DiagnosticsSettingsDesign(
            this,
            ServiceStore(this),
            clashRunning && uiStore.enableVpn,
            Remote.broadcasts.diagnosticsState,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        Event.DiagnosticsStatusChanged ->
                            design.updateDiagnosticsStatus(Remote.broadcasts.diagnosticsState)
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        DiagnosticsSettingsDesign.Request.Back -> finish()
                    }
                }
            }
        }
    }
}
