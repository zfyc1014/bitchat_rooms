package com.bitchat.android.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide lifecycle controller for the LAN TCP/IP transport.
 *
 * Mirrors [com.bitchat.android.wifiaware.WifiAwareController] but with none of the Wi-Fi Aware
 * support/availability machinery — TCP works on any device with a network connection and the
 * already-declared INTERNET/network permissions.
 */
object TcpController {
    private const val TAG = "TcpController"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var service: TcpMeshService? = null

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context, enabledByDefault: Boolean = false) {
        appContext = context.applicationContext
        _enabled.value = enabledByDefault
        if (enabledByDefault) {
            startIfPossible()
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (value) {
            startIfPossible()
        } else {
            stop()
        }
    }

    fun startIfPossible() {
        if (!_enabled.value) {
            Log.i(TAG, "TCP transport disabled; not starting")
            return
        }
        val ctx = appContext ?: return
        synchronized(this) {
            if (_running.value) return
            val svc = TcpMeshService(ctx)
            service = svc
            try {
                svc.startServices()
                _running.value = true
                Log.i(TAG, "TCP transport started")
            } catch (e: Exception) {
                service = null
                Log.e(TAG, "Failed to start TCP transport: ${e.message}", e)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            val svc = service ?: return
            service = null
            try {
                svc.stopServices()
            } catch (_: Exception) { }
            _running.value = false
        }
    }

    fun onServiceStopped(stopped: TcpMeshService) {
        if (service === stopped) {
            _running.value = false
        }
    }

    fun getService(): TcpMeshService? = service
}
