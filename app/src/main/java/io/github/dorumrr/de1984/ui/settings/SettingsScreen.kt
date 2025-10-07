package io.github.dorumrr.de1984.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel

import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.ui.common.De1984TopBar

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    permissionViewModel: PermissionSetupViewModel = hiltViewModel(),
    onNavigateToAcknowledgements: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val permissionUiState by permissionViewModel.uiState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var lastClickTime by remember { mutableLongStateOf(0L) }
    var showRootTestDialog by remember { mutableStateOf(false) }
    var rootTestResult by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionViewModel.markNotificationPermissionRequested()
        } else {
            val activity = context as? Activity
            val shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
            } ?: false

            if (shouldShowRationale) {
                permissionViewModel.markNotificationPermissionRequested()
            }
        }

        permissionViewModel.refreshPermissions()
    }

    LaunchedEffect(Unit) {
        permissionViewModel.refreshPermissions()
        settingsViewModel.requestRootPermission()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionViewModel.refreshPermissions()
                settingsViewModel.requestRootPermission()

            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Constants.UI.SPACING_STANDARD),
            verticalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_STANDARD),
            state = listState
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Constants.UI.PADDING_CARD_LARGE)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.de1984_icon),
                                contentDescription = Constants.App.LOGO_DESCRIPTION,
                                modifier = Modifier.size(Constants.UI.ICON_SIZE_EXTRA_LARGE),
                                tint = Color.Unspecified
                            )

                            Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = Constants.App.NAME,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                                Text(
                                    text = "Version ${settingsUiState.appVersion}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/ossdev"))
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFD700),
                                    contentColor = Color.Black
                                ),
                                border = androidx.compose.foundation.BorderStroke(Constants.UI.BORDER_WIDTH_THIN, Color.Black),
                                contentPadding = PaddingValues(horizontal = Constants.UI.SPACING_MEDIUM, vertical = Constants.UI.SPACING_SMALL)
                            ) {
                                Text("Donate ♥️")
                            }
                        }

                        Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))

                        Text(
                            text = "Privacy isn’t default. Take it back with De1984 Firewall and package control.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                    }
                }
            }

            item {
                OptionsCard(
                    settingsUiState = settingsUiState,
                    onShowAppIconsChanged = { enabled ->
                        settingsViewModel.setShowAppIcons(enabled)
                    },
                    onNewAppNotificationsChanged = { enabled ->
                        settingsViewModel.setNewAppNotifications(enabled)
                    },
                    onDefaultFirewallPolicyChanged = { policy ->
                        settingsViewModel.setDefaultFirewallPolicy(policy)
                    }
                )
            }

            item {
                val rootStatus by settingsViewModel.rootStatus.collectAsState()
                PermissionSetupCard(
                    permissionUiState = permissionUiState,
                    rootStatus = rootStatus,
                    onGrantBasicPermissions = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val permission = Manifest.permission.POST_NOTIFICATIONS

                            val isGranted = ContextCompat.checkSelfPermission(
                                context,
                                permission
                            ) == PackageManager.PERMISSION_GRANTED

                            if (isGranted) {
                                permissionViewModel.refreshPermissions()
                            } else {
                                val activity = context as? Activity
                                val shouldShowRationale = activity?.let {
                                    ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
                                } ?: false

                                val hasRequestedPermission = permissionViewModel.hasRequestedNotificationPermission()

                                val isFirstTime = !hasRequestedPermission
                                val canShowDialog = shouldShowRationale || isFirstTime

                                if (canShowDialog) {
                                    try {
                                        notificationPermissionLauncher.launch(permission)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    }
                                } else {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        } else {
                            permissionViewModel.refreshPermissions()
                        }
                    },
                    onTestRootAccess = {
                        rootTestResult = "🔄 Testing root access..."
                        showRootTestDialog = true

                        if (permissionUiState.isLoading) {
                            rootTestResult = "🔄 Initializing... Testing root access..."
                        }

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime > 100) {
                            lastClickTime = currentTime
                            coroutineScope.launch {
                                try {
                                    val result = testRootAccess()
                                    rootTestResult = result
                                    permissionViewModel.refreshPermissions()
                                } catch (e: Exception) {
                                    rootTestResult = "❌ Root test failed.\n\nError: ${e.message}"
                                }
                            }
                        } else {
                            rootTestResult = "⏳ Please wait a moment before testing again..."
                        }
                    }
                )
            }



            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigateToAcknowledgements?.invoke()
                            }
                            .padding(Constants.UI.PADDING_CARD_LARGE),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Acknowledgements",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "View acknowledgements",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Constants.UI.SPACING_STANDARD),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Giving Privacy its due, by ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Doru Moraru",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }



        if (showRootTestDialog && rootTestResult != null) {
            AlertDialog(
                onDismissRequest = {
                    showRootTestDialog = false
                    rootTestResult = null
                },
                title = {
                    Text(
                        text = if (rootTestResult!!.startsWith("✅")) "Root Access Available" else "Root Access Not Available"
                    )
                },
                text = {
                    Text(rootTestResult!!)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRootTestDialog = false
                            rootTestResult = null
                        }
                    ) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
private fun OptionsCard(
    settingsUiState: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState,
    onShowAppIconsChanged: (Boolean) -> Unit,
    onNewAppNotificationsChanged: (Boolean) -> Unit,
    onDefaultFirewallPolicyChanged: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Constants.UI.SPACING_LARGE)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚙️",
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Options",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                    Text(
                        text = "Configure behavior and settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Firewall Blocks All by Default",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                    Text(
                        text = if (settingsUiState.defaultFirewallPolicy == Constants.Settings.POLICY_BLOCK_ALL) {
                            "Block All, Allow Wanted"
                        } else {
                            "Allow All, Block Unwanted"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))

                Switch(
                    checked = settingsUiState.defaultFirewallPolicy == Constants.Settings.POLICY_BLOCK_ALL,
                    onCheckedChange = { isBlockAll ->
                        val newPolicy = if (isBlockAll) {
                            Constants.Settings.POLICY_BLOCK_ALL
                        } else {
                            Constants.Settings.POLICY_ALLOW_ALL
                        }
                        onDefaultFirewallPolicyChanged(newPolicy)
                    }
                )
            }

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Show App Icons",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                    Text(
                        text = "Might speed up the app when disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))

                Switch(
                    checked = settingsUiState.showAppIcons,
                    onCheckedChange = onShowAppIconsChanged
                )
            }

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "New App Notifications",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                    Text(
                        text = "Show notifications when new apps with network permissions are installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))

                Switch(
                    checked = settingsUiState.newAppNotifications,
                    onCheckedChange = onNewAppNotificationsChanged
                )
            }

        }
    }
}

