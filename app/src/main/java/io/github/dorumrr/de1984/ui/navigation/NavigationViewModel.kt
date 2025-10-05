package io.github.dorumrr.de1984.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    val superuserBannerState: SuperuserBannerState
) : ViewModel()
