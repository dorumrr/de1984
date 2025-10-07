package io.github.dorumrr.de1984.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.usecase.GetPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManagePackageUseCase
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PackagesViewModel(
    private val getPackagesUseCase: GetPackagesUseCase,
    private val managePackageUseCase: ManagePackageUseCase,
    private val superuserBannerState: SuperuserBannerState,
    private val rootManager: RootManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    val showRootBanner: Boolean
        get() = superuserBannerState.showBanner

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    fun checkRootAccess() {
        viewModelScope.launch {
            rootManager.checkRootStatus()
        }
    }

    init {
        loadPackages()
    }

    fun loadPackages() {
        val filterState = _uiState.value.filterState
        getPackagesUseCase.getFilteredByState(filterState)
            .catch { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    error = error.message
                )
            }
            .onEach { packages ->
                _uiState.value = _uiState.value.copy(
                    packages = packages,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null
                )
            }
            .launchIn(viewModelScope)
    }
    
    fun setPackageTypeFilter(packageType: String) {
        val newFilterState = PackageFilterState(
            packageType = packageType,
            packageState = null
        )
        _uiState.value = _uiState.value.copy(
            filterState = newFilterState,
            isLoadingData = true,
            isRenderingUI = false
        )

        loadPackages()
    }

    fun setPackageStateFilter(packageState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(packageState = packageState)
        _uiState.value = _uiState.value.copy(
            filterState = newFilterState,
            isLoadingData = true,
            isRenderingUI = false
        )
        loadPackages()
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.setPackageEnabled(packageName, enabled)
                .onSuccess {
                    loadPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoadingData = false,
                        isRenderingUI = false,
                        error = error.message
                    )
                }
        }
    }

    fun uninstallPackage(packageName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.uninstallPackage(packageName)
                .onSuccess {
                    loadPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoadingData = false,
                        isRenderingUI = false,
                        error = error.message
                    )
                }
        }
    }

    fun forceStopPackage(packageName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.forceStopPackage(packageName)
                .onSuccess {
                    loadPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoadingData = false,
                        isRenderingUI = false,
                        error = error.message
                    )
                }
        }
    }
    
    fun setUIReady() {
        _uiState.value = _uiState.value.copy(isRenderingUI = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    class Factory(
        private val getPackagesUseCase: GetPackagesUseCase,
        private val managePackageUseCase: ManagePackageUseCase,
        private val superuserBannerState: SuperuserBannerState,
        private val rootManager: RootManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PackagesViewModel::class.java)) {
                return PackagesViewModel(
                    getPackagesUseCase,
                    managePackageUseCase,
                    superuserBannerState,
                    rootManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class PackageFilterState(
    val packageType: String = "User",
    val packageState: String? = null
)

data class PackagesUiState(
    val packages: List<Package> = emptyList(),
    val filterState: PackageFilterState = PackageFilterState(),
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
