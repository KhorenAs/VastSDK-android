package com.kinodaran.vast.kit

import android.util.Log

/**
 * The SDK's own log lines, under one tag so a host can filter them.
 *
 * Wrapped rather than calling `Log` everywhere because `android.util.Log` is a
 * stub in JVM unit tests and throws there; a failed log line must never fail
 * the delivery or playback code that wrote it.
 */
internal object VastLog {
    const val TAG = "VastSDK"

    fun warning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    fun notice(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
