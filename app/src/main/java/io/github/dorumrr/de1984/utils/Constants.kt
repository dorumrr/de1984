package io.github.dorumrr.de1984.utils

object Constants {

    object UI {
        // Bottom Navigation (actually used in MainActivity.kt)
        const val BOTTOM_NAV_ICON_SIZE_ENLARGED = 27.5f
        const val BOTTOM_NAV_PADDING_TOP = 12
        const val BOTTOM_NAV_PADDING_BOTTOM = 3
        const val BOTTOM_NAV_TEXT_TRANSLATION_Y = -2

        // Dropdown behavior
        const val DROPDOWN_DISMISSAL_DELAY_MS = 200L

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
        const val TYPE_ALL = "all"

        const val STATE_ENABLED = "Enabled"
        const val STATE_DISABLED = "Disabled"
        const val STATE_UNINSTALLED = "Uninstalled"

        val PACKAGE_TYPE_FILTERS = listOf("All", "User", "System")
        val PACKAGE_STATE_FILTERS = listOf("Enabled", "Disabled", "Uninstalled")

        const val ANDROID_PACKAGE_PREFIX = "com.android"
        const val GOOGLE_PACKAGE_PREFIX = "com.google"
        const val SYSTEM_PACKAGE_PREFIX = "android"

        object MultiSelect {
            const val MAX_SELECTION_COUNT = 50
            const val TOAST_CANNOT_SELECT_CRITICAL = "Important/Essential packages must be uninstalled individually"
            const val TOAST_SELECTION_LIMIT = "Maximum 50 packages can be selected at once"

            const val TOOLBAR_TITLE_FORMAT = "%d selected"
            const val TOOLBAR_BUTTON_UNINSTALL = "Uninstall"
            const val TOOLBAR_BUTTON_REINSTALL = "Reinstall"
            const val TOOLBAR_BUTTON_CLEAR = "Clear"

            const val DIALOG_TITLE_UNINSTALL_MULTIPLE = "Uninstall %d Selected Apps?"
            const val DIALOG_MESSAGE_CANNOT_UNDO = "This action cannot be undone."
            const val DIALOG_BUTTON_UNINSTALL_ALL = "Uninstall All"
            const val DIALOG_BUTTON_CANCEL = "Cancel"

            const val DIALOG_TITLE_RESULTS = "Uninstall Results"
            const val DIALOG_MESSAGE_SUCCESS_FORMAT = "✓ Successfully uninstalled: %d"
            const val DIALOG_MESSAGE_FAILED_FORMAT = "✗ Failed to uninstall: %d"
            const val DIALOG_BUTTON_OK = "OK"

            const val PROGRESS_DIALOG_TITLE = "Uninstalling Packages"
            const val PROGRESS_MESSAGE_FORMAT = "Uninstalling %d of %d..."

            const val DIALOG_TITLE_REINSTALL_RESULTS = "Reinstall Results"
            const val DIALOG_MESSAGE_REINSTALL_SUCCESS_FORMAT = "✓ Successfully reinstalled: %d"
            const val DIALOG_MESSAGE_REINSTALL_FAILED_FORMAT = "✗ Failed to reinstall: %d"

            const val PROGRESS_DIALOG_TITLE_REINSTALL = "Reinstalling Packages"
            const val PROGRESS_MESSAGE_FORMAT_REINSTALL = "Reinstalling %d of %d..."

            const val CHECKBOX_CONTENT_DESCRIPTION = "Select package"
        }

        const val EMPTY_STATE_NO_UNINSTALLED = "No uninstalled system apps"
    }
    
    object Settings {
        const val PREFS_NAME = "de1984_prefs"

        const val KEY_SHOW_APP_ICONS = "show_app_icons"
        const val KEY_DEFAULT_FIREWALL_POLICY = "default_firewall_policy"
        const val KEY_FIREWALL_ENABLED = "firewall_enabled"
        const val KEY_VPN_SERVICE_RUNNING = "vpn_service_running"  // Tracks if VPN service is actually running
        const val KEY_VPN_INTERFACE_ACTIVE = "vpn_interface_active"  // Tracks if VPN interface is established (separate from service running)
        const val KEY_PRIVILEGED_SERVICE_RUNNING = "privileged_service_running"  // Tracks if privileged firewall service is running
        const val KEY_PRIVILEGED_BACKEND_TYPE = "privileged_backend_type"  // Stores which privileged backend is active (iptables/connectivity_manager/network_policy_manager)
        const val KEY_NEW_APP_NOTIFICATIONS = "new_app_notifications"
        const val KEY_FIREWALL_MODE = "firewall_mode"
        const val KEY_ALLOW_CRITICAL_UNINSTALL = "allow_critical_package_uninstall"
        const val KEY_SHOW_FIREWALL_START_PROMPT = "show_firewall_start_prompt"
        const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
        const val KEY_APP_LANGUAGE = "app_language"