@Composable
private fun PermissionSetupCard(
    permissionUiState: io.github.dorumrr.de1984.ui.permissions.PermissionSetupUiState,
    rootStatus: RootStatus,
    onGrantBasicPermissions: () -> Unit,
    onTestRootAccess: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Constants.UI.SPACING_LARGE)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔐",
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                    Text(
                        text = "Status and advanced configuration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

            PermissionTierSection(
                title = "Basic Functionality",
                description = "View installed packages and basic information",
                status = if (permissionUiState.hasBasicPermissions) "Completed" else "Setup Required",
                isComplete = permissionUiState.hasBasicPermissions,
                permissions = permissionUiState.basicPermissions,
                setupButtonText = "Grant Permission",
                onSetupClick = if (!permissionUiState.hasBasicPermissions) onGrantBasicPermissions else null
            )

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            PermissionTierSection(
                title = "Background Process",
                description = "Prevents Android from killing the firewall service to save battery. Critical for VPN reliability.",
                status = if (permissionUiState.hasBatteryOptimizationExemption) "Completed" else "Setup Required",
                isComplete = permissionUiState.hasBatteryOptimizationExemption,
                permissions = permissionUiState.batteryOptimizationInfo,
                setupButtonText = "Grant Permission",
                onSetupClick = if (!permissionUiState.hasBatteryOptimizationExemption) {
                    {
                        try {
                            val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            } else {
                                null
                            }
                            intent?.let { context.startActivity(it) }
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = android.net.Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            }
                        }
                    }
                } else null
            )

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            PermissionTierSection(
                title = "Advanced Operations",
                description = "Package management and system-level operations",
                status = if (permissionUiState.hasAdvancedPermissions) "Completed" else "Root Required",
                isComplete = permissionUiState.hasAdvancedPermissions,
                permissions = permissionUiState.advancedPermissions,
                setupButtonText = "Test Root Access",
                onSetupClick = if (permissionUiState.hasAdvancedPermissions ||
                                   rootStatus == RootStatus.ROOTED_NO_PERMISSION ||
                                   rootStatus == RootStatus.NOT_ROOTED) null else onTestRootAccess,
                rootStatus = rootStatus
            )
        }
    }
}

@Composable
private fun PermissionTierCard(
    title: String,
    description: String,
    status: String,
    isComplete: Boolean,
    permissions: List<PermissionInfo>,
    onSetupClick: (() -> Unit)? = null,
    setupButtonText: String = "Setup"
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Constants.UI.SPACING_STANDARD)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(
                            horizontal = Constants.UI.BADGE_PADDING_HORIZONTAL,
                            vertical = Constants.UI.BADGE_PADDING_VERTICAL
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (permissions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

                permissions.forEach { permission ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Constants.UI.SPACING_EXTRA_TINY),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (permission.isGranted) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Cancel
                            },
                            contentDescription = null,
                            tint = if (permission.isGranted) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(Constants.UI.ICON_SIZE_TINY)
                        )

                        Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                        Text(
                            text = permission.name,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (!isComplete && onSetupClick != null) {
                Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

                Button(
                    onClick = onSetupClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(setupButtonText)
                }
            }
        }
    }
}

