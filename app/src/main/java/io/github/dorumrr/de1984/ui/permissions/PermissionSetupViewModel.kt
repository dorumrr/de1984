package io.github.dorumrr.de1984.ui.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class PermissionSetupViewModel constructor(
    private val context: Context,
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
            val vpnPermissionInfo = getVpnPermissionInfo()

            _uiState.value = _uiState.value.copy(
                hasBasicPermissions = permissionManager.hasBasicPermissions(),
                hasEnhancedPermissions = true,
                hasAdvancedPermissions = permissionManager.hasRootAccess() || permissionManager.hasShizukuAccess() || permissionManager.hasSystemPermissions(),
                hasBatteryOptimizationExemption = permissionManager.isBatteryOptimizationDisabled(),
                hasVpnPermission = permissionManager.hasVpnPermission(),
                basicPermissions = basicPermissions,
                enhancedPermissions = emptyList(),
                advancedPermissions = advancedPermissions,
                batteryOptimizationInfo = batteryOptimizationInfo,
                vpnPermissionInfo = vpnPermissionInfo,
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
                name = context.getString(R.string.permission_network_state),
                description = context.getString(R.string.permission_network_state_desc),
                isGranted = true
            ),
            PermissionInfo(
                permission = "android.permission.ACCESS_WIFI_STATE",
                name = context.getString(R.string.permission_wifi_state),
                description = context.getString(R.string.permission_wifi_state_desc),
                isGranted = true
            ),
            PermissionInfo(
                permission = "android.permission.POST_NOTIFICATIONS",
                name = context.getString(R.string.permission_notification),
                description = context.getString(R.string.permission_notification_desc),
                isGranted = permissionManager.hasNotificationPermission()
            )
        )
    }

    private fun getEnhancedPermissionInfo(): List<PermissionInfo> {
        return emptyList()
    }

    private fun getAdvancedPermissionInfo(): List<PermissionInfo> {
        val hasRoot = permissionManager.hasRootAccess()
        val hasShizuku = permissionManager.hasShizukuAccess()
        val hasAdvanced = hasRoot || hasShizuku || permissionManager.hasSystemPermissions()

        return listOf(
            PermissionInfo(
                permission = "android.permission.WRITE_SECURE_SETTINGS",
                name = context.getString(R.string.permission_modify_system_settings),
                description = context.getString(R.string.permission_modify_system_settings_desc),
                isGranted = hasAdvanced
            ),
            PermissionInfo(
                permission = "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
                name = context.getString(R.string.permission_enable_disable_components),
                description = context.getString(R.string.permission_enable_disable_components_desc),
                isGranted = hasAdvanced
            ),
            PermissionInfo(
                permission = "Superuser Access",
                name = context.getString(R.string.permission_superuser_access),
                description = context.getString(R.string.permission_superuser_access_desc),
                isGranted = hasAdvanced
            )
        )
    }

    private fun getBatteryOptimizationInfo(): List<PermissionInfo> {
        val isExempt = permissionManager.isBatteryOptimizationDisabled()
        return listOf(
            PermissionInfo(
                permission = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                name = context.getString(R.string.permission_battery_optimization),
                description = context.getString(R.string.permission_battery_optimization_desc),
                isGranted = isExempt
            )
        )
    }

    private fun getVpnPermissionInfo(): List<PermissionInfo> {
        val hasVpn = permissionManager.hasVpnPermission()
        return listOf(
            PermissionInfo(
                permission = "android.permission.BIND_VPN_SERVICE",
                name = context.getString(R.string.permission_vpn),
                description = context.getString(R.string.permission_vpn_desc),
                isGranted = hasVpn
            )
        )
    }

    class Factory(
        private val context: Context,
        private val permissionManager: PermissionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PermissionSetupViewModel::class.java)) {
                return PermissionSetupViewModel(context, permissionManager) as T
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
    val hasVpnPermission: Boolean = false,
    val basicPermissions: List<PermissionInfo> = emptyList(),
    val enhancedPermissions: List<PermissionInfo> = emptyList(),
    val advancedPermissions: List<PermissionInfo> = emptyList(),
    val batteryOptimizationInfo: List<PermissionInfo> = emptyList(),
    val vpnPermissionInfo: List<PermissionInfo> = emptyList(),
    val errorMessage: String? = null
)