        const val POLICY_BLOCK_ALL = "block_all"
        const val POLICY_ALLOW_ALL = "allow_all"

        const val LANGUAGE_SYSTEM_DEFAULT = "system"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_ROMANIAN = "ro"
        const val LANGUAGE_PORTUGUESE = "pt"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_ITALIAN = "it"

        const val MODE_AUTO = "auto"
        const val MODE_VPN = "vpn"
        const val MODE_IPTABLES = "iptables"

        const val DEFAULT_SHOW_APP_ICONS = true
        const val DEFAULT_FIREWALL_POLICY = POLICY_ALLOW_ALL
        const val DEFAULT_FIREWALL_ENABLED = false
        const val DEFAULT_NEW_APP_NOTIFICATIONS = true
        const val DEFAULT_FIREWALL_MODE = MODE_AUTO
        const val DEFAULT_ALLOW_CRITICAL_UNINSTALL = false
        const val DEFAULT_SHOW_FIREWALL_START_PROMPT = true
        const val DEFAULT_USE_DYNAMIC_COLORS = false
        const val DEFAULT_APP_LANGUAGE = LANGUAGE_SYSTEM_DEFAULT

    }

    object CaptivePortal {
        // SharedPreferences name (reuse same prefs file as Settings)
        const val PREFS_NAME = "de1984_prefs"

        // Keys for original settings backup (stored in SharedPreferences)
        const val KEY_ORIGINAL_CAPTURED = "captive_portal_original_captured"
        const val KEY_ORIGINAL_MODE = "captive_portal_original_mode"
        const val KEY_ORIGINAL_HTTP_URL = "captive_portal_original_http_url"
        const val KEY_ORIGINAL_HTTPS_URL = "captive_portal_original_https_url"
        const val KEY_ORIGINAL_FALLBACK_URL = "captive_portal_original_fallback_url"
        const val KEY_ORIGINAL_OTHER_FALLBACK_URLS = "captive_portal_original_other_fallback_urls"
        const val KEY_ORIGINAL_USE_HTTPS = "captive_portal_original_use_https"
        const val KEY_ORIGINAL_DEVICE_MODEL = "captive_portal_original_device_model"
        const val KEY_ORIGINAL_SDK_INT = "captive_portal_original_sdk_int"
        const val KEY_ORIGINAL_ROM_NAME = "captive_portal_original_rom_name"

        // Android system settings keys (used with "settings get/put global")
        const val SYSTEM_KEY_MODE = "captive_portal_mode"
        const val SYSTEM_KEY_HTTP_URL = "captive_portal_http_url"
        const val SYSTEM_KEY_HTTPS_URL = "captive_portal_https_url"
        const val SYSTEM_KEY_FALLBACK_URL = "captive_portal_fallback_url"
        const val SYSTEM_KEY_OTHER_FALLBACK_URLS = "captive_portal_other_fallback_urls"
        const val SYSTEM_KEY_USE_HTTPS = "captive_portal_use_https"

        // Default values (Google's defaults)
        const val DEFAULT_MODE = 1  // ENABLED
        const val DEFAULT_HTTP_URL = "http://connectivitycheck.gstatic.com/generate_204"
        const val DEFAULT_HTTPS_URL = "https://www.google.com/generate_204"
        const val DEFAULT_USE_HTTPS = true
    }

    object HealthCheck {
        // Adaptive health check intervals for privileged backends
        // Start with fast checks (30s) for first 5 minutes, then slow down to 5 minutes
        // This provides fast failure detection initially while saving battery once backend is stable
        const val BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS = 30_000L  // 30 seconds - fast detection
        const val BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS = 300_000L  // 5 minutes - battery savings
        const val BACKEND_HEALTH_CHECK_STABLE_THRESHOLD = 10  // Consecutive successful checks before increasing interval
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
        const val STATE_INTERNET = "Internet"

        val PACKAGE_TYPE_FILTERS = listOf("All", "User", "System")
        val NETWORK_STATE_FILTERS = listOf("Allowed", "Blocked")
        val PERMISSION_FILTERS = listOf("Internet")

        val NETWORK_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE"
        )

