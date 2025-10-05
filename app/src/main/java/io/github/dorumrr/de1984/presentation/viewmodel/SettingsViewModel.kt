package io.github.dorumrr.de1984.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.data.updater.UpdateChecker
import io.github.dorumrr.de1984.data.updater.UpdateResult
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager,
    private val rootManager: RootManager,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus

    private val _updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()
    
    init {
        loadSettings()
        loadSystemInfo()
        loadCachedUpdateResult()
    }
    
    private fun loadCachedUpdateResult() {
        viewModelScope.launch {
            updateChecker.getLastCheckResult()?.let { result ->
                when (result) {
                    is UpdateResult.Available -> {
                        _updateCheckState.value = UpdateCheckState.Available(
                            version = result.version,
                            downloadUrl = result.downloadUrl,
                            releaseNotes = result.releaseNotes
                        )
                    }
                    else -> {
                        // Don't show cached error or up-to-date states
                    }
                }
            }
        }
    }

    fun requestRootPermission() {
        viewModelScope.launch {
            rootManager.checkRootStatus()
        }
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
                newAppNotifications = prefs.getBoolean(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS),
                autoCheckUpdates = prefs.getBoolean("auto_check_updates", true)
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

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckState.Checking

            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.Available -> {
                    _updateCheckState.value = UpdateCheckState.Available(
                        version = result.version,
                        downloadUrl = result.downloadUrl,
                        releaseNotes = result.releaseNotes
                    )
                }
                is UpdateResult.UpToDate -> {
                    _updateCheckState.value = UpdateCheckState.UpToDate
                }
                is UpdateResult.Error -> {
                    _updateCheckState.value = UpdateCheckState.Error(result.message)
                }
                is UpdateResult.NotApplicable -> {
                    // Should not happen in self-distributed builds
                    _updateCheckState.value = UpdateCheckState.Idle
                }
            }
        }
    }

    fun resetUpdateCheckState() {
        _updateCheckState.value = UpdateCheckState.Idle
    }

    fun refreshUpdateCheckState() {
        viewModelScope.launch {
            updateChecker.getLastCheckResult()?.let { result ->
                when (result) {
                    is UpdateResult.Available -> {
                        _updateCheckState.value = UpdateCheckState.Available(
                            version = result.version,
                            downloadUrl = result.downloadUrl,
                            releaseNotes = result.releaseNotes
                        )
                    }
                    is UpdateResult.UpToDate -> {
                        _updateCheckState.value = UpdateCheckState.UpToDate
                    }
                    is UpdateResult.Error -> {
                        _updateCheckState.value = UpdateCheckState.Error(result.message)
                    }
                    else -> {
                        // Keep current state for NotApplicable
                    }
                }
            }
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("auto_check_updates", enabled).apply()
            _uiState.value = _uiState.value.copy(autoCheckUpdates = enabled)
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
    val hasAdvancedPermissions: Boolean = false,

    val autoCheckUpdates: Boolean = true
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val androidROM: String,
    val hasRoot: Boolean,
    val architecture: String
)

sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    ) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}
