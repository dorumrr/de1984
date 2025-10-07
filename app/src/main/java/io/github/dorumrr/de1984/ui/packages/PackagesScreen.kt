package io.github.dorumrr.de1984.ui.packages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.dorumrr.de1984.ui.common.*
import io.github.dorumrr.de1984.presentation.viewmodel.PackagesViewModel
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.utils.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    viewModel: PackagesViewModel,
    settingsViewModel: io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val showBanner = viewModel.showRootBanner
    var selectedPackage by remember { mutableStateOf<Package?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showForceStopConfirmation by remember { mutableStateOf(false) }
    var packageToForceStop by remember { mutableStateOf<Package?>(null) }
    var showEnableDisableConfirmation by remember { mutableStateOf(false) }
    var packageToEnableDisable by remember { mutableStateOf<Package?>(null) }
    var enableDisableAction by remember { mutableStateOf(true) }
    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var packageToUninstall by remember { mutableStateOf<Package?>(null) }

    LaunchedEffect(Unit) {
        viewModel.checkRootAccess()
    }

    val listState = rememberLazyListState()

    LaunchedEffect(uiState.packages, uiState.isRenderingUI, listState.layoutInfo.visibleItemsInfo) {
        if (uiState.isRenderingUI) {
            if (uiState.packages.isEmpty()) {
                viewModel.setUIReady()
            } else if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                withFrameMillis { }
                viewModel.setUIReady()
            }
        }
    }

    val packageTypeFilters = Constants.Packages.PACKAGE_TYPE_FILTERS
    val packageStateFilters = Constants.Packages.PACKAGE_STATE_FILTERS

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.padding(
                    horizontal = Constants.UI.SPACING_STANDARD,
                    vertical = Constants.UI.SPACING_SMALL
                )
            ) {
                FilterChipsRow(
                    chips = (packageTypeFilters + packageStateFilters).map { filter ->
                        FilterChipData(
                            label = filter,
                            selected = when (filter) {
                                in packageTypeFilters -> uiState.filterState.packageType == filter
                                in packageStateFilters -> uiState.filterState.packageState == filter
                                else -> false
                            },
                            onClick = {
                                when (filter) {
                                    in packageTypeFilters -> {
                                        viewModel.setPackageTypeFilter(filter)
                                    }
                                    in packageStateFilters -> {
                                        val newState = if (uiState.filterState.packageState == filter) null else filter
                                        viewModel.setPackageStateFilter(newState)
                                    }
                                }
                            }
                        )
                    }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                SectionCard(
                    modifier = Modifier.fillMaxSize(),
                    hasRoundedCorners = false
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        contentPadding = PaddingValues(bottom = Constants.UI.SPACING_STANDARD)
                    ) {
                        items(
                            items = uiState.packages,
                            key = { packageInfo -> packageInfo.packageName }
                        ) { packageInfo ->
                            PackageItemRow(
                                packageName = packageInfo.packageName,
                                icon = packageInfo.icon,
                                name = packageInfo.name,
                                details = packageInfo.packageName,
                                isEnabled = packageInfo.isEnabled,
                                showRealIcons = settingsUiState.showAppIcons,
                                actionText = if (packageInfo.isEnabled) "Enabled" else "Disabled",
                                secondaryText = packageInfo.type.name,
                                onClick = {
                                    selectedPackage = packageInfo
                                    showActionDialog = true
                                }
                            )
                        }
                    }
                }

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        selectedPackage?.let { pkg ->
            ActionBottomSheet(
                isVisible = showActionDialog,
                onDismiss = {
                    showActionDialog = false
                    selectedPackage = null
                },
                title = pkg.name,
                subtitle = pkg.packageName,
                icon = pkg.icon,
                packageName = pkg.packageName,
                showRealIcons = settingsUiState.showAppIcons,
                actions = getPackageActions(
                    packageInfo = pkg,
                    onShowForceStopConfirmation = { packageInfo ->
                        packageToForceStop = packageInfo
                        showForceStopConfirmation = true
                    },
                    onShowEnableDisableConfirmation = { packageInfo, isEnabling ->
                        packageToEnableDisable = packageInfo
                        enableDisableAction = isEnabling
                        showEnableDisableConfirmation = true
                    },
                    onShowUninstallConfirmation = { packageInfo ->
                        packageToUninstall = packageInfo
                        showUninstallConfirmation = true
                    }
                )
            )
        }

        if (showForceStopConfirmation && packageToForceStop != null) {
            ForceStopConfirmationDialog(
                packageInfo = packageToForceStop!!,
                onConfirm = {
                    viewModel.forceStopPackage(packageToForceStop!!.packageName)
                    showForceStopConfirmation = false
                    packageToForceStop = null
                    showActionDialog = false
                    selectedPackage = null
                },
                onDismiss = {
                    showForceStopConfirmation = false
                    packageToForceStop = null
                }
            )
        }

        if (showEnableDisableConfirmation && packageToEnableDisable != null) {
            EnableDisableConfirmationDialog(
                packageInfo = packageToEnableDisable!!,
                isEnabling = enableDisableAction,
                onConfirm = {
                    viewModel.setPackageEnabled(packageToEnableDisable!!.packageName, enableDisableAction)
                    showEnableDisableConfirmation = false
                    packageToEnableDisable = null
                    showActionDialog = false
                    selectedPackage = null
                },
                onDismiss = {
                    showEnableDisableConfirmation = false
                    packageToEnableDisable = null
                }
            )
        }

        if (showUninstallConfirmation && packageToUninstall != null) {
            UninstallConfirmationDialog(
                packageInfo = packageToUninstall!!,
                onConfirm = {
                    viewModel.uninstallPackage(packageToUninstall!!.packageName)
                    showUninstallConfirmation = false
                    packageToUninstall = null
                    showActionDialog = false
                    selectedPackage = null
                },
                onDismiss = {
                    showUninstallConfirmation = false
                    packageToUninstall = null
                }
            )
        }

        RootAccessRequiredBanner(
            visible = showBanner,
            onDismiss = { viewModel.dismissRootBanner() }
        )
    }
}

