package io.github.dorumrr.de1984.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.SystemCapabilities
import io.github.dorumrr.de1984.ui.permissions.PermissionItem
import io.github.dorumrr.de1984.ui.permissions.PermissionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class PermissionsViewModel constructor(
    private val permissionManager: PermissionManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()
    
    init {
        loadPermissions()
    }
    
    private fun loadPermissions() {
        viewModelScope.launch {
            try {
                val systemCapabilities = permissionManager.getSystemCapabilities()
                val permissionItems = createPermissionItems(systemCapabilities)
                
                _uiState.value = _uiState.value.copy(
                    systemCapabilities = systemCapabilities,
                    permissionItems = permissionItems,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load permissions: ${e.message}",
                    isLoading = false
                )
            }
        }
    }
    
    private fun createPermissionItems(capabilities: SystemCapabilities): List<PermissionItem> {
        return listOf(
            PermissionItem(
                name = "Basic Network Access",
                description = "Required for checking package network permissions",
                requiredFor = "Package network access analysis",
                isGranted = capabilities.hasBasicPermissions,
                canRequest = true,
                type = PermissionType.BASIC_PERMISSIONS
            ),

            PermissionItem(
                name = "Package Query",
                description = "Permission to query all installed packages",
                requiredFor = "Complete package listing and management",
                isGranted = capabilities.hasPackageQuery,
                canRequest = false,
                type = PermissionType.PACKAGE_QUERY
            ),
            PermissionItem(
                name = "Root Access",
                description = "Superuser privileges for advanced system operations",
                requiredFor = "Advanced package control and system operations",
                isGranted = capabilities.hasRootAccess,
                canRequest = false,
                type = PermissionType.ROOT_ACCESS
            )
        )
    }
    
    fun requestBasicPermissions() {
        viewModelScope.launch {
            loadPermissions()
        }
    }
    
    fun showRootAccessInfo() {
        _uiState.value = _uiState.value.copy(
            showRootInfo = true
        )
    }
    
    fun dismissRootInfo() {
        _uiState.value = _uiState.value.copy(
            showRootInfo = false
        )
    }
    
    fun refreshPermissions() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadPermissions()
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class PermissionsUiState(
    val systemCapabilities: SystemCapabilities = SystemCapabilities(
        hasBasicPermissions = false,
        hasPackageQuery = false,
        hasRootAccess = false,
        canManagePackages = false
    ),
    val permissionItems: List<PermissionItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showRootInfo: Boolean = false
)
