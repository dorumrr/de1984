package io.github.dorumrr.de1984.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.dorumrr.de1984.utils.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedFilterDialog(
    filterState: FilterState,
    onFilterChange: (FilterState) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(Constants.UI.SPACING_STANDARD),
            elevation = CardDefaults.cardElevation(defaultElevation = Constants.UI.SPACING_SMALL)
        ) {
            Column(
                modifier = Modifier.padding(Constants.UI.SPACING_LARGE)
            ) {
                Text(
                    text = "Advanced Filters",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Constants.UI.SPACING_STANDARD)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_STANDARD)
                ) {
                    item {
                        FilterSection(
                            title = "Package Type",
                            icon = Icons.Default.Apps
                        ) {
                            Column(modifier = Modifier.selectableGroup()) {
                                PackageType.values().forEach { type ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = filterState.packageTypes.contains(type),
                                                onClick = {
                                                    val newTypes = if (filterState.packageTypes.contains(type)) {
                                                        filterState.packageTypes - type
                                                    } else {
                                                        filterState.packageTypes + type
                                                    }
                                                    onFilterChange(filterState.copy(packageTypes = newTypes))
                                                },
                                                role = Role.Checkbox
                                            )
                                            .padding(vertical = Constants.UI.SPACING_TINY),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = filterState.packageTypes.contains(type),
                                            onCheckedChange = null
                                        )
                                        Text(
                                            text = type.displayName,
                                            modifier = Modifier.padding(start = Constants.UI.SPACING_SMALL)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        FilterSection(
                            title = "Network Access",
                            icon = Icons.Default.NetworkCheck
                        ) {
                            Column(modifier = Modifier.selectableGroup()) {
                                NetworkAccessType.values().forEach { access ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = filterState.networkAccess.contains(access),
                                                onClick = {
                                                    val newAccess = if (filterState.networkAccess.contains(access)) {
                                                        filterState.networkAccess - access
                                                    } else {
                                                        filterState.networkAccess + access
                                                    }
                                                    onFilterChange(filterState.copy(networkAccess = newAccess))
                                                },
                                                role = Role.Checkbox
                                            )
                                            .padding(vertical = Constants.UI.SPACING_TINY),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = filterState.networkAccess.contains(access),
                                            onCheckedChange = null
                                        )
                                        Text(
                                            text = access.displayName,
                                            modifier = Modifier.padding(start = Constants.UI.SPACING_SMALL)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        FilterSection(
                            title = "Sort By",
                            icon = Icons.AutoMirrored.Filled.Sort
                        ) {
                            Column(modifier = Modifier.selectableGroup()) {
                                SortOption.values().forEach { option ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = filterState.sortBy == option,
                                                onClick = {
                                                    onFilterChange(filterState.copy(sortBy = option))
                                                },
                                                role = Role.RadioButton
                                            )
                                            .padding(vertical = Constants.UI.SPACING_TINY),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = filterState.sortBy == option,
                                            onClick = null
                                        )
                                        Text(
                                            text = option.displayName,
                                            modifier = Modifier.padding(start = Constants.UI.SPACING_SMALL)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        FilterSection(
                            title = "Sort Direction",
                            icon = Icons.Default.SwapVert
                        ) {
                            Row(
                                modifier = Modifier.selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_STANDARD)
                            ) {
                                SortDirection.values().forEach { direction ->
                                    Row(
                                        modifier = Modifier
                                            .selectable(
                                                selected = filterState.sortDirection == direction,
                                                onClick = {
                                                    onFilterChange(filterState.copy(sortDirection = direction))
                                                },
                                                role = Role.RadioButton
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = filterState.sortDirection == direction,
                                            onClick = null
                                        )
                                        Text(
                                            text = direction.displayName,
                                            modifier = Modifier.padding(start = Constants.UI.SPACING_TINY)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Constants.UI.SPACING_STANDARD),
                    horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_SMALL, Alignment.End)
                ) {
                    TextButton(onClick = {
                        onFilterChange(FilterState())
                    }) {
                        Text("Reset")
                    }
                    
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    
                    Button(onClick = {
                        onApply()
                        onDismiss()
                    }) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(Constants.UI.SPACING_STANDARD)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = Constants.UI.SPACING_MEDIUM)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Constants.UI.SPACING_SMALL)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}

data class FilterState(
    val packageTypes: Set<PackageType> = setOf(PackageType.USER, PackageType.SYSTEM),
    val networkAccess: Set<NetworkAccessType> = setOf(NetworkAccessType.HAS_ACCESS, NetworkAccessType.NO_ACCESS),
    val sortBy: SortOption = SortOption.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val searchQuery: String = ""
)

enum class PackageType(val displayName: String) {
    USER("User Apps"),
    SYSTEM("System Apps"),
    UPDATED_SYSTEM("Updated System Apps")
}

enum class NetworkAccessType(val displayName: String) {
    HAS_ACCESS("Has Network Access"),
    NO_ACCESS("No Network Access"),
    UNKNOWN("Unknown")
}

enum class SortOption(val displayName: String) {
    NAME("Name"),
    INSTALL_TIME("Install Date"),
    UPDATE_TIME("Last Updated"),
    SIZE("App Size"),
    NETWORK_USAGE("Network Usage")
}

enum class SortDirection(val displayName: String) {
    ASCENDING("A-Z / Oldest First"),
    DESCENDING("Z-A / Newest First")
}
