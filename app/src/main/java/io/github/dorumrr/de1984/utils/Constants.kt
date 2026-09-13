package io.github.dorumrr.de1984.utils

import io.github.dorumrr.de1984.BuildConfig

object Constants {

    object UI {
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

        // iptables chains and the boot script are device-wide: shared names let one build delete the other's.
        val ROOT_NAME_PREFIX = if (BuildConfig.APPLICATION_ID == PACKAGE_NAME_DEBUG) "de1984dbg" else "de1984"

        fun isOwnApp(packageName: String): Boolean {
            return packageName == PACKAGE_NAME || packageName == PACKAGE_NAME_DEBUG
        }
    }
    
    object Packages {
        const val TYPE_SYSTEM = "system"
        const val TYPE_USER = "user"
        const val TYPE_ALL = "all"

        /**
         * Issue #96. Packages screen only - the Firewall screen has no such chip and its mapper
         * would not know what to do with it. See FilterPrefs, which normalises the two separately
         * for exactly that reason.
         *
         * Not a package TYPE in the way the others are: it selects on criticality, which arrives
         * from the bundled package_safety_levels.json. Apps that file does not mention come back
         * UNKNOWN, so this shows known bloatware and does not guess.
         */
        const val TYPE_BLOATWARE = "bloatware"

        const val STATE_ENABLED = "Enabled"
        const val STATE_DISABLED = "Disabled"
        const val STATE_UNINSTALLED = "Uninstalled"

        val PACKAGE_TYPE_FILTERS = listOf("All", "User", "System")
        val PACKAGE_STATE_FILTERS = listOf("Enabled", "Disabled", "Uninstalled")
        val PROFILE_FILTERS = listOf("All", "Personal", "Work", "Clone")

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
        const val KEY_NPM_ORIGINAL_POLICIES = "npm_original_policies"  // "uid:policy" pairs: what each UID looked like before the NetworkPolicyManager backend touched it
        const val KEY_CM_BLOCKED_PACKAGES = "cm_blocked_packages"  // package names the ConnectivityManager backend has denied networking; survives the process so a fresh one can undo them
        const val KEY_CM_CHAIN3_ENABLED_BY_US = "cm_chain3_enabled_by_us"  // true only while De1984 is the one that turned FIREWALL_CHAIN_OEM_DENY_3 on
        const val KEY_IPTABLES_CHAINS_INSTALLED = "iptables_chains_installed"  // true from the moment the de1984 chains are created until a teardown is verified clean; iptables rules outlive the process, so this is what tells a fresh instance there is something to undo
        const val KEY_PRIVILEGED_BACKEND_TYPE = "privileged_backend_type"  // Stores which privileged backend is active (iptables/connectivity_manager/network_policy_manager)
        const val KEY_NEW_APP_NOTIFICATIONS = "new_app_notifications"
        const val KEY_BOOT_PROTECTION = "boot_protection"
        const val KEY_FIREWALL_MODE = "firewall_mode"
        const val KEY_ALLOW_CRITICAL_UNINSTALL = "allow_critical_package_uninstall"
        const val KEY_ALLOW_CRITICAL_FIREWALL = "allow_critical_package_firewall"
        const val KEY_SHOW_FIREWALL_START_PROMPT = "show_firewall_start_prompt"
        const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_CONFIRM_RULE_CHANGES = "confirm_rule_changes"
        const val KEY_CONFIRM_FIREWALL_STOP = "confirm_firewall_stop"

        // Last filter selection on each list screen, so it survives a restart (issue #71).
        // These hold the INTERNAL values ("user", "Work", ...), never the translated chip text,
        // so a language change cannot orphan a saved filter.
        const val KEY_FIREWALL_FILTER_TYPE = "firewall_filter_type"
        const val KEY_FIREWALL_FILTER_STATE = "firewall_filter_state"
        const val KEY_FIREWALL_FILTER_INTERNET_ONLY = "firewall_filter_internet_only"
        const val KEY_FIREWALL_FILTER_PROFILE = "firewall_filter_profile"
        const val KEY_PACKAGES_FILTER_TYPE = "packages_filter_type"
        const val KEY_PACKAGES_FILTER_STATE = "packages_filter_state"
        const val KEY_PACKAGES_FILTER_PROFILE = "packages_filter_profile"

        const val POLICY_BLOCK_ALL = "block_all"
        const val POLICY_ALLOW_ALL = "allow_all"

