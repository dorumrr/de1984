package io.github.dorumrr.de1984.ui.navigation

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.PackagesViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.acknowledgements.AcknowledgementsScreen
import io.github.dorumrr.de1984.ui.firewall.FirewallScreen
import io.github.dorumrr.de1984.ui.packages.PackagesScreen
import io.github.dorumrr.de1984.ui.settings.SettingsScreen
import io.github.dorumrr.de1984.utils.Constants

@Composable
fun De1984Navigation(
    showFirewallStartDialog: Boolean = false,
    onFirewallStartDialogDismiss: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val dependencies = (context.applicationContext as De1984Application).dependencies

    val sharedFirewallViewModel: FirewallViewModel = viewModel(
        factory = FirewallViewModel.Factory(
            application = context.applicationContext as android.app.Application,
            getNetworkPackagesUseCase = dependencies.provideGetNetworkPackagesUseCase(),
            manageNetworkAccessUseCase = dependencies.provideManageNetworkAccessUseCase(),
            superuserBannerState = dependencies.superuserBannerState,
            permissionManager = dependencies.permissionManager
        )
    )

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val batteryOptIntent = sharedFirewallViewModel.onVpnPermissionGranted()
            if (batteryOptIntent != null) {
                batteryOptimizationLauncher.launch(batteryOptIntent)
            }
        } else {
            sharedFirewallViewModel.onVpnPermissionDenied()
        }
    }

    Scaffold(
        topBar = {
            FixedTopBar(
                navController = navController,
                firewallViewModel = sharedFirewallViewModel,
                vpnPermissionLauncher = vpnPermissionLauncher,
                isVisible = true
            )
        },
        bottomBar = {
            BottomNavigationBar(navController = navController)
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavigationHost(
                navController = navController,
                sharedFirewallViewModel = sharedFirewallViewModel
            )
        }
    }

    if (showFirewallStartDialog) {
        FirewallStartDialog(
            onStartFirewall = {
                onFirewallStartDialogDismiss()
                val prepareIntent = sharedFirewallViewModel.startFirewall()
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                }
            },
            onSkip = {
                onFirewallStartDialogDismiss()
            }
        )
    }
}

@Composable
private fun FixedTopBar(
    navController: NavHostController,
    firewallViewModel: io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel,
    vpnPermissionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    isVisible: Boolean = true
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val firewallUiState by firewallViewModel.uiState.collectAsState()

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        io.github.dorumrr.de1984.ui.common.De1984TopBar(
            modifier = Modifier.alpha(if (isVisible) 1f else 0f),
            title = when (currentRoute) {
                Screen.Firewall.route -> "Firewall"
                Screen.Apps.route -> "Apps"
                Screen.Settings.route -> "Settings"
                else -> "De1984"
            },
            showSwitch = currentRoute == Screen.Firewall.route,
            switchChecked = firewallUiState.isFirewallEnabled,
            onSwitchChange = { enabled ->
                if (enabled) {
                    val prepareIntent = firewallViewModel.startFirewall()
                    if (prepareIntent != null) {
                        vpnPermissionLauncher.launch(prepareIntent)
                    }
                } else {
                    firewallViewModel.stopFirewall()
                }
            },
            sectionIcon = when (currentRoute) {
                Screen.Apps.route -> Icons.Filled.GridView
                Screen.Settings.route -> Icons.Filled.Settings
                else -> null
            }
        )
    }
}

@Composable
private fun BottomNavigationBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Surface(
        shadowElevation = Constants.UI.ELEVATION_CARD,
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Constants.UI.BOTTOM_NAV_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            bottomNavItems.forEach { screen ->
                val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            modifier = Modifier.size(Constants.UI.ICON_SIZE_MEDIUM),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))
                        Text(
                            text = screen.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FirewallStartDialog(
    onStartFirewall: () -> Unit,
    onSkip: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = { },
        modifier = Modifier.wrapContentSize()
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = Constants.UI.ELEVATION_SURFACE
        ) {
            Column(
                modifier = Modifier.padding(Constants.UI.SPACING_LARGE)
            ) {
                Text(
                    text = io.github.dorumrr.de1984.utils.Constants.UI.Dialogs.FIREWALL_START_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = Constants.UI.SPACING_STANDARD)
                )

                Text(
                    text = io.github.dorumrr.de1984.utils.Constants.UI.Dialogs.FIREWALL_START_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = Constants.UI.SPACING_LARGE)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onSkip) {
                        Text(io.github.dorumrr.de1984.utils.Constants.UI.Dialogs.FIREWALL_START_SKIP)
                    }

                    Button(onClick = onStartFirewall) {
                        Text(io.github.dorumrr.de1984.utils.Constants.UI.Dialogs.FIREWALL_START_CONFIRM)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationHost(
    navController: NavHostController,
    sharedFirewallViewModel: FirewallViewModel
) {
    val context = LocalContext.current
    val dependencies = (context.applicationContext as De1984Application).dependencies

    val sharedSettingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            context = context.applicationContext,
            permissionManager = dependencies.permissionManager,
            rootManager = dependencies.rootManager
        )
    )

    val permissionSetupViewModel: io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel = viewModel(
        factory = io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel.Factory(
            permissionManager = dependencies.permissionManager
        )
    )

    NavHost(
        navController = navController,
        startDestination = Screen.Firewall.route
    ) {
        composable(Screen.Firewall.route) {
            FirewallScreen(
                viewModel = sharedFirewallViewModel,
                settingsViewModel = sharedSettingsViewModel
            )
        }

        composable(Screen.Apps.route) {
            val packagesViewModel: PackagesViewModel = viewModel(
                factory = PackagesViewModel.Factory(
                    getPackagesUseCase = dependencies.provideGetPackagesUseCase(),
                    managePackageUseCase = dependencies.provideManagePackageUseCase(),
                    superuserBannerState = dependencies.superuserBannerState,
                    rootManager = dependencies.rootManager
                )
            )
            PackagesScreen(
                viewModel = packagesViewModel,
                settingsViewModel = sharedSettingsViewModel
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                settingsViewModel = sharedSettingsViewModel,
                permissionViewModel = permissionSetupViewModel,
                onNavigateToAcknowledgements = {
                    navController.navigate("acknowledgements")
                }
            )
        }

        composable("acknowledgements") {
            AcknowledgementsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Firewall : Screen("firewall", Constants.Firewall.TITLE, Icons.Filled.Security)
    object Apps : Screen("apps", "Apps", Icons.Filled.GridView)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

}

private val bottomNavItems = listOf(
    Screen.Firewall,
    Screen.Apps,
    Screen.Settings
)
