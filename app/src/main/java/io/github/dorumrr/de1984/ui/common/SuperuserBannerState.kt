package io.github.dorumrr.de1984.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State manager for the superuser banner
 */
class SuperuserBannerState {

    private val _showBanner = MutableStateFlow(false)
    val showBanner: StateFlow<Boolean> = _showBanner.asStateFlow()

    private var autoDismissJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun showSuperuserRequiredBanner() {
        _showBanner.value = true

        autoDismissJob?.cancel()

        autoDismissJob = scope.launch {
            delay(10000)
            _showBanner.value = false
        }
    }

    fun hideBanner() {
        autoDismissJob?.cancel()
        _showBanner.value = false
    }

    fun shouldShowBannerForError(error: Throwable?): Boolean {
        return error is SecurityException &&
               (error.message?.contains("Shizuku", ignoreCase = true) == true ||
                error.message?.contains("Root access required", ignoreCase = true) == true ||
                error.message?.contains("superuser", ignoreCase = true) == true)
    }
}