        const val LANGUAGE_SYSTEM_DEFAULT = "system"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_ROMANIAN = "ro"
        const val LANGUAGE_PORTUGUESE = "pt"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_ITALIAN = "it"
        const val LANGUAGE_FRENCH = "fr"
        const val LANGUAGE_RUSSIAN = "ru"

        const val MODE_AUTO = "auto"
        const val MODE_VPN = "vpn"
        const val MODE_IPTABLES = "iptables"

        const val DEFAULT_SHOW_APP_ICONS = true
        const val DEFAULT_FIREWALL_POLICY = POLICY_ALLOW_ALL
        const val DEFAULT_FIREWALL_ENABLED = false
        const val DEFAULT_NEW_APP_NOTIFICATIONS = true
        const val DEFAULT_BOOT_PROTECTION = false
        const val DEFAULT_FIREWALL_MODE = MODE_AUTO
        const val DEFAULT_ALLOW_CRITICAL_UNINSTALL = false
        const val DEFAULT_ALLOW_CRITICAL_FIREWALL = false
        const val DEFAULT_SHOW_FIREWALL_START_PROMPT = true
        const val DEFAULT_USE_DYNAMIC_COLORS = false
        const val DEFAULT_APP_LANGUAGE = LANGUAGE_SYSTEM_DEFAULT
        const val DEFAULT_CONFIRM_RULE_CHANGES = true

        /**
         * ON by default on purpose. Stopping the firewall gives every app network access at once,
         * so it stays behind a confirmation unless the user deliberately turns that off (issue #91).
         */
        const val DEFAULT_CONFIRM_FIREWALL_STOP = true

    }

    object BootProtection {
        const val MAGISK_POST_FS_DIR = "/data/adb/post-fs-data.d"
        val BOOT_SCRIPT_PATH = "$MAGISK_POST_FS_DIR/${App.ROOT_NAME_PREFIX}_boot_protection.sh"
        val BOOT_CHAIN = "${App.ROOT_NAME_PREFIX}_boot"
        const val BOOT_SCRIPT_PERMISSIONS = "755"

        /**
         * Seconds the boot script waits before lifting its own block.
         *
         * The block is only meant to cover the gap between early boot and De1984 taking over. If that
         * never happens - failed start, screen still locked, app uninstalled, app data cleared - the
         * device would otherwise be left with no network on every boot, permanently, with no in-app
         * way out. This timer guarantees the device always recovers on its own.
         *
         * De1984 normally removes the chain within seconds of the firewall starting, well inside this
         * window, so raising it costs nothing in the normal case. Lowering it shortens the protected
         * window; raising it lengthens how long a stuck device stays offline.
         */
        const val SELF_HEAL_TIMEOUT_SECONDS = 120

    }

    object CaptivePortal {
        const val PREFS_NAME = "de1984_prefs"

        const val KEY_ORIGINAL_CAPTURED = "captive_portal_original_captured"
        const val KEY_ORIGINAL_MODE = "captive_portal_original_mode"
        // Raw snapshots: the exact string the system held, or absent if the key was never set.
        // KEY_ORIGINAL_MODE and KEY_ORIGINAL_USE_HTTPS cannot express "unset" - they are an Int and
        // a Boolean, so an unset key was stored as a made-up default and later written back to the
        // device, creating a setting it never had. Kept only to read backups made before this.
        // Marks a backup written in the raw format. Presence of the raw value keys cannot be
        // used for this: storing null removes the key, so a correct fresh capture on a device
        // where mode and use_https are unset looks exactly like a pre-raw backup.
        const val KEY_ORIGINAL_RAW_FORMAT = "captive_portal_original_raw_format"
        const val KEY_ORIGINAL_MODE_RAW = "captive_portal_original_mode_raw"
        const val KEY_ORIGINAL_USE_HTTPS_RAW = "captive_portal_original_use_https_raw"
        const val KEY_ORIGINAL_HTTP_URL = "captive_portal_original_http_url"
        const val KEY_ORIGINAL_HTTPS_URL = "captive_portal_original_https_url"
        const val KEY_ORIGINAL_FALLBACK_URL = "captive_portal_original_fallback_url"
        const val KEY_ORIGINAL_OTHER_FALLBACK_URLS = "captive_portal_original_other_fallback_urls"
        const val KEY_ORIGINAL_USE_HTTPS = "captive_portal_original_use_https"
        const val KEY_ORIGINAL_DEVICE_MODEL = "captive_portal_original_device_model"
        const val KEY_ORIGINAL_SDK_INT = "captive_portal_original_sdk_int"
        const val KEY_ORIGINAL_ROM_NAME = "captive_portal_original_rom_name"