@Composable
private fun PermissionTierSection(
    title: String,
    description: String,
    status: String,
    isComplete: Boolean,
    permissions: List<PermissionInfo>,
    setupButtonText: String = "Setup",
    onSetupClick: (() -> Unit)? = null,
    rootStatus: RootStatus? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Surface(
                color = if (isComplete) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(
                        horizontal = Constants.UI.BADGE_PADDING_HORIZONTAL,
                        vertical = Constants.UI.BADGE_PADDING_VERTICAL
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (permissions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            permissions.forEach { permission ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Constants.UI.SPACING_EXTRA_TINY),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (permission.isGranted) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Cancel
                        },
                        contentDescription = null,
                        tint = if (permission.isGranted) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(Constants.UI.ICON_SIZE_TINY)
                    )

                    Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                    Text(
                        text = permission.name,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (onSetupClick != null) {
            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onSetupClick
                ) {
                    Text(setupButtonText)
                }
            }
        }

        if (rootStatus != null && rootStatus != RootStatus.ROOTED_WITH_PERMISSION) {
            Spacer(modifier = Modifier.height(Constants.UI.SPACING_MEDIUM))

            Surface(
                color = when (rootStatus) {
                    RootStatus.ROOTED_WITH_PERMISSION -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    RootStatus.ROOTED_NO_PERMISSION -> MaterialTheme.colorScheme.surfaceVariant
                    RootStatus.NOT_ROOTED -> MaterialTheme.colorScheme.surfaceVariant
                    RootStatus.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Constants.UI.SPACING_SMALL)
                ) {
                    Text(
                        text = when (rootStatus) {
                            RootStatus.ROOTED_WITH_PERMISSION -> Constants.RootAccess.STATUS_GRANTED
                            RootStatus.ROOTED_NO_PERMISSION -> Constants.RootAccess.STATUS_DENIED
                            RootStatus.NOT_ROOTED -> Constants.RootAccess.STATUS_NOT_AVAILABLE
                            RootStatus.CHECKING -> Constants.RootAccess.STATUS_CHECKING
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))

                    Text(
                        text = when (rootStatus) {
                            RootStatus.ROOTED_WITH_PERMISSION -> Constants.RootAccess.DESC_GRANTED
                            RootStatus.ROOTED_NO_PERMISSION -> Constants.RootAccess.DESC_DENIED
                            RootStatus.NOT_ROOTED -> Constants.RootAccess.DESC_NOT_AVAILABLE
                            RootStatus.CHECKING -> Constants.RootAccess.DESC_CHECKING
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (rootStatus == RootStatus.ROOTED_NO_PERMISSION || rootStatus == RootStatus.NOT_ROOTED) {
                        Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))

                        if (rootStatus == RootStatus.ROOTED_NO_PERMISSION) {
                            Text(
                                text = Constants.RootAccess.GRANT_INSTRUCTIONS_TITLE,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))
                            Text(
                                text = Constants.RootAccess.GRANT_INSTRUCTIONS_BODY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = Constants.RootAccess.ROOTING_INSTRUCTIONS_INTRO,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))

                            Text(
                                text = Constants.RootAccess.ROOTING_TOOLS_TITLE,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(Constants.UI.SPACING_TINY))
                            Text(
                                text = Constants.RootAccess.ROOTING_TOOLS_BODY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))

                            Text(
                                text = Constants.RootAccess.ROOTING_WARNING,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun testRootAccess(): String = withContext(Dispatchers.IO) {
    try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            return@withContext "❌ Root test timed out.\n\nThe root permission dialog may be waiting for your response, or root access is not available.\n\nPopular rooting apps include Magisk, KingRoot, SuperSU, and Framaroot."
        }

        if (process.exitValue() == 0) {
            val output = process.inputStream.bufferedReader().readText().trim()
            return@withContext if (output.contains("uid=0")) {
                "✅ Root access is available!\n\nAdvanced package management features are now enabled."
            } else {
                "❌ Root command executed but didn't return expected privileges.\n\nPlease check your root setup."
            }
        } else {
            val suCheck = Runtime.getRuntime().exec("which su")
            val suExists = suCheck.waitFor() == 0

            return@withContext if (suExists) {
                "❌ Root access denied.\n\nRoot is installed but permission was denied. To resolve:\n• Reinstall the app to trigger permission dialog again\n• Or manually add De1984 to your superuser app"
            } else {
                "❌ Root not available.\n\nYour device doesn't appear to be rooted. Advanced operations require root access.\n\nPopular rooting apps include Magisk, KingRoot, SuperSU, and Framaroot. Choose one that supports your device model and Android version."
            }
        }
    } catch (e: Exception) {
        return@withContext "❌ Root test failed.\n\nError: ${e.message}\n\nIf your device isn't rooted yet, try popular rooting apps like Magisk, KingRoot, SuperSU, or Framaroot."
    }
}



