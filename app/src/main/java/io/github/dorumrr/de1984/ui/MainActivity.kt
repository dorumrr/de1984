package io.github.dorumrr.de1984.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint

import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.service.PackageMonitoringService

import io.github.dorumrr.de1984.ui.navigation.De1984Navigation
import io.github.dorumrr.de1984.ui.permissions.StartupPermissionFlow
import io.github.dorumrr.de1984.ui.theme.De1984Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var permissionManager: PermissionManager



    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionCheckTrigger++
    }

    private var permissionCheckTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        PackageMonitoringService.startMonitoring(this)



        setContent {
            De1984Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    De1984App(
                        permissionManager = permissionManager,
                        onRequestPermissions = { permissions ->
                            requestPermissionLauncher.launch(permissions.toTypedArray())
                        },
                        permissionCheckTrigger = permissionCheckTrigger
                    )
                }
            }
        }
    }


}

@Composable
fun De1984App(
    permissionManager: PermissionManager,
    onRequestPermissions: (List<String>) -> Unit,
    permissionCheckTrigger: Int = 0
) {
    var permissionsCompleted by remember { mutableStateOf(false) }
    var showFirewallStartDialog by remember { mutableStateOf(false) }

    val needsNotificationPermission = remember { !permissionManager.hasNotificationPermission() }

    LaunchedEffect(Unit) {
        if (!needsNotificationPermission) {
            permissionsCompleted = true
        }
    }

    when {
        needsNotificationPermission && !permissionsCompleted -> {
            StartupPermissionFlow(
                permissionManager = permissionManager,
                onRequestPermissions = onRequestPermissions,
                permissionCheckTrigger = permissionCheckTrigger,
                onPermissionsComplete = {
                    permissionsCompleted = true
                    showFirewallStartDialog = true
                }
            )
        }
        else -> {
            De1984Navigation(
                showFirewallStartDialog = showFirewallStartDialog,
                onFirewallStartDialogDismiss = { showFirewallStartDialog = false }
            )
        }
    }

}

@Preview(showBackground = true)
@Composable
fun De1984AppPreview() {
    De1984Theme {
        De1984Navigation()
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun De1984AppDarkPreview() {
    De1984Theme(darkTheme = true) {
        De1984Navigation()
    }
}