        const val SYSTEM_KEY_MODE = "captive_portal_mode"
        const val SYSTEM_KEY_HTTP_URL = "captive_portal_http_url"
        const val SYSTEM_KEY_HTTPS_URL = "captive_portal_https_url"
        const val SYSTEM_KEY_FALLBACK_URL = "captive_portal_fallback_url"
        const val SYSTEM_KEY_OTHER_FALLBACK_URLS = "captive_portal_other_fallback_urls"
        const val SYSTEM_KEY_USE_HTTPS = "captive_portal_use_https"

        const val DEFAULT_MODE = 1
        const val DEFAULT_HTTP_URL = "http://connectivitycheck.gstatic.com/generate_204"
        const val DEFAULT_HTTPS_URL = "https://www.google.com/generate_204"
        const val DEFAULT_USE_HTTPS = true
    }

    object HealthCheck {
        // Adaptive health check intervals for privileged backends
        // Start with fast checks, then slow down after BACKEND_HEALTH_CHECK_STABLE_THRESHOLD
        // consecutive successes. There are exactly two intervals and one step-up.
        // This provides fast failure detection initially while saving battery once backend is stable
        const val BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS = 15_000L
        const val BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS = 60_000L
        const val BACKEND_HEALTH_CHECK_STABLE_THRESHOLD = 10
    }

    object Permissions {
        const val QUERY_ALL_PACKAGES_PERMISSION = "android.permission.QUERY_ALL_PACKAGES"
        const val WRITE_SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
        const val CHANGE_COMPONENT_ENABLED_STATE_PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
        const val KILL_BACKGROUND_PROCESSES_PERMISSION = "android.permission.KILL_BACKGROUND_PROCESSES"
        const val REQUEST_DELETE_PACKAGES_PERMISSION = "android.permission.REQUEST_DELETE_PACKAGES"
    }

    object RootAccess {
        // Command used to verify root is still active on an existing shell
        // Running this on a cached shell does NOT trigger Magisk toast
        const val ROOT_VERIFICATION_COMMAND = "id"
        const val ROOT_VERIFICATION_SUCCESS_MARKER = "uid=0"

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

        const val ACTION_TOGGLE_FIREWALL = "io.github.dorumrr.de1984.TOGGLE_FIREWALL"
        const val ACTION_FIREWALL_STATE_CHANGED = "io.github.dorumrr.de1984.FIREWALL_STATE_CHANGED"
        const val ACTION_REQUEST_VPN_PERMISSION = "io.github.dorumrr.de1984.REQUEST_VPN_PERMISSION"

        const val EXTRA_FIREWALL_STATE = "firewall_state"
        const val EXTRA_BACKEND_TYPE = "backend_type"

        val PACKAGE_TYPE_FILTERS = listOf("All", "User", "System")
        val NETWORK_STATE_FILTERS = listOf("Allowed", "Blocked")
        val PERMISSION_FILTERS = listOf("Internet")
        val PROFILE_FILTERS = listOf("All", "Personal", "Work", "Clone")

