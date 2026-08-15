package com.github.kr328.clash.core.model

class DiagnosticsAccess(
    val tunnelAuth: String,
    val controllerSecret: String,
) {
    init {
        require(tunnelAuth.isNotBlank()) { "diagnostics tunnel auth must not be blank" }
        require(controllerSecret.isNotBlank()) { "controller secret must not be blank" }
    }
}
