package io.github.dorumrr.de1984.utils

object Constants {

    object UI {
        // Bottom Navigation (actually used in MainActivity.kt)
        const val BOTTOM_NAV_ICON_SIZE_ENLARGED = 27.5f
        const val BOTTOM_NAV_PADDING_TOP = 12
        const val BOTTOM_NAV_PADDING_BOTTOM = 3
        const val BOTTOM_NAV_TEXT_TRANSLATION_Y = -2

        object Dialogs {
            const val FIREWALL_START_TITLE = "Start De1984 Firewall?"
            const val FIREWALL_START_MESSAGE = "Welcome to De1984! Would you like to start the firewall now to protect your privacy?"
            const val FIREWALL_START_CONFIRM = "Start Firewall"
            const val FIREWALL_START_SKIP = "Later"

            const val POLICY_CHANGE_TITLE = "Change Default Policy?"
            const val POLICY_CHANGE_CONFIRM = "Change Policy"
            const val POLICY_CHANGE_CANCEL = "Cancel"

            const val VPN_WARNING_EMOJI = "⚠️"
            const val VPN_WARNING_TITLE = "WARNING: A VPN is currently active!"
            const val VPN_WARNING_MESSAGE = "Please allow your VPN app in the firewall to avoid connectivity issues."
        }
    }
    
    object App {
        const val NAME = "De1984"
        const val PACKAGE_NAME = "io.github.dorumrr.de1984"
        const val PACKAGE_NAME_DEBUG = "io.github.dorumrr.de1984.debug"

        fun isOwnApp(packageName: String): Boolean {
            return packageName == PACKAGE_NAME || packageName == PACKAGE_NAME_DEBUG
        }
    }
    
    object Packages {
        const val TYPE_SYSTEM = "system"
        const val TYPE_USER = "user"

        const val STATE_ENABLED = "Enabled"
        const val STATE_DISABLED = "Disabled"
        
        val PACKAGE_TYPE_FILTERS = listOf("User", "System")
        val PACKAGE_STATE_FILTERS = listOf("Enabled", "Disabled")
        
        const val ANDROID_PACKAGE_PREFIX = "com.android"
        const val GOOGLE_PACKAGE_PREFIX = "com.google"
        const val SYSTEM_PACKAGE_PREFIX = "android"
    }
    
    object Settings {
        const val PREFS_NAME = "de1984_prefs"

        const val KEY_SHOW_APP_ICONS = "show_app_icons"
        const val KEY_DEFAULT_FIREWALL_POLICY = "default_firewall_policy"
        const val KEY_FIREWALL_ENABLED = "firewall_enabled"
        const val KEY_VPN_SERVICE_RUNNING = "vpn_service_running"  // Tracks if VPN service is actually running
        const val KEY_NEW_APP_NOTIFICATIONS = "new_app_notifications"
        const val KEY_FIREWALL_MODE = "firewall_mode"

        const val POLICY_BLOCK_ALL = "block_all"
        const val POLICY_ALLOW_ALL = "allow_all"

        const val MODE_AUTO = "auto"
        const val MODE_VPN = "vpn"
        const val MODE_IPTABLES = "iptables"

        const val DEFAULT_SHOW_APP_ICONS = true
        const val DEFAULT_FIREWALL_POLICY = POLICY_ALLOW_ALL
        const val DEFAULT_FIREWALL_ENABLED = false
        const val DEFAULT_NEW_APP_NOTIFICATIONS = true
        const val DEFAULT_FIREWALL_MODE = MODE_AUTO

    }

    object Permissions {
        const val QUERY_ALL_PACKAGES_PERMISSION = "android.permission.QUERY_ALL_PACKAGES"
        const val WRITE_SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
        const val CHANGE_COMPONENT_ENABLED_STATE_PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
        const val KILL_BACKGROUND_PROCESSES_PERMISSION = "android.permission.KILL_BACKGROUND_PROCESSES"
        const val REQUEST_DELETE_PACKAGES_PERMISSION = "android.permission.REQUEST_DELETE_PACKAGES"
    }

    object RootAccess {
        const val STATUS_GRANTED = "Root Access: Granted"
        const val STATUS_DENIED = "Root Access: Denied"
        const val STATUS_NOT_AVAILABLE = "Root Access: Not Available"
        const val STATUS_CHECKING = "Root Access: Checking..."

        const val DESC_GRANTED = "Advanced operations are available"
        const val DESC_DENIED = "Root permission was denied. Follow the instructions below to grant access."
        const val DESC_NOT_AVAILABLE = "Your device is not rooted. Root access is required for advanced package management operations."
        const val DESC_CHECKING = "Please wait..."

        const val GRANT_INSTRUCTIONS_TITLE = "To grant root access:"
        const val GRANT_INSTRUCTIONS_BODY = "• Reinstall the app to trigger the permission dialog again\n• Or manually add De1984 to your superuser app (Magisk, KernelSU, etc.)"