        /**
         * VPN permission used to identify VPN apps.
         * Apps that declare this permission are VPN service providers.
         */
        const val VPN_SERVICE_PERMISSION = "android.permission.BIND_VPN_SERVICE"

        const val SYSTEM_PACKAGE_WARNING = "⚠️ System Package Warning"

        /**
         * Packages that should NEVER be blocked to prevent breaking core Android functionality.
         * These packages are critical for network connectivity and system operation.
         *
         * Note: Different Android devices use different package names for the network stack:
         * - AOSP/Stock Android: com.android.networkstack
         * - Google/Pixel devices: com.google.android.networkstack
         * - Older Android (<10): com.android.networkstack.inprocess
         * - Samsung devices: com.samsung.android.networkstack
         * - Vivo devices: com.google.android.networkstack.overlay.vivo
         * - OnePlus China: com.android.networkstack.inprocess.cn
         *
         * OEM overlay packages follow the pattern: com.google.android.networkstack.overlay.<oem>
         * or com.<oem>.android.networkstack
         *
         * If you find a device where the network stack can be blocked, please report it with the package name.
         */
        val SYSTEM_WHITELIST = setOf(
            // De1984 itself
            App.PACKAGE_NAME,
            App.PACKAGE_NAME_DEBUG,

            // Critical network infrastructure - AOSP/Stock Android
            "com.android.networkstack",           // Network stack (AOSP/Stock Android 10+)
            "com.android.networkstack.tethering", // Network stack tethering (connectivity validation, shares UID with networkstack)
            "com.android.networkstack.inprocess", // Network stack (older Android <10)
            "com.android.networkstack.permissionconfig", // Network stack permissions
            "com.android.resolv",                 // DNS resolver (critical for all network operations)

            // Critical network infrastructure - Google/Pixel devices
            "com.google.android.networkstack",    // Network stack (Google/Pixel devices)

            // Critical network infrastructure - Samsung devices
            "com.samsung.android.networkstack",   // Network stack (Samsung devices)
            "com.samsung.android.networkstack.tethering.overlay", // Samsung tethering overlay

            // Critical network infrastructure - OEM variants (OnePlus, Vivo, etc.)
            "com.android.networkstack.inprocess.cn", // OnePlus China variant
            "com.google.android.networkstack.overlay.vivo", // Vivo overlay
            "com.oneplus.commonoverlay.com.android.networkstack.inprocess.cn", // OnePlus overlay

            // Critical system services
            "com.android.cellbroadcastservice",   // Emergency broadcast service (shares UID with networkstack)

            // Critical system UI
            "com.android.systemui",               // System UI (prevents UI crashes)
            "com.android.settings",               // Settings app (allows user to fix issues)

            // Captive portal detection (optional but recommended)
            "com.android.captiveportallogin",     // Captive portal login (hotel/airport WiFi)
        )

        /**
         * System-recommended apps that should be allowed by default.
         * These apps are important for system functionality but CAN be blocked by advanced users.
         *
         * Unlike SYSTEM_WHITELIST (which cannot be blocked), these apps:
         * - Are always created with "allow all" rules, even when default policy is "Block All"
         * - Can be manually blocked by users if desired
         * - Have normal (enabled) controls in the UI
         *
         * This ensures users can use "Block All" default policy without breaking WiFi/Bluetooth/Downloads.
         */
        val SYSTEM_RECOMMENDED_ALLOW = setOf(
            // WiFi and Connectivity
            "com.android.wifi",                    // WiFi service
            "com.android.wifi.resources",          // WiFi resources

            // Bluetooth
            "com.android.bluetooth",               // Bluetooth service
            "com.android.bluetoothmidiservice",    // Bluetooth MIDI

            // Download Manager
            "com.android.providers.downloads",     // Download Manager
            "com.android.providers.downloads.ui",  // Download Manager UI

            // NFC (for contactless payments, file sharing)
            "com.android.nfc",                     // NFC service
        )

        /**
         * Check if a package is system-critical and should never be blocked.
         */
        fun isSystemCritical(packageName: String): Boolean {
            return SYSTEM_WHITELIST.contains(packageName)
        }

        /**
         * Check if a package is system-recommended and should be allowed by default.
         */
        fun isSystemRecommendedAllow(packageName: String): Boolean {
            return SYSTEM_RECOMMENDED_ALLOW.contains(packageName)
        }

