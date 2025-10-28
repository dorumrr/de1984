package io.github.dorumrr.de1984.utils

object Constants {

    object UI {
        // Spacing values (in dp - use with resources)
        const val SPACING_EXTRA_TINY = 2
        const val SPACING_TINY = 4
        const val SPACING_SMALL = 8
        const val SPACING_MEDIUM = 12
        const val SPACING_STANDARD = 16
        const val SPACING_LARGE = 24

        const val ELEVATION_CARD = 2
        const val ELEVATION_SURFACE = 4

        const val CORNER_RADIUS_STANDARD = 12

        const val ICON_SIZE_TINY = 16
        const val ICON_SIZE_SMALL = 24
        const val ICON_SIZE_MEDIUM = 32
        const val ICON_SIZE_LARGE = 40
        const val ICON_SIZE_EXTRA_LARGE = 60

        const val STATUS_DOT_SIZE = 8

        const val PADDING_CARD_LARGE = 20

        const val BOTTOM_SHEET_CORNER_RADIUS = 28
        const val DRAG_HANDLE_WIDTH = 32
        const val DRAG_HANDLE_HEIGHT = 4
        const val BOTTOM_SHEET_DISMISS_THRESHOLD = 100

        const val BORDER_WIDTH_THIN = 1

        const val TOGGLE_SWITCH_WIDTH = 56
        const val BOTTOM_NAV_HEIGHT = 80

        object Dialogs {
            const val FIREWALL_START_TITLE = "Start De1984 Firewall?"
            const val FIREWALL_START_MESSAGE = "Welcome to De1984! Would you like to start the firewall now to protect your privacy?"
            const val FIREWALL_START_CONFIRM = "Start Firewall"
            const val FIREWALL_START_SKIP = "Later"
        }
        const val BADGE_PADDING_HORIZONTAL = 8
        const val BADGE_PADDING_VERTICAL = 4

        const val BUTTON_PADDING_HORIZONTAL = 12
        const val BUTTON_PADDING_VERTICAL = 8

        const val ALPHA_FULL = 1f
        const val ALPHA_DISABLED = 0.6f
    }
    
    object App {
        const val NAME = "De1984"
        const val PACKAGE_NAME = "io.github.dorumrr.de1984"
        const val PACKAGE_NAME_DEBUG = "io.github.dorumrr.de1984.debug"

        const val LOGO_DESCRIPTION = "De1984 Logo"

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
        const val KEY_NEW_APP_NOTIFICATIONS = "new_app_notifications"

        const val POLICY_BLOCK_ALL = "block_all"
        const val POLICY_ALLOW_ALL = "allow_all"

        const val DEFAULT_SHOW_APP_ICONS = true
        const val DEFAULT_FIREWALL_POLICY = POLICY_ALLOW_ALL
        const val DEFAULT_FIREWALL_ENABLED = false
        const val DEFAULT_NEW_APP_NOTIFICATIONS = true

    }

    object Permissions {
        const val QUERY_ALL_PACKAGES_PERMISSION = "android.permission.QUERY_ALL_PACKAGES"
        const val WRITE_SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
        const val CHANGE_COMPONENT_ENABLED_STATE_PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
        const val KILL_BACKGROUND_PROCESSES_PERMISSION = "android.permission.KILL_BACKGROUND_PROCESSES"
        const val GET_TASKS_PERMISSION = "android.permission.GET_TASKS"
        const val REQUEST_DELETE_PACKAGES_PERMISSION = "android.permission.REQUEST_DELETE_PACKAGES"
        const val ACCESS_SUPERUSER_PERMISSION = "android.permission.ACCESS_SUPERUSER"
        const val SYSTEM_ALERT_WINDOW_PERMISSION = "android.permission.SYSTEM_ALERT_WINDOW"
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

        const val ROOTING_INSTRUCTIONS_INTRO = "Your device needs to be rooted to use advanced package management features."
        const val ROOTING_TOOLS_TITLE = "<b>Recommended rooting tools:</b>"
        const val ROOTING_TOOLS_BODY = "• Magisk - Most popular and widely supported root solution. Works on Android 5.0+ and supports modules for additional features.\n• KernelSU - Modern kernel-based root management. Provides better security isolation and doesn't modify system partition.\n• APatch - Newer alternative with kernel patching approach. Good for devices with strict security policies."
        const val ROOTING_WARNING = "⚠️ Important: Rooting your device requires unlocking the bootloader. Research your specific device model before proceeding."

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

        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        const val GUIDE_URL = "https://shizuku.rikka.app/guide/setup/"

        const val SETUP_INSTRUCTIONS = """To enable package management with Shizuku:

1. Install Shizuku from the link below
2. Start Shizuku (requires ADB or root)
3. Grant Shizuku permission to De1984
4. Use advanced package operations"""

        const val WHAT_IS_SHIZUKU = "Shizuku allows apps to use system APIs with elevated privileges (ADB or root). It's a safer alternative to traditional root access for package management."
    }

    object Firewall {
        const val STATE_BLOCKED = "Blocked"
        const val STATE_ALLOWED = "Allowed"

        val PACKAGE_TYPE_FILTERS = listOf("User", "System")
        val NETWORK_STATE_FILTERS = listOf("Allowed", "Blocked")

        val NETWORK_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE"
        )

        const val TITLE = "Firewall"
        const val SUBTITLE = "Network Access Control"
        const val BLOCK_ACTION = "Block"
        const val ALLOW_ACTION = "Allow"
        const val BLOCK_DESCRIPTION = "Block all network access (WiFi & Mobile Data)"
        const val ALLOW_DESCRIPTION = "Allow network access via WiFi & Mobile Data"

        const val BLOCK_CONFIRMATION_TITLE = "Block Network Access"
        const val ALLOW_CONFIRMATION_TITLE = "Allow Network Access"
        const val SYSTEM_PACKAGE_WARNING = "⚠️ System Package Warning"
    }

}
