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
import io.github.dorumrr.de1984.data.common.BootProtectionManager
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
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.domain.usecase.SmartPolicySwitchUseCase
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageSafetyLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    private val captivePortalManager: CaptivePortalManager,
    private val bootProtectionManager: BootProtectionManager,
    private val smartPolicySwitchUseCase: SmartPolicySwitchUseCase,
    private val packageRepository: PackageRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(loadInitialSettings())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadInitialSettings(): SettingsUiState {
        val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
        val firewallModeString = prefs.getString(
            Constants.Settings.KEY_FIREWALL_MODE,
            Constants.Settings.DEFAULT_FIREWALL_MODE
        ) ?: Constants.Settings.DEFAULT_FIREWALL_MODE

        return SettingsUiState(
            showAppIcons = prefs.getBoolean(Constants.Settings.KEY_SHOW_APP_ICONS, Constants.Settings.DEFAULT_SHOW_APP_ICONS),
            defaultFirewallPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY,
            newAppNotifications = prefs.getBoolean(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS),
            bootProtection = prefs.getBoolean(Constants.Settings.KEY_BOOT_PROTECTION, Constants.Settings.DEFAULT_BOOT_PROTECTION),
            appLanguage = prefs.getString(Constants.Settings.KEY_APP_LANGUAGE, Constants.Settings.DEFAULT_APP_LANGUAGE) ?: Constants.Settings.DEFAULT_APP_LANGUAGE,
            firewallMode = FirewallMode.fromString(firewallModeString) ?: FirewallMode.AUTO,
            allowCriticalPackageUninstall = prefs.getBoolean(Constants.Settings.KEY_ALLOW_CRITICAL_UNINSTALL, Constants.Settings.DEFAULT_ALLOW_CRITICAL_UNINSTALL),
            allowCriticalPackageFirewall = prefs.getBoolean(Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL, Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL),
            showFirewallStartPrompt = prefs.getBoolean(Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT, Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT),
            confirmRuleChanges = prefs.getBoolean(Constants.Settings.KEY_CONFIRM_RULE_CHANGES, Constants.Settings.DEFAULT_CONFIRM_RULE_CHANGES),
            confirmFirewallStop = prefs.getBoolean(Constants.Settings.KEY_CONFIRM_FIREWALL_STOP, Constants.Settings.DEFAULT_CONFIRM_FIREWALL_STOP),
            useDynamicColors = prefs.getBoolean(Constants.Settings.KEY_USE_DYNAMIC_COLORS, Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS)
        )
    }

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.shizukuStatus
    val activeBackendType: StateFlow<FirewallBackendType?> = firewallManager.activeBackendType

    /**
     * Modes the device can genuinely run, as reported by the backends themselves.
     *
     * null until the first probe finishes - the picker treats that as "no opinion yet" and falls
     * back to the privilege-only guess, so the list is never empty on first draw.
     */
    private val _usableModes = MutableStateFlow<Set<FirewallMode>?>(null)
    val usableModes: StateFlow<Set<FirewallMode>?> = _usableModes.asStateFlow()

    /**
     * Modes whose availability probe passed but whose start then failed, this session.
     *
     * Re-probing cannot discover these: checkAvailability() already said yes, which is how the mode
     * reached the picker at all. Without remembering it, a user can pick a backend that always
     * fails, get dropped to AUTO, and pick it again forever. Deliberately not persisted - a start
     * can fail for reasons that pass, so the slate clears with the process.
     */
    private val _startFailedModes = MutableStateFlow<Set<FirewallMode>>(emptySet())
    val startFailedModes: StateFlow<Set<FirewallMode>> = _startFailedModes.asStateFlow()

    /**
     * Only ever one probe in flight.
     *
     * Four callers can fire this, and both status flows replay their current value to a new
     * collector, so opening Settings used to launch three overlapping passes before any privilege
     * had even resolved. Worse than wasteful: with no ordering, a pass started BEFORE root was
     * revoked can land after the one started after, leaving the picker offering a backend the
     * device no longer has - the exact failure this probe exists to prevent.
     */
    private var usableModesJob: Job? = null

    fun refreshUsableModes() {
        usableModesJob?.cancel()
        usableModesJob = viewModelScope.launch {
            val modes = firewallManager.getUsableModes()
            // cancel() alone is not enough. Writing a StateFlow is not a suspension point, so a
            // pass that was cancelled while its last shell call was in flight would still publish
            // its answer - and an answer computed before root resolved says [AUTO, VPN], which
            // greys out iptables on a rooted device. ensureActive() makes a cancelled pass throw
            // here instead of overwriting a newer one.
            ensureActive()
            _usableModes.value = modes
        }
    }



    init {
        // Note: Settings are loaded synchronously in loadInitialSettings() to avoid
        // emitting default values first, which would cause observers to see a "change"
        loadSystemInfo()
        cleanupOrphanedPreferences()
        requestRootPermission()
        requestShizukuPermission()
        refreshUsableModes()

        viewModelScope.launch {
            rootStatus.collect {
                AppLogger.d(TAG, "Root status changed: $it, re-checking boot protection availability")
                checkBootProtectionAvailability()
                updateCaptivePortalPrivileges()
                // Gaining or losing root changes which backends can run at all, and a start that
                // failed for want of root deserves another go once root is back.
                _startFailedModes.value = emptySet()
                refreshUsableModes()
            }
        }

        viewModelScope.launch {
            shizukuStatus.collect {
                AppLogger.d(TAG, "Shizuku status changed: $it, re-checking boot protection availability")
                checkBootProtectionAvailability()
                updateCaptivePortalPrivileges()
                _startFailedModes.value = emptySet()
                refreshUsableModes()
            }
        }

        viewModelScope.launch {
            firewallManager.currentMode.collect { mode ->
                AppLogger.d(TAG, "🔄 Firewall mode changed externally: $mode")
                if (_uiState.value.firewallMode != mode) {
                    AppLogger.d(TAG, "🔄 Updating UI mode from ${_uiState.value.firewallMode} to $mode")
                    _uiState.value = _uiState.value.copy(firewallMode = mode)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        AppLogger.d(TAG, "SettingsViewModel cleared - all coroutines will be cancelled")
    }

    private fun updateCaptivePortalPrivileges() {
        _uiState.value = _uiState.value.copy(
            captivePortalHasPrivileges = captivePortalManager.hasPrivileges()
        )
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
            // Skip if user already denied to prevent prompt spam (Issue #68)
            // This is called from init{}, so only auto-request if user hasn't denied
            if (status == ShizukuStatus.RUNNING_NO_PERMISSION && !shizukuManager.hasUserDeniedPermission) {
                shizukuManager.requestShizukuPermission()
            }
        }
    }

    fun grantShizukuPermission() {
        shizukuManager.resetPermissionDenial()
        shizukuManager.requestShizukuPermission()
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
    
    private fun saveSetting(key: String, value: Any, durable: Boolean = false) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
        }

        if (durable) {
            // Synchronous on purpose: setBootProtection reboots the device a few lines after calling
            // this, and an async write could simply not land. Callers are responsible for being off
            // the main thread - see the withContext wrappers at the boot-protection call sites.
            @Suppress("ApplySharedPref")
            editor.commit()
        } else {
            editor.apply()
        }
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
     * Change the default firewall policy with smart handling of system-critical packages.
     *
     * When allowCriticalPackageFirewall is ON:
     * - Preserves existing user preferences for critical packages
     * - Defaults critical packages to ALLOW (if no user preference exists) to ensure system stability
     * - Applies normal policy to non-critical packages
     *
     * When allowCriticalPackageFirewall is OFF:
     * - Uses standard policy switching (critical packages are protected by backend logic anyway)
     */
    fun setDefaultFirewallPolicy(newPolicy: String) {
        val oldPolicy = _uiState.value.defaultFirewallPolicy
        AppLogger.d(TAG, "setDefaultFirewallPolicy: oldPolicy=$oldPolicy, newPolicy=$newPolicy")

        if (oldPolicy == newPolicy) {
            AppLogger.d(TAG, "setDefaultFirewallPolicy: Policy unchanged, skipping")
            return
        }

        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "setDefaultFirewallPolicy: Updating uiState and saving to SharedPreferences")
                _uiState.value = _uiState.value.copy(
                    defaultFirewallPolicy = newPolicy
                )
                saveSetting(Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY, newPolicy)
                AppLogger.d(TAG, "setDefaultFirewallPolicy: uiState updated to: ${_uiState.value.defaultFirewallPolicy}")

                AppLogger.d(TAG, "setDefaultFirewallPolicy: Applying smart policy switch")
                when (newPolicy) {
                    Constants.Settings.POLICY_BLOCK_ALL -> {
                        smartPolicySwitchUseCase.switchToBlockAll()
                    }
                    Constants.Settings.POLICY_ALLOW_ALL -> {
                        smartPolicySwitchUseCase.switchToAllowAll()
                    }
                }

                if (firewallManager.isActive()) {
                    AppLogger.d(TAG, "setDefaultFirewallPolicy: Firewall active, triggering rule reapplication")
                    firewallManager.triggerRuleReapplication()

                    val intent = Intent("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED")
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                    AppLogger.d(TAG, "setDefaultFirewallPolicy: Broadcast sent")
                } else {
                    AppLogger.d(TAG, "setDefaultFirewallPolicy: Firewall not active, skipping rule reapplication")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to change policy", e)
            }
        }
    }

    fun setNewAppNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(newAppNotifications = enabled)
        saveSetting(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, enabled)
    }

    fun setBootProtection(enabled: Boolean) {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "setBootProtection: enabled=$enabled")

                val result = bootProtectionLock.withLock { bootProtectionManager.setBootProtection(enabled) }

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(bootProtection = enabled)
                    // Durable, not apply(): the device is rebooted a few lines below. Losing this
                    // write would leave the preference disagreeing with the boot script actually on
                    // disk - the exact mismatch this feature exists to prevent.
                    withContext(Dispatchers.IO) { saveSetting(Constants.Settings.KEY_BOOT_PROTECTION, enabled, durable = true) }

                    AppLogger.d(TAG, "✅ Boot protection ${if (enabled) "enabled" else "disabled"} successfully")

                    // Reboot immediately. The user already confirmed in the warning dialog, which is
                    // the only gate: no second prompt, no delay, no message they would never see.
                    // This guarantees the on-disk script and the live iptables state always agree.
                    // Only reached after a verified successful change - never after a failure.
                    val rebootResult = bootProtectionManager.rebootDevice()
                    if (rebootResult.isFailure) {
                        AppLogger.e(TAG, "❌ Reboot failed after boot protection change", rebootResult.exceptionOrNull())
                        _uiState.value = _uiState.value.copy(
                            error = context.getString(io.github.dorumrr.de1984.R.string.boot_protection_reboot_failed)
                        )
                    }
                } else {
                    val errorMessage = if (enabled) {
                        context.getString(io.github.dorumrr.de1984.R.string.boot_protection_enable_failed, result.exceptionOrNull()?.message ?: "Unknown error")
                    } else {
                        context.getString(io.github.dorumrr.de1984.R.string.boot_protection_disable_failed, result.exceptionOrNull()?.message ?: "Unknown error")
                    }
                    _uiState.value = _uiState.value.copy(error = errorMessage)

                    AppLogger.e(TAG, "❌ Failed to ${if (enabled) "enable" else "disable"} boot protection", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception in setBootProtection", e)
                _uiState.value = _uiState.value.copy(error = e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Lockout scenario 5: the switch is greyed out and the script is still installed.
     *
     * Asks Magisk for root again and, if it comes back, removes the script. Reuses
     * [BootProtectionManager.retryRemoveBootProtection], which reuses `deleteBootScript` - so the
     * read-back check and the live-chain teardown are the same ones the normal disable path uses.
     *
     * On success the device restarts, for the same reason the normal toggles do: deleting the file
     * does not clear the chain already live in THIS boot, and calling `resetIptablesPolicies()`
     * separately would be a second way to undo the same thing. One rule, no exception to remember.
     *
     * Unlike the toggles, this one shows a "Restarting..." screen first. The toggles are reached
     * through a warning dialog that already says the device will restart; this button is pressed by
     * someone whose phone is already misbehaving, and a screen going black with no explanation is
     * the wrong thing to hand them.
     */
    fun retryRemoveBootProtection() {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "retryRemoveBootProtection: attempting recovery from a privilege loss")
                _uiState.value = _uiState.value.copy(
                    bootProtectionRemovalInProgress = true,
                    error = null
                )

                val result = bootProtectionLock.withLock { bootProtectionManager.retryRemoveBootProtection() }

                if (result.isSuccess) {
                    // Durable, not apply(): the device is restarted a few lines below, and losing
                    // this write would leave the preference disagreeing with what is on disk.
                    withContext(Dispatchers.IO) {
                        saveSetting(Constants.Settings.KEY_BOOT_PROTECTION, false, durable = true)
                    }
                    _uiState.value = _uiState.value.copy(
                        bootProtection = false,
                        bootProtectionRemovalInProgress = false,
                        isRebooting = true
                    )
                    AppLogger.d(TAG, "✅ Boot protection removed on retry - restarting")

                    val rebootResult = bootProtectionManager.rebootDevice()
                    if (rebootResult.isFailure) {
                        AppLogger.e(TAG, "❌ Reboot failed after removing boot protection", rebootResult.exceptionOrNull())
                        _uiState.value = _uiState.value.copy(
                            isRebooting = false,
                            error = context.getString(io.github.dorumrr.de1984.R.string.boot_protection_reboot_failed)
                        )
                    }
                } else {
                    val cause = result.exceptionOrNull()
                    val message = if (cause is BootProtectionManager.NoPrivilegeException) {
                        // Do not blame the deletion. Root is genuinely gone, and no button in this
                        // app can delete a file under /data/adb without it.
                        context.getString(io.github.dorumrr.de1984.R.string.boot_protection_remove_no_root)
                    } else {
                        context.getString(
                            io.github.dorumrr.de1984.R.string.boot_protection_disable_failed,
                            cause?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        bootProtectionRemovalInProgress = false,
                        error = message
                    )
                    AppLogger.e(TAG, "❌ Retry removal failed: $message", cause)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception in retryRemoveBootProtection", e)
                _uiState.value = _uiState.value.copy(
                    bootProtectionRemovalInProgress = false,
                    error = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    /**
     * Serialises boot-protection reads and writes.
     *
     * Both the toggle and the disk reconciliation suspend on IO, so without this they interleave: the
     * reconciliation reads the disk, the user confirms the toggle, the toggle writes script and
     * preference, then the reconciliation resumes with its stale reading and commits the opposite -
     * leaving a script installed with the preference saying it is not.
     */
    private val bootProtectionLock = kotlinx.coroutines.sync.Mutex()

    fun checkBootProtectionAvailability() {
        AppLogger.d(TAG, "checkBootProtectionAvailability() called")
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "Checking boot protection availability...")

                // Check if root/Shizuku is available
                // Must match BootProtectionManager.hasBootProtectionPrivilege(). ADB-mode Shizuku runs
                // as uid 2000 and can touch neither iptables nor /data/adb, so counting it here made
                // the reconciliation below run with a check that always fails - writing "not
                // installed" over a device that is blocked at every boot.
                val hasPrivileges = rootManager.hasRootPermission ||
                    (shizukuManager.hasShizukuPermission && shizukuManager.isShizukuRootMode())
                AppLogger.d(TAG, "hasPrivileges: $hasPrivileges (root=${rootManager.hasRootPermission}, shizuku=${shizukuManager.hasShizukuPermission})")

                val hasBootScriptSupport = if (hasPrivileges) {
                    AppLogger.d(TAG, "Checking boot script support availability...")
                    bootProtectionManager.isBootScriptSupportAvailable()
                } else {
                    AppLogger.d(TAG, "No privileges, skipping boot script support check")
                    false
                }

                val available = hasPrivileges && hasBootScriptSupport

                AppLogger.d(TAG, "Boot protection availability: hasPrivileges=$hasPrivileges, hasBootScriptSupport=$hasBootScriptSupport, available=$available")

                _uiState.value = _uiState.value.copy(bootProtectionAvailable = available)
                AppLogger.d(TAG, "Updated UI state with bootProtectionAvailable=$available")

                // The switch used to read only the preference. Clearing app data resets that
                // preference to false while leaving the script on disk, so the switch said OFF for a
                // device that is still blocked at every boot - and the user had no way to see it.
                // Disk is the truth: if a script is installed, boot protection IS on.
                if (available) bootProtectionLock.withLock {
                    // null = could not determine. Never write that over the preference: a dropped
                    // Shizuku call or an expired su grant would otherwise erase the only record that
                    // boot protection is on.
                    val installedOnDisk = bootProtectionManager.isBootProtectionInstalled()
                    if (installedOnDisk != null && installedOnDisk != _uiState.value.bootProtection) {
                        AppLogger.w(
                            TAG,
                            "Boot protection preference (${_uiState.value.bootProtection}) disagreed with " +
                                "the script on disk ($installedOnDisk) - trusting disk"
                        )
                        _uiState.value = _uiState.value.copy(bootProtection = installedOnDisk)
                        withContext(Dispatchers.IO) { saveSetting(Constants.Settings.KEY_BOOT_PROTECTION, installedOnDisk, durable = true) }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to check boot protection availability", e)
                _uiState.value = _uiState.value.copy(bootProtectionAvailable = false)
            }
        }
    }

    fun setAllowCriticalPackageUninstall(allow: Boolean) {
        _uiState.value = _uiState.value.copy(allowCriticalPackageUninstall = allow)
        saveSetting(Constants.Settings.KEY_ALLOW_CRITICAL_UNINSTALL, allow)
    }

    fun setAllowCriticalPackageFirewall(allow: Boolean) {
        _uiState.value = _uiState.value.copy(allowCriticalPackageFirewall = allow)
        saveSetting(Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL, allow)
    }

    fun setShowFirewallStartPrompt(show: Boolean) {
        _uiState.value = _uiState.value.copy(showFirewallStartPrompt = show)
        saveSetting(Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT, show)
    }

    fun setConfirmRuleChanges(confirm: Boolean) {
        _uiState.value = _uiState.value.copy(confirmRuleChanges = confirm)
        saveSetting(Constants.Settings.KEY_CONFIRM_RULE_CHANGES, confirm)
    }

    fun setConfirmFirewallStop(confirm: Boolean) {
        _uiState.value = _uiState.value.copy(confirmFirewallStop = confirm)
        saveSetting(Constants.Settings.KEY_CONFIRM_FIREWALL_STOP, confirm)
    }

    fun setUseDynamicColors(enabled: Boolean, showRestartDialog: Boolean = false) {
        _uiState.value = _uiState.value.copy(useDynamicColors = enabled, requiresRestart = showRestartDialog)
        saveSetting(Constants.Settings.KEY_USE_DYNAMIC_COLORS, enabled)
    }

    fun clearRestartPrompt() {
        _uiState.value = _uiState.value.copy(requiresRestart = false)
    }

    fun wouldDisconnectOtherVpn(mode: FirewallMode): Boolean {
        if (mode != FirewallMode.VPN) return false
        
        return firewallManager.isAnotherVpnActive()
    }

    fun setFirewallMode(mode: FirewallMode, forceEvenIfOtherVpnActive: Boolean = false) {
        AppLogger.i(TAG, "👆 USER ACTION: setFirewallMode($mode, forceEvenIfOtherVpnActive=$forceEvenIfOtherVpnActive)")
        AppLogger.d(TAG, "   Current UI state: mode=${_uiState.value.firewallMode}, activeBackend=${firewallManager.activeBackendType.value}")
        
        firewallManager.dismissVpnConflictSwitchNotification()
        
        val wouldDisconnectVpn = wouldDisconnectOtherVpn(mode)
        if (wouldDisconnectVpn && !forceEvenIfOtherVpnActive) {
            AppLogger.d(TAG, "   Another VPN is active and user hasn't confirmed - showing warning")
            _uiState.value = _uiState.value.copy(
                pendingModeChange = mode,
                showVpnConflictWarning = true
            )
            return
        }
        
        AppLogger.i(TAG, "   Updating mode to $mode and restarting firewall")
        
        _uiState.value = _uiState.value.copy(
            firewallMode = mode,
            pendingModeChange = null,
            showVpnConflictWarning = false
        )
        firewallManager.setMode(mode)

        // Restart firewall if user has it enabled (regardless of current active state)
        // This handles the case where a previous backend switch failed and firewall is down
        viewModelScope.launch {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
            AppLogger.d(TAG, "   Firewall enabled: $isFirewallEnabled")
            if (isFirewallEnabled) {
                AppLogger.d(TAG, "   Calling restartFirewallIfRunning() with mode=$mode")
                restartFirewallIfRunning()
            }
        }
    }

    fun cancelPendingModeChange() {
        _uiState.value = _uiState.value.copy(
            pendingModeChange = null,
            showVpnConflictWarning = false
        )
    }

    fun confirmPendingModeChange() {
        val pendingMode = _uiState.value.pendingModeChange ?: return
        setFirewallMode(pendingMode, forceEvenIfOtherVpnActive = true)
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

    fun checkVpnPermissionNeeded(): android.content.Intent? {
        val mode = _uiState.value.firewallMode
        if (mode != FirewallMode.VPN) return null
        
        return android.net.VpnService.prepare(context)
    }

    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            restartFirewallIfRunning()
        }
    }

    private suspend fun restartFirewallIfRunning() {
        try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

            if (!isFirewallEnabled) {
                return
            }

            val newMode = _uiState.value.firewallMode
            if (newMode == FirewallMode.VPN) {
                val prepareIntent = android.net.VpnService.prepare(context)
                if (prepareIntent != null) {
                    _uiState.value = _uiState.value.copy(
                        vpnPermissionRequired = true
                    )
                    return
                }
            }

            // A failed stop must ABORT the switch. This used to discard the Result and start the
            // new backend anyway, which was wrong twice over: the old backend is still enforcing,
            // so two sets of rules end up live with only one of them visible or undoable - and the
            // successful start then calls reportFirewallHealthy(), erasing the very warning that
            // said the first backend is stuck. The user is left with a firewall they cannot turn
            // off and nothing on screen saying so.
            //
            // Aborting keeps exactly one backend running and leaves the StopFailed banner up, whose
            // "Stop again" button is the way out. The mode preference has already been written by
            // the caller, so a later successful stop lets the user retry the switch.
            firewallManager.stopFirewall().onFailure { error ->
                AppLogger.e(TAG, "Backend switch aborted - the old backend would not stop: ${error.message}", error)
                _uiState.value = _uiState.value.copy(
                    error = context.getString(
                        io.github.dorumrr.de1984.R.string.error_firewall_restart_failed,
                        error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                    )
                )
                return
            }
            delay(500)

            // Ask what will actually happen before doing it. computeStartPlan falls back to AUTO
            // on its own when this device cannot run the chosen backend, and reports the mode it
            // settled on - so comparing the two is how we learn the user's pick was substituted.
            // Without this the start would quietly succeed on a different backend and say nothing.
            val plan = firewallManager.computeStartPlan(newMode).getOrNull()
            val substituted = plan != null && plan.mode != newMode

            // AUTO can land on the VPN backend, which needs the system consent dialog. Starting it
            // without asking just burns the activation timeout and fails - no prompt, and no way to
            // reach one from here. The guard further up only covers an explicit VPN pick.
            // Not gated on `substituted`. The guard above only catches an explicit VPN pick, so a
            // straight AUTO choice that resolves to the VPN backend fell between the two and
            // started without ever asking for consent.
            if (plan?.requiresVpnPermission == true) {
                AppLogger.d(TAG, "Fallback would need VPN permission - asking instead of failing silently")
                _uiState.value = _uiState.value.copy(vpnPermissionRequired = true)
                return
            }

            val result = firewallManager.startFirewall(newMode)

            result.onSuccess {
                if (substituted) {
                    AppLogger.w(TAG, "$newMode is unavailable here - running ${plan?.mode} instead, keeping $newMode as the stored choice")
                    // Remember it so the picker stops offering it. The availability probe agreed it
                    // was usable, which is how it reached the picker at all, so re-probing cannot
                    // discover this - and without it the user can pick, be substituted, and pick
                    // again forever.
                    _startFailedModes.value = _startFailedModes.value + newMode
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(
                            io.github.dorumrr.de1984.R.string.backend_fell_back_to_auto,
                            displayNameFor(newMode)
                        ),
                        // Not a success. The fragment titles every plain message "Success", which
                        // would announce a forced downgrade as an accomplishment.
                        messageTitleRes = io.github.dorumrr.de1984.R.string.backend_changed_title
                    )
                }
            }

            result.onFailure { error ->
                // IMPORTANT: Do NOT clear KEY_FIREWALL_ENABLED here, and do NOT rewrite the mode
                // preference. The manual choice is load-bearing - handlePrivilegeChange restarts
                // exactly that backend when privileges come back - and a start can fail for reasons
                // that pass, like a Magisk prompt dismissed once.
                _startFailedModes.value = _startFailedModes.value + newMode
                _uiState.value = _uiState.value.copy(
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_firewall_restart_failed, error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restart firewall", e)
        }
    }

    /** The same names the backend picker shows, so a message never invents a second vocabulary. */
    private fun displayNameFor(mode: FirewallMode): String = context.getString(
        when (mode) {
            FirewallMode.AUTO -> io.github.dorumrr.de1984.R.string.backend_auto_name
            FirewallMode.VPN -> io.github.dorumrr.de1984.R.string.backend_vpn_name
            FirewallMode.IPTABLES -> io.github.dorumrr.de1984.R.string.backend_iptables_name
            FirewallMode.CONNECTIVITY_MANAGER -> io.github.dorumrr.de1984.R.string.backend_connectivity_manager_name
            FirewallMode.NETWORK_POLICY_MANAGER -> io.github.dorumrr.de1984.R.string.backend_network_policy_manager_name
        }
    )

    fun clearVpnPermissionRequired() {
        _uiState.value = _uiState.value.copy(vpnPermissionRequired = false)
    }

    

    
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, messageTitleRes = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun backupRules(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                val rules = firewallRepository.getAllRules().first()

                if (rules.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_no_rules_to_backup)
                    )
                    return@launch
                }

                val backup = FirewallRulesBackup(
                    version = 1,
                    exportDate = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    rulesCount = rules.size,
                    rules = rules
                )

                val json = Json.encodeToString(FirewallRulesBackup.serializer(), backup)

                writeToUri(uri, json)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "✅ Backup successful: ${rules.size} rules saved"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Backup failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_backup_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    fun restoreRules(uri: Uri, replaceExisting: Boolean) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                val json = readFromUri(uri)

                val backup = Json.decodeFromString<FirewallRulesBackup>(json)

                if (backup.version > 1) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_unsupported_backup_version, backup.version)
                    )
                    return@launch
                }

                if (backup.rules.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_backup_no_rules)
                    )
                    return@launch
                }

                // Re-point every rule at the uid its package holds NOW. The file carries the uid
                // from export time, and a reinstall since then changed it - the privileged backends
                // group rules by uid, so a stale one matches no app and Block All then blocks it
                // with no way back from the UI. See issue #81.
                val rules = withContext(Dispatchers.IO) {
                    backup.rules.map { HandleNewAppInstallUseCase.withCurrentIdentity(context, it) }
                }
                val repointed = rules.indices.count { rules[it].uid != backup.rules[it].uid }
                if (repointed > 0) {
                    AppLogger.i(TAG, "Restore: re-pointed $repointed of ${rules.size} rules at their current uid")
                }

                if (replaceExisting) {
                    firewallRepository.deleteAllRules()
                }

                firewallRepository.insertRules(rules)

                val action = if (replaceExisting) "restored" else "merged"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "✅ Rules $action successfully: ${backup.rules.size} rules"
                )
            } catch (e: SerializationException) {
                AppLogger.e(TAG, "Invalid backup file format", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_invalid_backup_format)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Restore failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_restore_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    suspend fun parseBackupFile(uri: Uri): Result<FirewallRulesBackup> {
        return withContext(Dispatchers.IO) {
            try {
                val json = readFromUri(uri)
                val backup = Json.decodeFromString<FirewallRulesBackup>(json)
                Result.success(backup)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse backup file", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun writeToUri(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray())
        } ?: throw IOException("Failed to open output stream")
    }

    /**
     * Read a user-picked file, and give a usable message when it cannot be opened at all.
     *
     * Picking a backup from the file picker's SEARCH results can hand back a MediaStore URI that
     * the picker's own provider then refuses:
     *
     *     com.android.externalstorage has no access to content://media/external_primary/file/...
     *
     * The denial is inside the provider chain the picker chose, not in our grant, and no permission
     * we could hold changes it - a .json backup is not covered by READ_MEDIA_* on API 33+. Browsing
     * to the same file yields a DocumentsProvider URI and works. So the fix is not a fix: it is
     * telling the user the one thing that gets them out of it.
     *
     * Only the OPEN is remapped. A failure part-way through reading keeps its own message, because
     * that is a different problem and "try browsing instead" would be wrong advice for it.
     *
     * The cause is kept so the original provider message still reaches the log.
     */
    private suspend fun readFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not open $uri - authority=${uri.authority}", e)
            throw IOException(
                context.getString(io.github.dorumrr.de1984.R.string.error_backup_file_unreadable),
                e
            )
        } ?: throw IOException(
            context.getString(io.github.dorumrr.de1984.R.string.error_backup_file_unreadable)
        )

        stream.use { inputStream ->
            inputStream.bufferedReader().readText()
        }
    }

    fun getCurrentDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

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


    fun exportUninstalledApps(uri: Uri) {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "📤 EXPORT: Starting export of uninstalled apps")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                AppLogger.d(TAG, "📤 EXPORT: Privilege check - root=${rootManager.hasRootPermission}, shizuku=${shizukuManager.hasShizukuPermission}")
                if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_export_requires_privileges)
                    )
                    return@launch
                }

                val result = packageRepository.getUninstalledSystemPackages()
                val packages = result.getOrNull()

                if (packages.isNullOrEmpty()) {
                    AppLogger.d(TAG, "📤 EXPORT: No uninstalled system packages found")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_export_no_uninstalled_apps)
                    )
                    return@launch
                }

                AppLogger.d(TAG, "📤 EXPORT: Found ${packages.size} uninstalled system packages")

                val content = createExportContent(packages)

                AppLogger.d(TAG, "📤 EXPORT: Writing to file: $uri")
                writeToUri(uri, content)

                AppLogger.d(TAG, "📤 EXPORT: Success - exported ${packages.size} packages")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = context.getString(io.github.dorumrr.de1984.R.string.success_export_uninstalled, packages.size)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "📤 EXPORT: Failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_export_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    private fun createExportContent(packages: List<Package>): String {
        return buildString {
            appendLine("# De1984 Uninstalled Apps Export")
            appendLine("# Date: ${getCurrentDate()}")
            appendLine("# App Version: ${BuildConfig.VERSION_NAME}")
            appendLine("# Count: ${packages.size}")
            appendLine()
            packages.forEach { pkg ->
                appendLine(pkg.packageName)
            }
        }
    }

    fun importUninstalledApps(uri: Uri) {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "📥 IMPORT: Starting import from file: $uri")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                AppLogger.d(TAG, "📥 IMPORT: Privilege check - root=${rootManager.hasRootPermission}, shizuku=${shizukuManager.hasShizukuPermission}")
                if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_import_requires_privileges)
                    )
                    return@launch
                }

                val content = readFromUri(uri)
                val packageNames = parseUninstalledAppsFile(content)

                AppLogger.d(TAG, "📥 IMPORT: Parsed ${packageNames.size} package names from file")

                if (packageNames.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.dialog_import_empty_file)
                    )
                    return@launch
                }

                val installedPackages = packageRepository.getPackages().first()
                val installedPackageNames = installedPackages.map { it.packageName }.toSet()

                val packagesNotFound = packageNames.filter { it !in installedPackageNames }
                val installedFromFile = packageNames.filter { it in installedPackageNames }

                // A package list is a file the user can be handed by anyone. Nothing here checked
                // criticality: the only filter was "is it installed", so an ESSENTIAL package or
                // De1984 itself would be uninstalled without the typed confirmation the Packages
                // screen demands for exactly those apps. Protected packages are dropped from the
                // batch and reported, never silently uninstalled.
                val packagesProtected = mutableListOf<String>()
                val packagesToUninstallNames = mutableListOf<String>()
                for (name in installedFromFile) {
                    val isProtected = Constants.App.isOwnApp(name) ||
                        Constants.Firewall.isSystemCritical(name) ||
                        PackageSafetyLoader.getCriticality(context, name) == PackageCriticality.ESSENTIAL
                    if (isProtected) packagesProtected.add(name) else packagesToUninstallNames.add(name)
                }

                val packagesToUninstall = packagesToUninstallNames.map { it to 0 }

                AppLogger.d(TAG, "📥 IMPORT: Validation - ${packagesToUninstall.size} found, " +
                        "${packagesNotFound.size} not found, ${packagesProtected.size} protected and skipped")
                if (packagesProtected.isNotEmpty()) {
                    AppLogger.w(TAG, "📥 IMPORT: refusing to uninstall protected packages: ${packagesProtected.joinToString()}")
                }

                when {
                    packagesToUninstall.isEmpty() -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = context.getString(io.github.dorumrr.de1984.R.string.dialog_import_all_not_found, packagesNotFound.size)
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            importUninstalledPreview = ImportUninstalledPreview(
                                totalPackages = packageNames.size,
                                packagesToUninstall = packagesToUninstall,
                                packagesNotFound = packagesNotFound,
                                packagesProtected = packagesProtected
                            )
                        )
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "📥 IMPORT: File read failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_import_file_read_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            } catch (e: CancellationException) {
                // Never swallowed. CancellationException extends Exception, so the generic catch
                // below would mislabel it as a failed import and break structured concurrency by not
                // letting it through.
                //
                // Not because the user navigating away cancels this - it does not; this ViewModel is
                // activityViewModels, so the only cancellation is onCleared. It is here so that any
                // cancellation, present or future, cannot be reported as a failure.
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "📥 IMPORT: Failed", e)
                // A scan failure carries developer English written for a log. The import runs
                // getPackages().first(), so that message was being formatted straight into the
                // user's error toast, untranslated, in every locale. Discriminated by TYPE:
                // IllegalStateException also catches unrelated ones from the file parsing above -
                // and kotlinx CancellationException is itself an IllegalStateException.
                val detail = if (e is io.github.dorumrr.de1984.data.datasource.PackageScanException) {
                    context.getString(io.github.dorumrr.de1984.R.string.error_package_scan_failed)
                } else {
                    e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_import_failed, detail)
                )
            } finally {
                // The one guaranteed exit, covering the paths no catch above reaches: the rethrown
                // CancellationException, and any future early return. NOT the OutOfMemoryError case
                // - an Error escaping viewModelScope.launch takes the process with it, so nothing
                // this writes is ever rendered; that case has no in-app recovery either way. The
                // assignments above are now redundant rather than load-bearing.
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun parseUninstalledAppsFile(content: String): List<String> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("#") }
            .filter { it.contains(".") }
            .distinct()
    }

    fun confirmImportUninstall() {
        viewModelScope.launch {
            val preview = _uiState.value.importUninstalledPreview ?: return@launch

            AppLogger.d(TAG, "📥 IMPORT: User confirmed - starting batch uninstall of ${preview.packagesToUninstall.size} packages")
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                importUninstalledPreview = null
            )

            val result = packageRepository.uninstallMultiplePackages(preview.packagesToUninstall)

            result.fold(
                onSuccess = { batchResult ->
                    AppLogger.d(TAG, "📥 IMPORT: Batch uninstall complete - ${batchResult.succeeded.size} succeeded, ${batchResult.failed.size} failed")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        batchUninstallResult = batchResult
                    )
                },
                onFailure = { error ->
                    AppLogger.e(TAG, "📥 IMPORT: Batch uninstall failed", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                    )
                }
            )
        }
    }

    fun clearImportPreview() {
        _uiState.value = _uiState.value.copy(importUninstalledPreview = null)
    }

    fun clearBatchUninstallResult() {
        _uiState.value = _uiState.value.copy(batchUninstallResult = null)
    }


    fun loadCaptivePortalSettings() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                if (!captivePortalManager.hasOriginalSettings()) {
                    captivePortalManager.captureOriginalSettings()
                }

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
                AppLogger.e(TAG, "Failed to load captive portal settings", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun applyCaptivePortalPreset(preset: CaptivePortalPreset) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.applyPreset(preset)
                if (result.isSuccess) {
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
                AppLogger.e(TAG, "Failed to apply preset", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun setCaptivePortalDetectionMode(mode: CaptivePortalMode) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.setDetectionMode(mode)
                if (result.isSuccess) {
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
                AppLogger.e(TAG, "Failed to set detection mode", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun setCustomCaptivePortalUrls(httpUrl: String, httpsUrl: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.setCustomUrls(httpUrl, httpsUrl)
                if (result.isSuccess) {
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
                AppLogger.e(TAG, "Failed to set custom URLs", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun restoreOriginalCaptivePortalSettings() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.restoreOriginalSettings()
                if (result.isSuccess) {
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
                AppLogger.e(TAG, "Failed to restore original settings", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun resetCaptivePortalToGoogleDefaults() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.resetToGoogleDefaults()
                if (result.isSuccess) {
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
                AppLogger.e(TAG, "Failed to reset to Google defaults", e)
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
        private val captivePortalManager: CaptivePortalManager,
        private val bootProtectionManager: BootProtectionManager,
        private val smartPolicySwitchUseCase: SmartPolicySwitchUseCase,
        private val packageRepository: PackageRepository
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
                    captivePortalManager,
                    bootProtectionManager,
                    smartPolicySwitchUseCase,
                    packageRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class SettingsUiState(
    val showAppIcons: Boolean = true,
    val defaultFirewallPolicy: String = Constants.Settings.DEFAULT_FIREWALL_POLICY,
    val newAppNotifications: Boolean = Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS,
    val bootProtection: Boolean = Constants.Settings.DEFAULT_BOOT_PROTECTION,
    val bootProtectionAvailable: Boolean = false,
    /** A "Try to remove" attempt is running - lockout scenario 5. */
    val bootProtectionRemovalInProgress: Boolean = false,
    /** The device is about to restart. Non-dismissible: nothing the user does can stop it now. */
    val isRebooting: Boolean = false,
    val firewallMode: FirewallMode = FirewallMode.AUTO,
    val allowCriticalPackageUninstall: Boolean = Constants.Settings.DEFAULT_ALLOW_CRITICAL_UNINSTALL,
    val allowCriticalPackageFirewall: Boolean = Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL,
    val showFirewallStartPrompt: Boolean = Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT,
    val confirmRuleChanges: Boolean = Constants.Settings.DEFAULT_CONFIRM_RULE_CHANGES,
    val confirmFirewallStop: Boolean = Constants.Settings.DEFAULT_CONFIRM_FIREWALL_STOP,
    val useDynamicColors: Boolean = Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS,
    val appLanguage: String = Constants.Settings.DEFAULT_APP_LANGUAGE,

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
    /** Title for [message]; null falls back to the generic success title. */
    val messageTitleRes: Int? = null,
    val error: String? = null,

    val hasBasicPermissions: Boolean = true,
    val hasEnhancedPermissions: Boolean = false,
    val hasAdvancedPermissions: Boolean = false,

    val captivePortalSettings: CaptivePortalSettings? = null,
    val captivePortalOriginalCaptured: Boolean = false,
    val captivePortalHasPrivileges: Boolean = false,
    val captivePortalLoading: Boolean = false,
    val captivePortalError: String? = null,

    val importUninstalledPreview: ImportUninstalledPreview? = null,
    val batchUninstallResult: UninstallBatchResult? = null,

    val pendingModeChange: FirewallMode? = null,
    val showVpnConflictWarning: Boolean = false,
    val vpnPermissionRequired: Boolean = false
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val androidROM: String,
    val hasRoot: Boolean,
    val architecture: String
)

data class ImportUninstalledPreview(
    val totalPackages: Int,
    val packagesToUninstall: List<Pair<String, Int>>,
    val packagesNotFound: List<String>,
    /** Installed, but refused: De1984 itself, system-critical packages, ESSENTIAL packages. */
    val packagesProtected: List<String> = emptyList()
)