        /**
         * Check if a package is a VPN app based on its permissions.
         * VPN apps should always be allowed to prevent VPN reconnection issues.
         *
         * @param permissions List of permissions declared by the app
         * @return true if the app has BIND_VPN_SERVICE permission (is a VPN app)
         */
        fun isVpnApp(permissions: List<String>): Boolean {
            return permissions.contains(VPN_SERVICE_PERMISSION)
        }
    }

    object Notifications {
        // Notification actions
        const val ACTION_OPEN_FIREWALL = "io.github.dorumrr.de1984.OPEN_FIREWALL"
        const val ACTION_TOGGLE_NETWORK_ACCESS = "io.github.dorumrr.de1984.TOGGLE_NETWORK_ACCESS"
        const val ACTION_ENABLE_VPN_FALLBACK = "io.github.dorumrr.de1984.ENABLE_VPN_FALLBACK"

        // Intent extras
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_BLOCKED = "blocked"
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

    object BackendMonitoring {
        // Service Actions
        const val ACTION_START = "io.github.dorumrr.de1984.action.START_BACKEND_MONITORING"
        const val ACTION_STOP = "io.github.dorumrr.de1984.action.STOP_BACKEND_MONITORING"
        const val ACTION_RETRY = "io.github.dorumrr.de1984.action.RETRY_BACKEND_SWITCH"

        // Intent Extras
        const val EXTRA_SHIZUKU_STATUS = "shizuku_status"

        // Notification
        const val CHANNEL_ID = "backend_monitoring_channel"
        const val CHANNEL_NAME = "Backend Monitoring"
        const val NOTIFICATION_ID = 1003

        // Timeouts
        const val TIMEOUT_NO_SHIZUKU_MS = 600_000L  // 10 minutes if Shizuku not installed
        const val SUCCESS_NOTIFICATION_DURATION_MS = 3_000L  // 3 seconds

        // Notification Titles
        const val NOTIFICATION_TITLE_WAITING = "De1984 Firewall Active (VPN Mode)"
        const val NOTIFICATION_TITLE_SWITCHING = "De1984 is switching backend..."
        const val NOTIFICATION_TITLE_SUCCESS = "De1984 Firewall backend switched"
        const val NOTIFICATION_TITLE_FAILED = "De1984 Firewall backend switch failed"

        // Notification Texts
        const val NOTIFICATION_TEXT_SHIZUKU_NOT_RUNNING = "Waiting for Shizuku to start. Tap to retry."
        const val NOTIFICATION_TEXT_SHIZUKU_NO_PERMISSION = "Waiting for Shizuku permission. Tap to retry."
        const val NOTIFICATION_TEXT_SWITCHING = "Shizuku is now available. Switching to preferred backend..."
        const val NOTIFICATION_TEXT_SUCCESS_CONNECTIVITY_MANAGER = "De1984 Firewall is now using ConnectivityManager backend"
        const val NOTIFICATION_TEXT_SUCCESS_IPTABLES = "De1984 Firewall is now using iptables backend"
        const val NOTIFICATION_TEXT_FAILED = "De1984 Firewall failed to switch backend. Still using VPN."

        // Action Button Text
        const val ACTION_BUTTON_RETRY = "Retry"

        // Toast Messages
        const val TOAST_SUCCESS_CONNECTIVITY_MANAGER = "De1984 Firewall switched to ConnectivityManager"
        const val TOAST_SUCCESS_IPTABLES = "De1984 Firewall switched to iptables"
        const val TOAST_FAILED = "De1984 Firewall failed to switch backend"
    }

    object VpnFallback {
        // Notification Channel
        const val CHANNEL_ID = "vpn_fallback_channel"
        const val CHANNEL_NAME = "VPN Fallback"
        const val NOTIFICATION_ID = 1004

        // Notification Content
        const val NOTIFICATION_TITLE = "De1984 Firewall Down"
        const val NOTIFICATION_TEXT = "Privileged backend failed. Tap to enable VPN fallback and restore firewall protection."
        const val NOTIFICATION_ACTION_TEXT = "Enable VPN Fallback"

        // Permission Tier
        const val TIER_TITLE = "VPN Fallback"
        const val TIER_DESCRIPTION = "Allows automatic fallback to VPN when privileged backends (iptables/ConnectivityManager) fail. Ensures firewall stays active."
        const val TIER_STATUS_GRANTED = "Completed"
        const val TIER_STATUS_NOT_GRANTED = "Permission Required"
        const val TIER_BUTTON_TEXT = "Grant VPN Permission"

        // Permission Info
        const val PERMISSION_NAME = "VPN Permission"
        const val PERMISSION_DESCRIPTION = "Create VPN connection for firewall fallback"
    }

}
