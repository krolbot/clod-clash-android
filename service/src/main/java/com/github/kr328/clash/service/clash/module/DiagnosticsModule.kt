package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.sendDiagnosticsStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Runs only while the already-started Clash service owns the core. */
class DiagnosticsModule(service: Service) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private val credentials = DiagnosticsCredentialStore(service)

    override suspend fun run() {
        val changes = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_DIAGNOSTICS_CHANGED)
        }

        try {
            applySetting(credentials.read())
            while (currentCoroutineContext().isActive) {
                val intent = changes.tryReceive().getOrNull()
                if (intent != null) {
                    applySetting(intent.getStringExtra(Intents.EXTRA_DIAGNOSTICS_AUTH))
                }
                publishStatus()
                delay(1_000)
            }
        } finally {
            withContext(NonCancellable) {
                Clash.stopDiagnostics()
                service.sendDiagnosticsStatus(DiagnosticsState.STOPPED)
            }
        }
    }

    private fun applySetting(auth: String?) {
        val status = Clash.queryDiagnostics()
        val endpoint = normalizeDiagnosticsEndpoint(store.diagnosticsEndpoint)
        if (store.diagnosticsEnabled && status.available && endpoint != null && auth != null) {
            Clash.startDiagnostics(endpoint, auth)
        } else {
            Clash.stopDiagnostics()
        }
    }

    private fun publishStatus() {
        val status = Clash.queryDiagnostics()
        val state = when {
            !store.diagnosticsEnabled -> DiagnosticsState.STOPPED
            status.running -> DiagnosticsState.CONNECTED
            else -> DiagnosticsState.CONNECTING
        }
        service.sendDiagnosticsStatus(state)
    }
}