        val NETWORK_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE"
        )

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
        private val SYSTEM_WHITELIST = setOf(
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

            // Shizuku (required for wireless ADB mode - blocking it breaks the service)
            "moe.shizuku.privileged.api",         // Shizuku app (provides ADB/root-level access to other apps)
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
         *
         * NOTE: Google Play Services is included here because:
         * - It hosts Firebase Cloud Messaging (FCM) - the push notification infrastructure for most apps
         * - Blocking it breaks notifications system-wide on GMS phones (Issue #66)
         * - Users can still manually block it if desired (degoogled/privacy-focused users)
         * - On degoogled devices without GMS, this entry has no effect
         *
         * Google Services Framework is included for the same reason (Issue #101). Play Services
         * hosts FCM, but the device registration it delivers to lives in GSF, so allowing
         * com.google.android.gms alone was not enough: push notifications stopped arriving and
         * the Play Store lost its login after a few days, while the firewall showed exactly what
         * the user had asked for. Same escape hatch - a degoogled user can still block it.
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

            // Google Play Services (FCM push notifications)
            "com.google.android.gms",              // Google Play Services - hosts FCM for push notifications
            "com.google.android.gsf",              // Google Services Framework - device registration FCM and Play Store sign-in depend on
        )

        /**
         * [SYSTEM_WHITELIST] plus anything discovered at runtime. Swapped whole, never mutated in
         * place, so a reader always sees a complete set.
         */
        @Volatile
        private var effectiveWhitelist: Set<String> = SYSTEM_WHITELIST

        /**
         * Record the package that actually provides Shizuku on this device.
         *
         * [SYSTEM_WHITELIST] carries the stock id. A "hide Shizuku from other apps" build renames
         * the package, so the literal entry misses it and Block All can cut the service the user
         * relies on to start Shizuku over wireless ADB. ShizukuManager calls this once it knows the
         * real name. Adds only - protection is never taken away.
         */
        fun registerShizukuPackage(packageName: String) {
            if (packageName.isBlank() || packageName in effectiveWhitelist) return
            effectiveWhitelist = SYSTEM_WHITELIST + packageName
        }

        /**
         * Every package that must never be blocked. Use this rather than [SYSTEM_WHITELIST], which
         * is the static half only and misses a renamed Shizuku.
         */
        fun systemWhitelist(): Set<String> = effectiveWhitelist

        fun isSystemCritical(packageName: String): Boolean {
            return effectiveWhitelist.contains(packageName)
        }

        /**
         * Android packs a uid as `userId * PER_USER_RANGE + appId`, and only an appId in
         * [APP_APP_ID_RANGE] belongs to an installed app - AOSP's FIRST/LAST_APPLICATION_UID.
         *
         * Both Shizuku backends are refused for anything outside it. ConnectivityManager answers
         * "Can't set package firewall rule for system app <pkg> with appId <n>";
         * NetworkPolicyManager throws "cannot apply policy to UID <uid>". Each refusal still costs a
         * Shizuku process, and enough of those kill the daemon - which is what issue #93 was.
         */
        private const val PER_USER_RANGE = 100000
        private val APP_APP_ID_RANGE = 10000..19999

        fun isFirewallableAppUid(uid: Int): Boolean = uid % PER_USER_RANGE in APP_APP_ID_RANGE

        fun isSystemRecommendedAllow(packageName: String): Boolean {
            return SYSTEM_RECOMMENDED_ALLOW.contains(packageName)
        }

        fun isVpnApp(permissions: List<String>): Boolean {
            return permissions.contains(VPN_SERVICE_PERMISSION)
        }
    }

    object Notifications {
        const val ACTION_OPEN_FIREWALL = "io.github.dorumrr.de1984.OPEN_FIREWALL"
        const val ACTION_TOGGLE_NETWORK_ACCESS = "io.github.dorumrr.de1984.TOGGLE_NETWORK_ACCESS"
        const val ACTION_ENABLE_VPN_FALLBACK = "io.github.dorumrr.de1984.ENABLE_VPN_FALLBACK"
        const val ACTION_BOOT_FAILURE_RECOVERY = "io.github.dorumrr.de1984.BOOT_FAILURE_RECOVERY"

        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_BLOCKED = "blocked"
    }

    object Navigation {
        const val DESTINATION_FIREWALL = "firewall"
        const val DESTINATION_PACKAGES = "packages"
        const val DESTINATION_SETTINGS = "settings"

        const val LABEL_FIREWALL = "Firewall"
        const val LABEL_PACKAGES = "Packages"
        const val LABEL_SETTINGS = "Settings"

        const val TITLE_FIREWALL = "De1984 FIREWALL"
        const val TITLE_PACKAGES = "De1984 PACKAGES"
        const val TITLE_SETTINGS = "De1984 SETTINGS"
    }

    object PrivilegedAccessBanner {
        const val MESSAGE_NO_ACCESS_AVAILABLE = "No privileged access available. Install Shizuku or root your device to enable package management."
        const val MESSAGE_SHIZUKU_NOT_RUNNING = "Shizuku is installed but not running. Start Shizuku or root your device to enable package management."
        const val MESSAGE_PERMISSION_REQUIRED = "Shizuku or root access required for package management"

        const val BUTTON_GO_TO_SETTINGS = "Go to Settings"
        const val BUTTON_GRANT = "Grant"
        const val BUTTON_DISMISS = "Dismiss"
    }

    object BackendMonitoring {
        const val ACTION_START = "io.github.dorumrr.de1984.action.START_BACKEND_MONITORING"
        const val ACTION_STOP = "io.github.dorumrr.de1984.action.STOP_BACKEND_MONITORING"
        const val ACTION_RETRY = "io.github.dorumrr.de1984.action.RETRY_BACKEND_SWITCH"

        const val EXTRA_SHIZUKU_STATUS = "shizuku_status"

        const val CHANNEL_ID = "backend_monitoring_channel"
        const val CHANNEL_NAME = "Backend Monitoring"
        const val NOTIFICATION_ID = 1003

        const val TIMEOUT_NO_SHIZUKU_MS = 600_000L  // 10 minutes if Shizuku not installed
        const val SUCCESS_NOTIFICATION_DURATION_MS = 3_000L

        const val NOTIFICATION_TITLE_WAITING = "De1984 Firewall Active (VPN Mode)"
        const val NOTIFICATION_TITLE_SWITCHING = "De1984 is switching backend..."
        const val NOTIFICATION_TITLE_FAILED = "De1984 Firewall backend switch failed"

        const val NOTIFICATION_TEXT_SHIZUKU_NOT_RUNNING = "Waiting for Shizuku to start. Tap to retry."
        const val NOTIFICATION_TEXT_SHIZUKU_NO_PERMISSION = "Waiting for Shizuku permission. Tap to retry."
        const val NOTIFICATION_TEXT_SWITCHING = "Shizuku is now available. Switching to preferred backend..."
        const val NOTIFICATION_TEXT_FAILED = "De1984 Firewall failed to switch backend. Still using VPN."

        const val ACTION_BUTTON_RETRY = "Retry"
        const val TOAST_FAILED = "De1984 Firewall failed to switch backend"
    }

    object VpnFallback {
        const val CHANNEL_ID = "vpn_fallback_channel"
        const val CHANNEL_NAME = "VPN Fallback"
        const val NOTIFICATION_ID = 1004

        const val NOTIFICATION_TITLE = "De1984 Firewall Down"
        const val NOTIFICATION_TEXT = "Privileged backend failed. Tap to enable VPN fallback and restore firewall protection."
        const val NOTIFICATION_ACTION_TEXT = "Enable VPN Fallback"

        const val TIER_TITLE = "VPN Fallback"
        const val TIER_DESCRIPTION = "Allows automatic fallback to VPN when privileged backends (iptables/ConnectivityManager) fail. Ensures firewall stays active."
        const val TIER_STATUS_GRANTED = "Completed"
        const val TIER_STATUS_NOT_GRANTED = "Permission Required"
        const val TIER_BUTTON_TEXT = "Grant VPN Permission"

        const val PERMISSION_NAME = "VPN Permission"
        const val PERMISSION_DESCRIPTION = "Create VPN connection for firewall fallback"
    }

    object BackendFailure {
        const val NOTIFICATION_ID = 1006
        const val CHANNEL_ID = "backend_failure_channel"
        const val CHANNEL_NAME = "Backend Failure"
    }

    object BootFailure {
        const val CHANNEL_ID = "boot_failure_channel"
        const val CHANNEL_NAME = "Boot Failure"
        const val NOTIFICATION_ID = 1005
    }

    /**
     * Failure notification raised by FirewallVpnService.
     *
     * Its own id, not FirewallVpnService.NOTIFICATION_ID + 1. That arithmetic landed on 1002, which
     * is PrivilegedFirewallService's foreground notification - posting replaced it and dismissing
     * cancelled it.
     */
    object VpnFailure {
        const val NOTIFICATION_ID = 1008
    }

    /**
     * Raised when the user stopped the firewall and the backend refused to tear down.
     *
     * Its own id so that dismissing the backend-failure notification cannot cancel it: a backend
     * reporting healthy again says nothing about rules that were never removed.
     */
    object StopFailure {
        const val NOTIFICATION_ID = 1009
    }

    object VpnConflict {
        const val CHANNEL_ID = "firewall_alerts_channel"
        const val CHANNEL_NAME = "Firewall Alerts"
        const val NOTIFICATION_ID = 1007
    }

    /**
     * Told the user the firewall was stopped from the tile or widget, when they have turned the
     * stop confirmation off (issue #91). Deliberately the SAME channel as VpnConflict: Android keys
     * channels by id, and one "Firewall Alerts" toggle is kinder than two.
     */
    object FirewallStopped {
        const val CHANNEL_ID = "firewall_alerts_channel"
        const val CHANNEL_NAME = "Firewall Alerts"
        const val NOTIFICATION_ID = 1010
    }

}
