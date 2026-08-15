package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed persistence for user-added (third-party) relay URLs.
 *
 * These relays are appended to the built-in relay list and receive all Nostr
 * traffic (DMs, geohash channels, presence), so a self-hosted bitChat relay can
 * replicate channel delivery on a private server.
 */
object CustomRelayStore {
    private const val PREFS_NAME = "bitchat_custom_relays"
    private const val KEY_URLS = "custom_relay_urls"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ready(): Boolean = ::prefs.isInitialized

    fun getAll(): List<String> =
        if (ready()) prefs.getStringSet(KEY_URLS, emptySet())?.toList().orEmpty()
            .sorted()
        else emptyList()

    fun add(url: String) {
        if (!ready()) return
        val normalized = url.trim()
        if (normalized.isEmpty()) return
        val updated = getAll().toMutableSet().apply { add(normalized) }
        prefs.edit().putStringSet(KEY_URLS, updated).apply()
    }

    fun remove(url: String) {
        if (!ready()) return
        val updated = getAll().toMutableSet().apply { remove(url) }
        prefs.edit().putStringSet(KEY_URLS, updated).apply()
    }
}
