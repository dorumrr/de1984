package io.github.dorumrr.de1984.ui.permissions

import androidx.compose.runtime.*
import io.github.dorumrr.de1984.data.common.PermissionManager

@Composable
fun StartupPermissionFlow(
    permissionManager: PermissionManager,
    onRequestPermissions: (List<String>) -> Unit,
    permissionCheckTrigger: Int = 0,
    onPermissionsComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf<PermissionStep?>(null) }

    LaunchedEffect(Unit) {
        val hasNotificationPermission = permissionManager.hasNotificationPermission()
        if (hasNotificationPermission) {
            onPermissionsComplete()
        } else {
            val runtimePermissions = permissionManager.getRuntimePermissions()
            onRequestPermissions(runtimePermissions)
            currentStep = PermissionStep.WAITING_FOR_SYSTEM_PERMISSION
        }
    }

    LaunchedEffect(permissionCheckTrigger) {
        if (permissionCheckTrigger > 0 && currentStep == PermissionStep.WAITING_FOR_SYSTEM_PERMISSION) {
            onPermissionsComplete()
        }
    }

}

private enum class PermissionStep {
    WAITING_FOR_SYSTEM_PERMISSION
}
