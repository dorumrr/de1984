package io.github.dorumrr.de1984.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dorumrr.de1984.utils.Constants

@Composable
fun SystemStatusBanner(
    onNavigateToPermissions: () -> Unit = {},
    hasBasicPermissions: Boolean = true,
    hasEnhancedPermissions: Boolean = false,
    hasAdvancedPermissions: Boolean = false,
    hasRootAccess: Boolean = false
) {
    val permissionIssues = !hasBasicPermissions || !hasEnhancedPermissions
    val rootIssue = !hasRootAccess && hasAdvancedPermissions
    
    if (permissionIssues || rootIssue) {
        val (title, subtitle, isClickable) = when {
            permissionIssues -> Triple(
                "Permission misconfiguration detected",
                "Tap to configure required permissions",
                true
            )
            rootIssue -> Triple(
                "Root access required",
                Constants.RootAccess.DESC_NOT_AVAILABLE,
                false
            )
            else -> return
        }
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Constants.UI.SPACING_STANDARD, vertical = Constants.UI.SPACING_SMALL)
                .let { if (isClickable) it.clickable { onNavigateToPermissions() } else it },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Constants.UI.SPACING_MEDIUM),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(Constants.UI.ICON_SIZE_SMALL)
                )
                
                Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
                
                if (isClickable) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Navigate to permissions",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(Constants.UI.ICON_SIZE_SMALL)
                    )
                }
            }
        }
    }
}