        const val ROOTING_TOOLS_TITLE = "<b>Recommended rooting tools:</b>"
        const val ROOTING_TOOLS_BODY = "• Magisk - Most popular and widely supported root solution. Works on Android 5.0+ and supports modules for additional features.\n• KernelSU - Modern kernel-based root management. Provides better security isolation and doesn't modify system partition.\n• APatch - Newer alternative with kernel patching approach. Good for devices with strict security policies."

        const val SETUP_INSTRUCTIONS = """To enable package disable/enable functionality:

1. Root your device using your preferred method
2. Grant superuser access to De1984 when prompted
3. Restart the app to use advanced operations"""
    }

    object ShizukuAccess {
        const val STATUS_GRANTED = "Shizuku: Granted"
        const val STATUS_DENIED = "Shizuku: Denied"
        const val STATUS_NOT_AVAILABLE = "Shizuku: Not Installed"
        const val STATUS_NOT_RUNNING = "Shizuku: Not Running"
        const val STATUS_CHECKING = "Shizuku: Checking..."

        const val DESC_GRANTED = "Advanced operations are available via Shizuku"
        const val DESC_DENIED = "Shizuku permission was denied. Tap 'Grant Shizuku Permission' to try again."
        const val DESC_NOT_AVAILABLE = "Shizuku is not installed. Install Shizuku to enable package management without root."
        const val DESC_NOT_RUNNING = "Shizuku is installed but not running. Start Shizuku to enable package management."
        const val DESC_CHECKING = "Please wait..."

        const val WHAT_IS_SHIZUKU = "Shizuku allows apps to use system APIs with elevated privileges (ADB or root). It's a safer alternative to traditional root access for package management."
    }

    object Firewall {
        const val STATE_BLOCKED = "Blocked"
        const val STATE_ALLOWED = "Allowed"
        const val STATE_INTERNET_ONLY = "Internet Only"

        val PACKAGE_TYPE_FILTERS = listOf("User", "System")
        val NETWORK_STATE_FILTERS = listOf("Allowed", "Blocked")
        val PERMISSION_FILTERS = listOf("Internet Only")

        val NETWORK_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE"
        )

        const val SYSTEM_PACKAGE_WARNING = "⚠️ System Package Warning"

        /**
         * Packages that should NEVER be blocked to prevent breaking core Android functionality.
         * These packages are critical for network connectivity and system operation.
         */
        val SYSTEM_WHITELIST = setOf(
            // De1984 itself
            App.PACKAGE_NAME,
            App.PACKAGE_NAME_DEBUG,

            // Critical network infrastructure
            "com.android.networkstack",           // Network stack (DHCP, IP config, network validation)
            "com.android.networkstack.inprocess", // Network stack (older Android versions)
            "com.android.networkstack.permissionconfig", // Network stack permissions
            "com.android.resolv",                 // DNS resolver (critical for all network operations)

            // Critical system UI
            "com.android.systemui",               // System UI (prevents UI crashes)
            "com.android.settings",               // Settings app (allows user to fix issues)

            // Captive portal detection (optional but recommended)
            "com.android.captiveportallogin",     // Captive portal login (hotel/airport WiFi)
        )

        /**
         * Check if a package is system-critical and should never be blocked.
         */
        fun isSystemCritical(packageName: String): Boolean {
            return SYSTEM_WHITELIST.contains(packageName)
        }
    }

    object Navigation {
        // Bottom Navigation Destinations
        const val DESTINATION_FIREWALL = "firewall"
        const val DESTINATION_PACKAGES = "packages"
        const val DESTINATION_SETTINGS = "settings"

        // Navigation Labels
        const val LABEL_FIREWALL = "Firewall"
        const val LABEL_PACKAGES = "Packages"
        const val LABEL_SETTINGS = "Settings"

        // Toolbar Titles (uppercase for consistency)
        const val TITLE_FIREWALL = "De1984 FIREWALL"
        const val TITLE_PACKAGES = "De1984 PACKAGES"
        const val TITLE_SETTINGS = "De1984 SETTINGS"
    }

    object PrivilegedAccessBanner {
        // Banner Messages - Context-aware based on actual device capabilities
        const val MESSAGE_NO_ACCESS_AVAILABLE = "No privileged access available. Install Shizuku or root your device to enable package management."
        const val MESSAGE_SHIZUKU_NOT_RUNNING = "Shizuku is installed but not running. Start Shizuku or root your device to enable package management."
        const val MESSAGE_PERMISSION_REQUIRED = "Shizuku or root access required for package management"

        // Button Text
        const val BUTTON_GO_TO_SETTINGS = "Go to Settings"
        const val BUTTON_GRANT = "Grant"
        const val BUTTON_DISMISS = "Dismiss"
    }

}
