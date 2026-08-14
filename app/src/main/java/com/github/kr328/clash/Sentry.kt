package com.github.kr328.clash

import android.content.Context
import io.sentry.android.core.SentryAndroid

internal fun initializeSentry(context: Context) {
    if (BuildConfig.SENTRY_DSN.isBlank()) return

    SentryAndroid.init(context) { options ->
        options.dsn = BuildConfig.SENTRY_DSN
        options.release = BuildConfig.SENTRY_RELEASE.ifBlank { null }
        options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        options.isSendDefaultPii = false
    }
}
