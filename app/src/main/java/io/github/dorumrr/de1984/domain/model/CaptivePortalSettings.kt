package io.github.dorumrr.de1984.domain.model

import android.content.Context

/**
 * Represents the current captive portal configuration on the device.
 */
data class CaptivePortalSettings(
    val mode: CaptivePortalMode,
    val httpUrl: String?,
    val httpsUrl: String?,
    val fallbackUrl: String?,
    val otherFallbackUrls: String?,
    val useHttps: Boolean
) {
    /**
     * Check if settings match a known preset.
     */
    fun matchesPreset(preset: CaptivePortalPreset): Boolean {
        if (preset == CaptivePortalPreset.CUSTOM) return false
        return httpUrl == preset.httpUrl && httpsUrl == preset.httpsUrl
    }

    /**
     * Get the preset that matches these settings, or CUSTOM if no match.
     */
    fun getMatchingPreset(): CaptivePortalPreset {
        return CaptivePortalPreset.values().find { it != CaptivePortalPreset.CUSTOM && matchesPreset(it) }
            ?: CaptivePortalPreset.CUSTOM
    }
}

/**
 * Captive portal detection mode (API 26+).
 */
enum class CaptivePortalMode(val value: Int) {
    FORCED_OFF(-1),
    DISABLED(0),
    ENABLED(1);

    fun getDisplayName(context: Context): String {
        return when (this) {
            FORCED_OFF -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_forced_off)
            DISABLED -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_disabled)
            ENABLED -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_enabled)
        }
    }

    fun getDescription(context: Context): String {
        return when (this) {
            FORCED_OFF -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_forced_off_description)
            DISABLED -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_disabled_description)
            ENABLED -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_enabled_description)
        }
    }

    companion object {
        fun fromValue(value: Int): CaptivePortalMode {
            return values().find { it.value == value } ?: ENABLED
        }

        fun fromString(str: String?): CaptivePortalMode {
            return when (str) {
                "-1", "FORCED_OFF" -> FORCED_OFF
                "0", "DISABLED" -> DISABLED
                "1", "ENABLED" -> ENABLED
                else -> ENABLED
            }
        }
    }

    fun toStorageString(): String = value.toString()
}

/**
 * Predefined captive portal server presets.
 */
enum class CaptivePortalPreset(
    val httpUrl: String,
    val httpsUrl: String
) {
    GOOGLE(
        httpUrl = "http://connectivitycheck.gstatic.com/generate_204",
        httpsUrl = "https://www.google.com/generate_204"
    ),
    GRAPHENEOS(
        httpUrl = "http://connectivitycheck.grapheneos.network/generate_204",
        httpsUrl = "https://connectivitycheck.grapheneos.network/generate_204"
    ),
    KUKETZ(
        httpUrl = "http://captiveportal.kuketz.de",
        httpsUrl = "https://captiveportal.kuketz.de"
    ),
    CLOUDFLARE(
        httpUrl = "http://cp.cloudflare.com",
        httpsUrl = "https://cp.cloudflare.com"
    ),
    CUSTOM(
        httpUrl = "",
        httpsUrl = ""
    );

    fun getDisplayName(context: Context): String {
        return when (this) {
            GOOGLE -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_google)
            GRAPHENEOS -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_grapheneos)
            KUKETZ -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_kuketz)
            CLOUDFLARE -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_cloudflare)
            CUSTOM -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_custom)
        }
    }

    fun getDescription(context: Context): String {
        return when (this) {
            GOOGLE -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_google_description)
            GRAPHENEOS -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_grapheneos_description)
            KUKETZ -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_kuketz_description)
            CLOUDFLARE -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_cloudflare_description)
            CUSTOM -> context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_custom_description)
        }
    }

    companion object {
        fun fromDisplayName(context: Context, name: String): CaptivePortalPreset {
            return values().find { it.getDisplayName(context) == name } ?: GOOGLE
        }
    }
}

