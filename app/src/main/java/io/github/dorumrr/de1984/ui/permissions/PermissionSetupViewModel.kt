package io.github.dorumrr.de1984.ui.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class PermissionSetupViewModel constructor(
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionSetupUiState())
    val uiState: StateFlow<PermissionSetupUiState> = _uiState.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            val basicPermissions = getBasicPermissionInfo()
            val advancedPermissions = getAdvancedPermissionInfo()
            val batteryOptimizationInfo = getBatteryOptimizationInfo()

            _uiState.value = _uiState.value.copy(
                hasBasicPermissions = permissionManager.hasBasicPermissions(),
                hasEnhancedPermissions = true,
                hasAdvancedPermissions = permissionManager.hasRootAccess() || permissionManager.hasSystemPermissions(),
                hasBatteryOptimizationExemption = permissionManager.isBatteryOptimizationDisabled(),
                basicPermissions = basicPermissions,
                enhancedPermissions = emptyList(),
                advancedPermissions = advancedPermissions,
                batteryOptimizationInfo = batteryOptimizationInfo,
                isLoading = false
            )
        }
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return permissionManager.hasRequestedNotificationPermission()
    }

    fun markNotificationPermissionRequested() {
        permissionManager.markNotificationPermissionRequested()
    }

    private fun getBasicPermissionInfo(): List<PermissionInfo> {
        return listOf(
            PermissionInfo(
                permission = "android.permission.ACCESS_NETWORK_STATE",
                name = "Network State",
                description = "Monitor network connectivity (WiFi/Mobile/Roaming)",
                isGranted = true
            ),
            PermissionInfo(
                permission = "android.permission.ACCESS_WIFI_STATE",
                name = "WiFi State",
                description = "Detect WiFi network status",
                isGranted = true
            ),
            PermissionInfo(
                permission = "android.permission.POST_NOTIFICATIONS",
                name = "Notification Permission",
                description = "Show notifications for new app installations",
                isGranted = permissionManager.hasNotificationPermission()
            )
        )
    }

    private fun getEnhancedPermissionInfo(): List<PermissionInfo> {
        return emptyList()
    }

    private fun getAdvancedPermissionInfo(): List<PermissionInfo> {
        val hasRoot = permissionManager.hasRootAccess()
        return listOf(
            PermissionInfo(
                permission = "android.permission.WRITE_SECURE_SETTINGS",
                name = "Modify System Settings",
                description = "Change system-level security settings",
                isGranted = hasRoot || permissionManager.hasSystemPermissions()
            ),
            PermissionInfo(
                permission = "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
                name = "Enable/Disable Components",
                description = "Enable or disable app components",
                isGranted = hasRoot || permissionManager.hasSystemPermissions()
            ),
            PermissionInfo(
                permission = "Root Access",
                name = "Superuser Access",
                description = "Full system access for advanced operations",
                isGranted = hasRoot
            )
        )
    }

    private fun getBatteryOptimizationInfo(): List<PermissionInfo> {
        val isExempt = permissionManager.isBatteryOptimizationDisabled()
        return listOf(
            PermissionInfo(
                permission = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                name = "Battery Optimization Exemption",
                description = "Prevents Android from killing the firewall service to save battery. Critical for VPN reliability.",
                isGranted = isExempt
            )
        )
    }

    class Factory(
        private val permissionManager: PermissionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PermissionSetupViewModel::class.java)) {
                return PermissionSetupViewModel(permissionManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class PermissionSetupUiState(
    val isLoading: Boolean = true,
    val hasBasicPermissions: Boolean = false,
    val hasEnhancedPermissions: Boolean = false,
    val hasAdvancedPermissions: Boolean = false,
    val hasBatteryOptimizationExemption: Boolean = false,
    val basicPermissions: List<PermissionInfo> = emptyList(),
    val enhancedPermissions: List<PermissionInfo> = emptyList(),
    val advancedPermissions: List<PermissionInfo> = emptyList(),
    val batteryOptimizationInfo: List<PermissionInfo> = emptyList(),
    val errorMessage: String? = null
)
