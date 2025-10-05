package io.github.dorumrr.de1984.ui.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.dorumrr.de1984.data.common.CapabilityLevel
import io.github.dorumrr.de1984.presentation.viewmodel.PermissionsViewModel
import io.github.dorumrr.de1984.utils.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Constants.UI.SPACING_STANDARD)
    ) {
        Text(
            text = "De1984 • Permissions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = Constants.UI.SPACING_LARGE)
        )

        SystemCapabilityCard(
            capabilityLevel = uiState.systemCapabilities.overallCapabilityLevel,
            modifier = Modifier.padding(bottom = Constants.UI.SPACING_STANDARD)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_MEDIUM)
        ) {
            items(uiState.permissionItems) { permissionItem ->
                PermissionCard(
                    permissionItem = permissionItem,
                    onRequestPermission = { permission ->
                        when (permission.type) {
                            PermissionType.USAGE_STATS -> {
                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                context.startActivity(intent)
                            }
                            PermissionType.PACKAGE_QUERY -> {
                                // This is typically granted automatically for system apps
                            }
                            PermissionType.BASIC_PERMISSIONS -> {
                                viewModel.requestBasicPermissions()
                            }
                            PermissionType.ROOT_ACCESS -> {
                                viewModel.showRootAccessInfo()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SystemCapabilityCard(
    capabilityLevel: CapabilityLevel,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (capabilityLevel) {
                CapabilityLevel.FULL_ROOT -> MaterialTheme.colorScheme.primaryContainer
                CapabilityLevel.LIMITED -> MaterialTheme.colorScheme.tertiaryContainer
                CapabilityLevel.MINIMAL -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(Constants.UI.SPACING_STANDARD)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = Constants.UI.SPACING_SMALL)
            ) {
                Icon(
                    imageVector = when (capabilityLevel) {
                        CapabilityLevel.FULL_ROOT -> Icons.Default.Security
                        CapabilityLevel.LIMITED -> Icons.Default.Warning
                        CapabilityLevel.MINIMAL -> Icons.Default.Error
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(end = Constants.UI.SPACING_SMALL)
                )
                Text(
                    text = "System Capability Level",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = when (capabilityLevel) {
                    CapabilityLevel.FULL_ROOT -> "Full Root Access"
                    CapabilityLevel.LIMITED -> "Limited Access"
                    CapabilityLevel.MINIMAL -> "Minimal Access"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = Constants.UI.SPACING_TINY)
            )
            
            Text(
                text = when (capabilityLevel) {
                    CapabilityLevel.FULL_ROOT -> "All features available with root privileges"
                    CapabilityLevel.LIMITED -> "Basic monitoring features only"
                    CapabilityLevel.MINIMAL -> "Very limited functionality"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PermissionCard(
    permissionItem: PermissionItem,
    onRequestPermission: (PermissionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Constants.UI.SPACING_STANDARD)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = permissionItem.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = permissionItem.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Constants.UI.SPACING_TINY)
                    )
                    Text(
                        text = "Required for: ${permissionItem.requiredFor}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Constants.UI.SPACING_EXTRA_TINY)
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (permissionItem.isGranted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (permissionItem.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Constants.UI.ICON_SIZE_TINY)
                        )
                        Text(
                            text = if (permissionItem.isGranted) "Granted" else "Required",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (permissionItem.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = Constants.UI.SPACING_TINY)
                        )
                    }

                    if (!permissionItem.isGranted && permissionItem.canRequest) {
                        Button(
                            onClick = { onRequestPermission(permissionItem) },
                            modifier = Modifier.padding(top = Constants.UI.SPACING_SMALL)
                        ) {
                            Text("Grant")
                        }
                    } else if (!permissionItem.isGranted && !permissionItem.canRequest) {
                        OutlinedButton(
                            onClick = { onRequestPermission(permissionItem) },
                            modifier = Modifier.padding(top = Constants.UI.SPACING_SMALL)
                        ) {
                            Text("Info")
                        }
                    }
                }
            }
        }
    }
}

data class PermissionItem(
    val name: String,
    val description: String,
    val requiredFor: String,
    val isGranted: Boolean,
    val canRequest: Boolean,
    val type: PermissionType
)

enum class PermissionType {
    BASIC_PERMISSIONS,
    USAGE_STATS,
    PACKAGE_QUERY,
    ROOT_ACCESS
}
