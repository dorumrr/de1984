package io.github.dorumrr.de1984.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.data.common.CaptivePortalManager
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.domain.model.CaptivePortalMode
import io.github.dorumrr.de1984.domain.model.CaptivePortalPreset
import io.github.dorumrr.de1984.domain.model.CaptivePortalSettings
import io.github.dorumrr.de1984.domain.model.FirewallRulesBackup
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val firewallManager: FirewallManager,
    private val firewallRepository: FirewallRepository,
    private val captivePortalManager: CaptivePortalManager
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.shizukuStatus
    val activeBackendType: StateFlow<FirewallBackendType?> = firewallManager.activeBackendType


    
    init {
        loadSettings()
        loadSystemInfo()
        cleanupOrphanedPreferences()
        requestRootPermission()
        requestShizukuPermission()
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

            val status = shizukuManager.shizukuStatus.value
            if (status == ShizukuStatus.RUNNING_NO_PERMISSION) {
                shizukuManager.requestShizukuPermission()
            }
        }
    }

    fun grantShizukuPermission() {
        shizukuManager.requestShizukuPermission()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)

            val firewallModeString = prefs.getString(
                Constants.Settings.KEY_FIREWALL_MODE,
                Constants.Settings.DEFAULT_FIREWALL_MODE
            ) ?: Constants.Settings.DEFAULT_FIREWALL_MODE

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
                appLanguage = prefs.getString(Constants.Settings.KEY_APP_LANGUAGE, Constants.Settings.DEFAULT_APP_LANGUAGE) ?: Constants.Settings.DEFAULT_APP_LANGUAGE,
                firewallMode = FirewallMode.fromString(firewallModeString) ?: FirewallMode.AUTO,
                allowCriticalPackageUninstall = prefs.getBoolean(Constants.Settings.KEY_ALLOW_CRITICAL_UNINSTALL, Constants.Settings.DEFAULT_ALLOW_CRITICAL_UNINSTALL),
                showFirewallStartPrompt = prefs.getBoolean(Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT, Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT),
                useDynamicColors = prefs.getBoolean(Constants.Settings.KEY_USE_DYNAMIC_COLORS, Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS)
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
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
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

    fun setAppLanguage(languageCode: String) {
        _uiState.value = _uiState.value.copy(appLanguage = languageCode)
        saveSetting(Constants.Settings.KEY_APP_LANGUAGE, languageCode)
    }

    /**
     * Change the default firewall policy.
     * Rules are preserved and interpreted based on the new policy.
     */
    fun setDefaultFirewallPolicy(newPolicy: String) {
        val oldPolicy = _uiState.value.defaultFirewallPolicy
        Log.d(TAG, "setDefaultFirewallPolicy: oldPolicy=$oldPolicy, newPolicy=$newPolicy")

        // If policy is the same, do nothing
        if (oldPolicy == newPolicy) {
            Log.d(TAG, "setDefaultFirewallPolicy: Policy unchanged, skipping")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "setDefaultFirewallPolicy: Updating uiState and saving to SharedPreferences")
                _uiState.value = _uiState.value.copy(
                    defaultFirewallPolicy = newPolicy
                )
                saveSetting(Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY, newPolicy)
                Log.d(TAG, "setDefaultFirewallPolicy: uiState updated to: ${_uiState.value.defaultFirewallPolicy}")

                if (firewallManager.isActive()) {
                    Log.d(TAG, "setDefaultFirewallPolicy: Firewall active, triggering rule reapplication")
                    firewallManager.triggerRuleReapplication()

                    val intent = Intent("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED")
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                    Log.d(TAG, "setDefaultFirewallPolicy: Broadcast sent")
                } else {
                    Log.d(TAG, "setDefaultFirewallPolicy: Firewall not active, skipping rule reapplication")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change policy", e)
            }
        }
    }

    fun setNewAppNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(newAppNotifications = enabled)
        saveSetting(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, enabled)
    }

    fun setAllowCriticalPackageUninstall(allow: Boolean) {
        _uiState.value = _uiState.value.copy(allowCriticalPackageUninstall = allow)
        saveSetting(Constants.Settings.KEY_ALLOW_CRITICAL_UNINSTALL, allow)
    }

    fun setShowFirewallStartPrompt(show: Boolean) {
        _uiState.value = _uiState.value.copy(showFirewallStartPrompt = show)
        saveSetting(Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT, show)
    }

    fun setUseDynamicColors(enabled: Boolean, showRestartDialog: Boolean = false) {
        _uiState.value = _uiState.value.copy(useDynamicColors = enabled, requiresRestart = showRestartDialog)
        saveSetting(Constants.Settings.KEY_USE_DYNAMIC_COLORS, enabled)
    }

    fun clearRestartPrompt() {
        _uiState.value = _uiState.value.copy(requiresRestart = false)
    }

    fun setFirewallMode(mode: FirewallMode) {
        _uiState.value = _uiState.value.copy(firewallMode = mode)
        firewallManager.setMode(mode)

        // Restart firewall if running to apply new mode
        viewModelScope.launch {
            if (firewallManager.isActive()) {
                restartFirewallIfRunning()
            }
        }
    }

    fun checkIptablesAvailability(callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val available = firewallManager.isIptablesAvailable()
            callback(available)
        }
    }

    fun isShizukuRootMode(): Boolean {
        return shizukuManager.isShizukuRootMode()
    }

    private suspend fun restartFirewallIfRunning() {
        try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

            if (!isFirewallEnabled) {
                return
            }

            firewallManager.stopFirewall()
            delay(500)

            val newMode = _uiState.value.firewallMode
            val result = firewallManager.startFirewall(newMode)

            result.onFailure { error ->
                // Update SharedPreferences to reflect firewall is stopped
                prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false).apply()

                // Show error to user
                _uiState.value = _uiState.value.copy(
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_firewall_restart_failed, error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart firewall", e)
        }
    }

    fun setRefreshInterval(interval: Int) {
        _uiState.value = _uiState.value.copy(refreshInterval = interval)
        saveSetting("refresh_interval", interval)
    }
    

    
    fun showLicenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                message = context.getString(io.github.dorumrr.de1984.R.string.licenses_coming_soon)
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Backup firewall rules to a JSON file.
     */
    fun backupRules(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                // Get all rules
                val rules = firewallRepository.getAllRules().first()

                if (rules.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_no_rules_to_backup)
                    )
                    return@launch
                }

                // Create backup object
                val backup = FirewallRulesBackup(
                    version = 1,
                    exportDate = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    rulesCount = rules.size,
                    rules = rules
                )

                // Serialize to JSON
                val json = Json.encodeToString(FirewallRulesBackup.serializer(), backup)

                // Write to file
                writeToUri(uri, json)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "✅ Backup successful: ${rules.size} rules saved"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_backup_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    /**
     * Restore firewall rules from a JSON file.
     * @param uri URI of the backup file
     * @param replaceExisting If true, delete all existing rules before restoring
     */
    fun restoreRules(uri: Uri, replaceExisting: Boolean) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                // Read JSON from file
                val json = readFromUri(uri)

                // Parse JSON
                val backup = Json.decodeFromString<FirewallRulesBackup>(json)

                // Validate version
                if (backup.version > 1) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_unsupported_backup_version, backup.version)
                    )
                    return@launch
                }

                // Validate rules
                if (backup.rules.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_backup_no_rules)
                    )
                    return@launch
                }

                // Replace existing rules if requested
                if (replaceExisting) {
                    firewallRepository.deleteAllRules()
                }

                // Insert rules (REPLACE strategy handles duplicates)
                firewallRepository.insertRules(backup.rules)

                val action = if (replaceExisting) "restored" else "merged"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "✅ Rules $action successfully: ${backup.rules.size} rules"
                )
            } catch (e: SerializationException) {
                Log.e(TAG, "Invalid backup file format", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_invalid_backup_format)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_restore_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    /**
     * Parse a backup file and return its contents for preview.
     */
    suspend fun parseBackupFile(uri: Uri): Result<FirewallRulesBackup> {
        return withContext(Dispatchers.IO) {
            try {
                val json = readFromUri(uri)
                val backup = Json.decodeFromString<FirewallRulesBackup>(json)
                Result.success(backup)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse backup file", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Write content to a URI using ContentResolver.
     */
    private suspend fun writeToUri(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray())
        } ?: throw IOException("Failed to open output stream")
    }

    /**
     * Read content from a URI using ContentResolver.
     */
    private suspend fun readFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().readText()
        } ?: throw IOException("Failed to open input stream")
    }

    /**
     * Get current date in yyyy-MM-dd format for backup filenames.
     */
    fun getCurrentDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
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

    // =============================================================================================
    // Captive Portal Controller
    // =============================================================================================

    /**
     * Load current captive portal settings from the system.
     * Also captures original settings if not already captured.
     */
    fun loadCaptivePortalSettings() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                // Capture original settings if not already captured
                if (!captivePortalManager.hasOriginalSettings()) {
                    captivePortalManager.captureOriginalSettings()
                }

                // Load current settings
                val result = captivePortalManager.getCurrentSettings()
                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        captivePortalSettings = result.getOrNull(),
                        captivePortalOriginalCaptured = captivePortalManager.hasOriginalSettings(),
                        captivePortalHasPrivileges = captivePortalManager.hasPrivileges(),
                        captivePortalLoading = false,
                        captivePortalError = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_load_settings)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load captive portal settings", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    /**
     * Apply a captive portal server preset.
     */
    fun applyCaptivePortalPreset(preset: CaptivePortalPreset) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.applyPreset(preset)
                if (result.isSuccess) {
                    // Reload settings to reflect changes
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_applied, preset.getDisplayName(context))
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_apply_preset)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply preset", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    /**
     * Set captive portal detection mode.
     */
    fun setCaptivePortalDetectionMode(mode: CaptivePortalMode) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.setDetectionMode(mode)
                if (result.isSuccess) {
                    // Reload settings to reflect changes
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_set, mode.getDisplayName(context))
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_set_detection_mode)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set detection mode", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    /**
     * Set custom captive portal URLs.
     */
    fun setCustomCaptivePortalUrls(httpUrl: String, httpsUrl: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.setCustomUrls(httpUrl, httpsUrl)
                if (result.isSuccess) {
                    // Reload settings to reflect changes
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.success_custom_urls_applied)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_set_custom_urls)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set custom URLs", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    /**
     * Restore original captive portal settings.
     */
    fun restoreOriginalCaptivePortalSettings() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.restoreOriginalSettings()
                if (result.isSuccess) {
                    // Reload settings to reflect changes
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.success_original_settings_restored)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_restore_original_settings)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore original settings", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    /**
     * Reset to Google's default captive portal settings.
     */
    fun resetCaptivePortalToGoogleDefaults() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.resetToGoogleDefaults()
                if (result.isSuccess) {
                    // Reload settings to reflect changes
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.success_reset_to_google_defaults)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_reset_to_google_defaults)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset to Google defaults", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    class Factory(
        private val context: Context,
        private val permissionManager: PermissionManager,
        private val rootManager: RootManager,
        private val shizukuManager: ShizukuManager,
        private val firewallManager: FirewallManager,
        private val firewallRepository: FirewallRepository,
        private val captivePortalManager: CaptivePortalManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(
                    context,
                    permissionManager,
                    rootManager,
                    shizukuManager,
                    firewallManager,
                    firewallRepository,
                    captivePortalManager
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
    val firewallMode: FirewallMode = FirewallMode.AUTO,
    val allowCriticalPackageUninstall: Boolean = Constants.Settings.DEFAULT_ALLOW_CRITICAL_UNINSTALL,
    val showFirewallStartPrompt: Boolean = Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT,
    val useDynamicColors: Boolean = Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS,
    val appLanguage: String = Constants.Settings.DEFAULT_APP_LANGUAGE,

    val refreshInterval: Int = 30,

    val requiresRestart: Boolean = false,

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
    val error: String? = null,

    val hasBasicPermissions: Boolean = true,
    val hasEnhancedPermissions: Boolean = false,
    val hasAdvancedPermissions: Boolean = false,

    // Captive Portal Controller
    val captivePortalSettings: CaptivePortalSettings? = null,
    val captivePortalOriginalCaptured: Boolean = false,
    val captivePortalHasPrivileges: Boolean = false,
    val captivePortalLoading: Boolean = false,
    val captivePortalError: String? = null
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val androidROM: String,
    val hasRoot: Boolean,
    val architecture: String
)


