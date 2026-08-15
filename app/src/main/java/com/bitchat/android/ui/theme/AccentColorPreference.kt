package com.bitchat.android.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App accent (theme) color. The brand default is green; the other entries are
 * Material-3-friendly alternates. Each enum entry carries the full primary ramp
 * for both light and dark schemes so [Theme.kt] can build a coherent scheme
 * without extra lookups.
 */
enum class AccentColor(
    val darkPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val lightPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
) {
    Green(
        darkPrimary = Color(0xFF32D74B),
        darkPrimaryContainer = Color(0xFF163D1D),
        darkOnPrimaryContainer = Color(0xFFB8F5C1),
        lightPrimary = Color(0xFF248A3D),
        lightPrimaryContainer = Color(0xFFD5F1D8),
        lightOnPrimaryContainer = Color(0xFF0A3212),
    ),
    Blue(
        darkPrimary = Color(0xFF0A84FF),
        darkPrimaryContainer = Color(0xFF082E54),
        darkOnPrimaryContainer = Color(0xFFC2E0FF),
        lightPrimary = Color(0xFF007AFF),
        lightPrimaryContainer = Color(0xFFD6E9FF),
        lightOnPrimaryContainer = Color(0xFF002C5C),
    ),
    Orange(
        darkPrimary = Color(0xFFFF9F0A),
        darkPrimaryContainer = Color(0xFF3D2A05),
        darkOnPrimaryContainer = Color(0xFFFFD9A0),
        lightPrimary = Color(0xFFFF9500),
        lightPrimaryContainer = Color(0xFFFFE4C2),
        lightOnPrimaryContainer = Color(0xFF3D2600),
    ),
    Purple(
        darkPrimary = Color(0xFFBF5AF2),
        darkPrimaryContainer = Color(0xFF2E1450),
        darkOnPrimaryContainer = Color(0xFFE2C6FF),
        lightPrimary = Color(0xFFAF52DE),
        lightPrimaryContainer = Color(0xFFF0D9FF),
        lightOnPrimaryContainer = Color(0xFF330C55),
    ),
    Pink(
        darkPrimary = Color(0xFFFF375F),
        darkPrimaryContainer = Color(0xFF3D1020),
        darkOnPrimaryContainer = Color(0xFFFFC2D0),
        lightPrimary = Color(0xFFFF2D55),
        lightPrimaryContainer = Color(0xFFFFD6DE),
        lightOnPrimaryContainer = Color(0xFF3D0A1A),
    ),
    Teal(
        darkPrimary = Color(0xFF64D2FF),
        darkPrimaryContainer = Color(0xFF0A3D4D),
        darkOnPrimaryContainer = Color(0xFFC2F0FF),
        lightPrimary = Color(0xFF00A8D6),
        lightPrimaryContainer = Color(0xFFD6F5FF),
        lightOnPrimaryContainer = Color(0xFF00344D),
    ),
}

/**
 * Simple SharedPreferences-backed manager for the accent color preference.
 * Mirrors [ThemePreferenceManager] so both flows are collected in [Theme.kt].
 */
object AccentColorPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_ACCENT = "accent_color_preference"

    private val _accentFlow = MutableStateFlow(AccentColor.Green)
    val accentFlow: StateFlow<AccentColor> = _accentFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ACCENT, AccentColor.Green.name)
        _accentFlow.value = runCatching { AccentColor.valueOf(saved!!) }.getOrDefault(AccentColor.Green)
    }

    fun set(context: Context, accent: AccentColor) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACCENT, accent.name).apply()
        _accentFlow.value = accent
    }
}
