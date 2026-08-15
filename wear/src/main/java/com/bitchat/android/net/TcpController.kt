package com.bitchat.android.net

/**
 * Wear shim for the phone's TcpController.
 *
 * Referenced only by the shared `DebugSettingsManager` debug-UI toggle. LAN TCP transport is out
 * of scope for the watch (Bluetooth mesh only), so this is a no-op.
 */
object TcpController {
    fun setEnabled(value: Boolean) = Unit
}
