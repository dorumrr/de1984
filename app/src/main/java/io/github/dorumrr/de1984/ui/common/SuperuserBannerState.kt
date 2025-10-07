package io.github.dorumrr.de1984.ui.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SuperuserBannerState {

    private var _showBanner by mutableStateOf(false)
    val showBanner: Boolean get() = _showBanner

    private var autoDismissJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun showSuperuserRequiredBanner() {
        _showBanner = true

        autoDismissJob?.cancel()

        autoDismissJob = scope.launch {
            delay(10000)
            _showBanner = false
        }
    }

    fun hideBanner() {
        autoDismissJob?.cancel()
        _showBanner = false
    }

    fun shouldShowBannerForError(error: Throwable?): Boolean {
        return error is SecurityException &&
               (error.message?.contains("Root access required", ignoreCase = true) == true ||
                error.message?.contains("superuser", ignoreCase = true) == true)
    }
}
