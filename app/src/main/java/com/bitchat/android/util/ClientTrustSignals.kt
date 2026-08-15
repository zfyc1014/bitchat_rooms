package com.bitchat.android.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * Collects device-trust signals that the self-hosted relay uses to decide
 * whether a connecting client is a real device, an emulator, or a bot.
 *
 * The raw Android build fields are sent verbatim so the server can re-derive
 * emulator/VM markers instead of trusting the client's own [Report.isEmulator].
 */
object ClientTrustSignals {

    /** Wire schema version; bump when the signal set changes incompatibly. */
    const val VERSION = 1

    /** One-shot report emitted after every relay WebSocket connect. */
    data class Report(
        val version: Int,
        val fingerprint: String,
        val hardware: String,
        val model: String,
        val manufacturer: String,
        val brand: String,
        val device: String,
        val product: String,
        val board: String,
        val bootloader: String,
        val tags: String,
        val isEmulator: Boolean,
        val isRooted: Boolean,
        val isDebuggable: Boolean,
        val emulatorHints: List<String>
    )

    fun gather(context: Context): Report {
        val emulator = detectEmulator()
        return Report(
            version = VERSION,
            fingerprint = Build.FINGERPRINT.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            board = Build.BOARD.orEmpty(),
            bootloader = Build.BOOTLOADER.orEmpty(),
            tags = Build.TAGS.orEmpty(),
            isEmulator = emulator.first,
            isRooted = detectRoot(),
            isDebuggable = detectDebuggable(context),
            emulatorHints = emulator.second
        )
    }

    // --- detection helpers ---

    private fun detectEmulator(): Pair<Boolean, List<String>> {
        val hints = mutableListOf<String>()
        fun containsAny(value: String, needles: List<String>): Boolean {
            val v = value.lowercase(Locale.ROOT).trim()
            return needles.any { v.contains(it) }
        }

        if (containsAny(Build.HARDWARE.orEmpty(), listOf("goldfish", "ranchu", "vbox86", "qemu", "ttvm"))) {
            hints += "emulator hardware: ${Build.HARDWARE}"
        }
        if (containsAny(Build.FINGERPRINT.orEmpty(), listOf("generic", "unknown", "emulator", "vbox", "android_x86", "google_sdk"))) {
            hints += "generic/emulator fingerprint"
        }
        if (containsAny(Build.MODEL.orEmpty(), listOf("emulator", "android sdk", "google_sdk", "droid4x", "bluestacks", "nox", "vbox", "virtual"))) {
            hints += "emulator model: ${Build.MODEL}"
        }
        if (containsAny(Build.MANUFACTURER.orEmpty(), listOf("genymotion", "virtual", "bluestacks", "nox"))) {
            hints += "emulator manufacturer: ${Build.MANUFACTURER}"
        }
        if (containsAny(Build.PRODUCT.orEmpty(), listOf("sdk", "emulator", "simulator", "vbox", "nox"))) {
            hints += "emulator product: ${Build.PRODUCT}"
        }
        if (containsAny(Build.BRAND.orEmpty(), listOf("generic"))) {
            hints += "generic brand"
        }
        if (containsAny(Build.DEVICE.orEmpty(), listOf("generic", "emulator", "vbox", "nox"))) {
            hints += "emulator device: ${Build.DEVICE}"
        }
        if (containsAny(Build.BOARD.orEmpty(), listOf("goldfish", "ranchu", "vbox"))) {
            hints += "emulator board: ${Build.BOARD}"
        }
        if (containsAny(Build.BOOTLOADER.orEmpty(), listOf("goldfish", "unknown"))) {
            hints += "emulator bootloader: ${Build.BOOTLOADER}"
        }
        if (getSystemProperty("ro.kernel.qemu") == "1") {
            hints += "ro.kernel.qemu=1"
        }

        val unique = hints.distinct()
        return unique.isNotEmpty() to unique
    }

    private fun detectRoot(): Boolean {
        if (Build.TAGS.orEmpty().contains("test-keys")) return true
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk"
        )
        return suPaths.any { path -> runCatching { File(path).exists() }.getOrDefault(false) }
    }

    private fun detectDebuggable(context: Context): Boolean =
        runCatching {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)

    private fun getSystemProperty(name: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, name) as? String
    }.getOrNull()
}
