package io.github.dorumrr.de1984.ui.firewall

import android.app.Activity
import android.content.Context
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.ui.common.*
import io.github.dorumrr.de1984.utils.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    viewModel: FirewallViewModel,
    settingsViewModel: io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val showBanner = viewModel.showRootBanner
    var showActionSheet by remember { mutableStateOf(false) }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }

    val selectedPackage = remember(uiState.packages, selectedPackageName) {
        selectedPackageName?.let { packageName ->
            uiState.packages.find { it.packageName == packageName }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDefaultPolicy()
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
                    chips = (Constants.Firewall.PACKAGE_TYPE_FILTERS + Constants.Firewall.NETWORK_STATE_FILTERS).map { filter ->
                        FilterChipData(
                            label = filter,
                            selected = when (filter) {
                                in Constants.Firewall.PACKAGE_TYPE_FILTERS -> uiState.filterState.packageType == filter
                                in Constants.Firewall.NETWORK_STATE_FILTERS -> uiState.filterState.networkState == filter
                                else -> false
                            },
                            onClick = {
                                when (filter) {
                                    in Constants.Firewall.PACKAGE_TYPE_FILTERS -> {
                                        viewModel.setPackageTypeFilter(filter)
                                    }
                                    in Constants.Firewall.NETWORK_STATE_FILTERS -> {
                                        val newState = if (uiState.filterState.networkState == filter) null else filter
                                        viewModel.setNetworkStateFilter(newState)
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
                            key = { networkPackage -> networkPackage.packageName }
                        ) { networkPackage ->
                            NetworkPackageItem(
                                networkPackage = networkPackage,
                                showRealIcons = settingsUiState.showAppIcons,
                                onClick = {
                                    selectedPackageName = networkPackage.packageName
                                    showActionSheet = true
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
            if (showActionSheet) {
                FirewallActionSheet(
                    isVisible = showActionSheet,
                    networkPackage = pkg,
                    showRealIcons = settingsUiState.showAppIcons,
                    onDismiss = {
                        showActionSheet = false
                        selectedPackageName = null
                    },
                    onSetWifiBlocking = { blocked ->
                        viewModel.setWifiBlocking(pkg.packageName, blocked)
                    },
                    onSetMobileBlocking = { blocked ->
                        viewModel.setMobileBlocking(pkg.packageName, blocked)
                    },
                    onSetRoamingBlocking = { blocked ->
                        viewModel.setRoamingBlocking(pkg.packageName, blocked)
                    }
                )
            }
        }

        RootAccessRequiredBanner(
            visible = showBanner,
            onDismiss = { viewModel.dismissRootBanner() }
        )
    }
}

@Composable
private fun NetworkPackageItem(
    networkPackage: NetworkPackage,
    showRealIcons: Boolean = true,
    onClick: () -> Unit
) {
    val statusColor = when {
        networkPackage.isFullyAllowed -> MaterialTheme.colorScheme.tertiary
        networkPackage.isFullyBlocked -> MaterialTheme.colorScheme.error
        networkPackage.isPartiallyBlocked -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(Constants.UI.SPACING_STANDARD),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PackageIcon(
            packageName = networkPackage.packageName,
            fallbackIcon = networkPackage.icon,
            isEnabled = networkPackage.isFullyAllowed,
            showRealIcons = showRealIcons,
            modifier = Modifier.size(Constants.UI.ICON_SIZE_LARGE)
        )

        Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = networkPackage.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))
                Box(
                    modifier = Modifier
                        .size(Constants.UI.STATUS_DOT_SIZE)
                        .background(statusColor, shape = CircleShape)
                )
            }

            Text(
                text = "${networkPackage.packageName} • ${networkPackage.type.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))
        Column(
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = networkPackage.networkState,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (networkPackage.hasInternetPermission) "Internet" else "Network",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirewallActionSheet(
    isVisible: Boolean,
    networkPackage: NetworkPackage,
    showRealIcons: Boolean,
    onDismiss: () -> Unit,
    onSetWifiBlocking: (Boolean) -> Unit,
    onSetMobileBlocking: (Boolean) -> Unit,
    onSetRoamingBlocking: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val hasCellularCapability = remember {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(networkPackage.wifiBlocked, networkPackage.mobileBlocked, networkPackage.roamingBlocked) {
        isLoading = false
    }

    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onDismiss()
                        }
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    shape = RoundedCornerShape(topStart = Constants.UI.BOTTOM_SHEET_CORNER_RADIUS, topEnd = Constants.UI.BOTTOM_SHEET_CORNER_RADIUS),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = Constants.UI.ELEVATION_CARD
                ) {
                    Box {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Constants.UI.SPACING_LARGE)
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PackageIcon(
                                packageName = networkPackage.packageName,
                                fallbackIcon = networkPackage.icon,
                                isEnabled = networkPackage.isFullyAllowed,
                                showRealIcons = showRealIcons,
                                modifier = Modifier.size(Constants.UI.ICON_SIZE_EXTRA_LARGE)
                            )
                            Spacer(modifier = Modifier.width(Constants.UI.SPACING_STANDARD))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = networkPackage.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = networkPackage.packageName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))

                        Text(
                            text = "Currently: ${networkPackage.networkState}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_MEDIUM)
                        ) {
                            NetworkTypeToggle(
                                label = "WiFi",
                                isBlocked = networkPackage.wifiBlocked,
                                onToggle = { blocked ->
                                    if (blocked != networkPackage.wifiBlocked) {
                                        isLoading = true
                                        onSetWifiBlocking(blocked)
                                    }
                                }
                            )

                            HorizontalDivider()

                            NetworkTypeToggle(
                                label = "Mobile Data",
                                isBlocked = networkPackage.mobileBlocked,
                                onToggle = { blocked ->
                                    if (blocked != networkPackage.mobileBlocked) {
                                        isLoading = true
                                        onSetMobileBlocking(blocked)
                                    }
                                }
                            )

                            // Only show Roaming option if device has cellular capability
                            if (hasCellularCapability) {
                                HorizontalDivider()

                                NetworkTypeToggle(
                                    label = "Roaming",
                                    isBlocked = networkPackage.roamingBlocked,
                                    enabled = !networkPackage.mobileBlocked,
                                    onToggle = { blocked ->
                                        if (blocked != networkPackage.roamingBlocked) {
                                            isLoading = true
                                            onSetRoamingBlocking(blocked)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Constants.UI.ICON_SIZE_LARGE),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun NetworkTypeToggle(
    label: String,
    isBlocked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_SMALL)
        ) {
            FilterChip(
                selected = !isBlocked,
                onClick = { if (enabled) onToggle(false) },
                enabled = enabled,
                label = { Text("Allow") },
                leadingIcon = if (!isBlocked) {
                    { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(Constants.UI.ICON_SIZE_TINY)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4CAF50),
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White
                )
            )

            FilterChip(
                selected = isBlocked,
                onClick = { if (enabled) onToggle(true) },
                enabled = enabled,
                label = { Text("Block") },
                leadingIcon = if (isBlocked) {
                    { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(Constants.UI.ICON_SIZE_TINY)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    }
}

