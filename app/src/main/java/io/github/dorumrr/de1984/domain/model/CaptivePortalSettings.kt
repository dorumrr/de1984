package io.github.dorumrr.de1984.domain.model

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
enum class CaptivePortalMode(val value: Int, val displayName: String, val description: String) {
    FORCED_OFF(-1, "Forced Off", "Captive portal detection is completely disabled"),
    DISABLED(0, "Disabled", "Captive portal detection is disabled"),
    ENABLED(1, "Enabled", "Captive portal detection is enabled (default)");

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
    val displayName: String,
    val httpUrl: String,
    val httpsUrl: String,
    val description: String
) {
    GOOGLE(
        displayName = "Google (Default)",
        httpUrl = "http://connectivitycheck.gstatic.com/generate_204",
        httpsUrl = "https://www.google.com/generate_204",
        description = "Google's default captive portal detection servers"
    ),
    GRAPHENEOS(
        displayName = "GrapheneOS",
        httpUrl = "http://connectivitycheck.grapheneos.network/generate_204",
        httpsUrl = "https://connectivitycheck.grapheneos.network/generate_204",
        description = "Privacy-focused servers from GrapheneOS project"
    ),
    KUKETZ(
        displayName = "Kuketz",
        httpUrl = "http://captiveportal.kuketz.de",
        httpsUrl = "https://captiveportal.kuketz.de",
        description = "Privacy-focused servers from Mike Kuketz (Germany)"
    ),
    CLOUDFLARE(
        displayName = "Cloudflare",
        httpUrl = "http://cp.cloudflare.com",
        httpsUrl = "https://cp.cloudflare.com",
        description = "Cloudflare's captive portal detection servers"
    ),
    CUSTOM(
        displayName = "Custom URLs",
        httpUrl = "",
        httpsUrl = "",
        description = "Use your own custom captive portal URLs"
    );

    companion object {
        fun fromDisplayName(name: String): CaptivePortalPreset {
            return values().find { it.displayName == name } ?: GOOGLE
        }
    }
}