private fun getPackageActions(
    packageInfo: Package,
    onShowForceStopConfirmation: (Package) -> Unit,
    onShowEnableDisableConfirmation: (Package, Boolean) -> Unit,
    onShowUninstallConfirmation: (Package) -> Unit
): List<ActionItem> {
    val actions = mutableListOf<ActionItem>()

    if (packageInfo.isEnabled) {
        actions.add(
            ActionItem(
                title = "Disable Package",
                description = "Stop this app from running",
                icon = Icons.Default.Block,
                onClick = { onShowEnableDisableConfirmation(packageInfo, false) }
            )
        )
    } else {
        actions.add(
            ActionItem(
                title = "Enable Package",
                description = "Allow this app to run",
                icon = Icons.Default.PlayArrow,
                onClick = { onShowEnableDisableConfirmation(packageInfo, true) }
            )
        )
    }

    actions.add(
        ActionItem(
            title = "Force Stop",
            description = if (packageInfo.isEnabled) "Immediately stop all processes" else "Force stop (if running)",
            icon = Icons.Default.Stop,
            onClick = { onShowForceStopConfirmation(packageInfo) }
        )
    )

    actions.add(
        ActionItem(
            title = "Uninstall",
            description = if (packageInfo.type == PackageType.SYSTEM) "Remove system package (DANGEROUS)" else "Remove this app from device",
            icon = Icons.Default.Delete,
            isDestructive = true,
            onClick = { onShowUninstallConfirmation(packageInfo) }
        )
    )

    return actions
}

@Composable
private fun ForceStopConfirmationDialog(
    packageInfo: Package,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSystemPackage = packageInfo.type == PackageType.SYSTEM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_SMALL)
            ) {
                Icon(
                    imageVector = if (isSystemPackage) Icons.Default.Warning else Icons.Default.Stop,
                    contentDescription = null,
                    tint = if (isSystemPackage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isSystemPackage) "System Package Warning" else "Force Stop App",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "You are about to force stop:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                Text(
                    text = packageInfo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

                if (isSystemPackage) {
                    Text(
                        text = "DANGER: This is a system package. Force stopping system packages can:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    Text(
                        text = "• Break core Android functionality\n• Cause system instability\n• Require device restart\n• Potentially brick your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    Text(
                        text = "Only proceed if you know what you're doing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = if (packageInfo.isEnabled) {
                            "This will immediately stop all processes for this app. The app will need to be manually restarted."
                        } else {
                            "This will force stop any background processes for this disabled app."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isSystemPackage) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(
                    text = if (isSystemPackage) "I Know The Risk" else "Force Stop",
                    color = if (isSystemPackage) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EnableDisableConfirmationDialog(
    packageInfo: Package,
    isEnabling: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSystemPackage = packageInfo.type == PackageType.SYSTEM
    val actionText = if (isEnabling) "enable" else "disable"
    val actionTextCapitalized = if (isEnabling) "Enable" else "Disable"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_SMALL)
            ) {
                Icon(
                    imageVector = if (isSystemPackage) Icons.Default.Warning else if (isEnabling) Icons.Default.PlayArrow else Icons.Default.Block,
                    contentDescription = null,
                    tint = if (isSystemPackage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isSystemPackage) "System Package Warning" else "$actionTextCapitalized Package",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "You are about to $actionText:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                Text(
                    text = packageInfo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

                if (isSystemPackage) {
                    Text(
                        text = "DANGER: This is a system package. ${actionTextCapitalized}ing system packages can:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    Text(
                        text = if (isEnabling) {
                            "• Re-enable potentially unwanted system features\n• Restore system services you disabled for privacy\n• Affect system stability\n• Override your privacy settings"
                        } else {
                            "• Break core Android functionality\n• Cause system crashes and instability\n• Make device unusable\n• Require factory reset to fix"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    Text(
                        text = "Only proceed if you know what you're doing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = if (isEnabling) {
                            "This will allow the app to run normally and receive updates."
                        } else {
                            "This will prevent the app from running until you manually enable it again."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isSystemPackage) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(
                    text = if (isSystemPackage) "I Know The Risk" else actionTextCapitalized,
                    color = if (isSystemPackage) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun UninstallConfirmationDialog(
    packageInfo: Package,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSystemPackage = packageInfo.type == PackageType.SYSTEM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_SMALL)
            ) {
                Icon(
                    imageVector = if (isSystemPackage) Icons.Default.Warning else Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isSystemPackage) "System Package Warning" else "Uninstall Package",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "You are about to uninstall:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                Text(
                    text = packageInfo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                Text(
                    text = packageInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))

                if (isSystemPackage) {
                    Text(
                        text = "EXTREME DANGER: This is a system package. Uninstalling system packages can:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    Text(
                        text = "• Cause immediate boot loops\n• Make your device completely unusable\n• Require factory reset or reflashing\n• Break core Android functionality\n• Render system permanently damaged",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    Text(
                        text = "This action is irreversible and extremely dangerous.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "This will permanently remove the app and all its data from your device. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = if (isSystemPackage) "I Know The Risk" else "Uninstall",
                    color = MaterialTheme.colorScheme.onError
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
