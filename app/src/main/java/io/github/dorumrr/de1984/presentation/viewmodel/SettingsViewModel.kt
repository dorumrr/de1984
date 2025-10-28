package io.github.dorumrr.de1984.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.service.FirewallVpnService

import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.shizukuStatus


    
    init {
        loadSettings()
        loadSystemInfo()
        cleanupOrphanedPreferences()
        requestRootPermission() // Check root status on initialization
        requestShizukuPermission() // Check Shizuku status on initialization
    }


    fun requestRootPermission() {
        viewModelScope.launch {
            rootManager.checkRootStatus()
        }
    }

    fun hasRequestedRootPermission(): Boolean {
        return rootManager.hasRequestedRootPermission()
    }

    fun markRootPermissionRequested() {
        rootManager.markRootPermissionRequested()
    }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            shizukuManager.checkShizukuStatus()
        }
    }

    fun grantShizukuPermission() {
        shizukuManager.requestShizukuPermission()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)

            _uiState.value = _uiState.value.copy(
                autoRefresh = prefs.getBoolean("auto_refresh", true),
                showSystemApps = prefs.getBoolean("show_system_apps", false),
                darkTheme = prefs.getBoolean("dark_theme", false),
                refreshInterval = prefs.getInt("refresh_interval", 30),
                showAppIcons = prefs.getBoolean(Constants.Settings.KEY_SHOW_APP_ICONS, Constants.Settings.DEFAULT_SHOW_APP_ICONS),
                defaultFirewallPolicy = prefs.getString(
                    Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                    Constants.Settings.DEFAULT_FIREWALL_POLICY
                ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY,
                newAppNotifications = prefs.getBoolean(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS)

            )
        }
    }
    
    private fun loadSystemInfo() {
        viewModelScope.launch {
            val systemCapabilities = permissionManager.getSystemCapabilities()

            val systemInfo = SystemInfo(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                androidROM = getAndroidROMInfo(),
                hasRoot = false,
                architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
            )

            _uiState.value = _uiState.value.copy(
                systemInfo = systemInfo,
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                hasBasicPermissions = systemCapabilities.hasBasicPermissions,
                hasEnhancedPermissions = true,
                hasAdvancedPermissions = false
            )
        }
    }

    private fun getAndroidROMInfo(): String {
        return try {
            val displayInfo = Build.DISPLAY
            when {
                displayInfo.contains("lineage", ignoreCase = true) -> displayInfo
                displayInfo.contains("pixel", ignoreCase = true) -> "Pixel Experience"
                displayInfo.isNotBlank() -> displayInfo
                else -> "Stock Android"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun saveSetting(key: String, value: Any) {
        val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
        }

        editor.apply()
    }
    
    fun setAutoRefresh(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoRefresh = enabled)
        saveSetting("auto_refresh", enabled)
    }
    
    fun setShowSystemApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
        saveSetting("show_system_apps", show)
    }
    
    fun setDarkTheme(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkTheme = enabled)
        saveSetting("dark_theme", enabled)
    }

    fun setShowAppIcons(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAppIcons = show)
        saveSetting(Constants.Settings.KEY_SHOW_APP_ICONS, show)
    }

    fun setDefaultFirewallPolicy(policy: String) {
        _uiState.value = _uiState.value.copy(defaultFirewallPolicy = policy)
        saveSetting(Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY, policy)

        viewModelScope.launch {
            restartFirewallIfRunning()
        }
    }

    fun setNewAppNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(newAppNotifications = enabled)
        saveSetting(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, enabled)
    }

    private suspend fun restartFirewallIfRunning() {
        try {
            val stopIntent = Intent(context, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_STOP
            }
            context.startService(stopIntent)

            delay(500)

            val startIntent = Intent(context, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_START
            }
            context.startService(startIntent)
        } catch (e: Exception) {
        }
    }

    fun setRefreshInterval(interval: Int) {
        _uiState.value = _uiState.value.copy(refreshInterval = interval)
        saveSetting("refresh_interval", interval)
    }
    

    
    fun showLicenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                message = "Open source licenses screen coming soon"
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * One-time cleanup of orphaned update-related preferences.
     * This method removes preferences that are no longer used after update system removal.
     */
    private fun cleanupOrphanedPreferences() {
        val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("auto_check_updates")
            .remove("last_update_check")
            .remove("last_update_check_result")
            .remove("last_update_version")
            .remove("last_update_url")
            .remove("last_update_notes")
            .remove("last_update_error")
            .apply()
    }

    class Factory(
        private val context: Context,
        private val permissionManager: PermissionManager,
        private val rootManager: RootManager,
        private val shizukuManager: ShizukuManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(
                    context,
                    permissionManager,
                    rootManager,
                    shizukuManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class SettingsUiState(
    val autoRefresh: Boolean = true,
    val showSystemApps: Boolean = false,
    val darkTheme: Boolean = false,
    val showAppIcons: Boolean = true,
    val defaultFirewallPolicy: String = Constants.Settings.DEFAULT_FIREWALL_POLICY,
    val newAppNotifications: Boolean = Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS,

    val refreshInterval: Int = 30,

    val systemInfo: SystemInfo = SystemInfo(
        deviceModel = "Unknown",
        androidVersion = "Unknown",
        androidROM = "Unknown",
        hasRoot = false,
        architecture = "Unknown"
    ),

    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildNumber: String = BuildConfig.VERSION_CODE.toString(),

    val isLoading: Boolean = false,
    val message: String? = null,

    val hasBasicPermissions: Boolean = true,
    val hasEnhancedPermissions: Boolean = false,
    val hasAdvancedPermissions: Boolean = false
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val androidROM: String,
    val hasRoot: Boolean,
    val architecture: String
)


