package io.github.dorumrr.de1984.ui.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile for De1984 Firewall.
 * 
 * Provides a quick toggle for the firewall from the Quick Settings panel.
 * Uses StateFlow to receive real-time updates about firewall state.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FirewallTileService : TileService() {

    companion object {
        private const val TAG = "FirewallTileService"
    }

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        AppLogger.d(TAG, "Tile listening started")

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val firewallManager = (application as De1984Application).dependencies.firewallManager

        // Collect StateFlow for real-time updates
        scope?.launch {
            firewallManager.firewallState.collect { state ->
                AppLogger.d(TAG, "State changed: $state")
                updateTile(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        AppLogger.d(TAG, "Tile listening stopped")
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        AppLogger.d(TAG, "Tile clicked")
        
        val firewallManager = (application as De1984Application).dependencies.firewallManager
        val isActive = firewallManager.isActive()
        AppLogger.d(TAG, "Current firewall state: isActive=$isActive")
        
        if (isActive) {
            // Firewall is ON - open app for stop confirmation
            AppLogger.d(TAG, "Firewall is ON, opening app for stop confirmation")
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            // Firewall is OFF - start directly (like widget)
            AppLogger.d(TAG, "Firewall is OFF, starting directly...")
            
            // Collapse the quick settings panel first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // On Android 14+, we need to collapse manually
                try {
                    val statusBarService = getSystemService("statusbar")
                    val statusBarManager = Class.forName("android.app.StatusBarManager")
                    val collapse = statusBarManager.getMethod("collapsePanels")
                    collapse.invoke(statusBarService)
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Could not collapse panels: ${e.message}")
                }
            }
            
            // Use coroutine to check VPN permission and start
            scope?.launch(Dispatchers.IO) {
                val planResult = firewallManager.computeStartPlan(io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO)
                val plan = planResult.getOrNull()
                
                if (plan?.requiresVpnPermission == true) {
                    // VPN permission needed - launch transparent activity without collapsing animation
                    AppLogger.d(TAG, "VPN permission required, launching VpnPermissionActivity")
                    val vpnIntent = Intent(this@FirewallTileService, io.github.dorumrr.de1984.ui.VpnPermissionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(vpnIntent)
                } else {
                    // No VPN permission needed - start directly without any UI
                    AppLogger.d(TAG, "No VPN permission needed, starting firewall directly")
                    val result = firewallManager.startFirewall(io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO)
                    AppLogger.d(TAG, "startFirewall result: $result")
                    
                    // Update SharedPreferences
                    val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateTile(state: FirewallManager.FirewallState) {
        try {
            val tile = qsTile ?: return
            
            when (state) {
                is FirewallManager.FirewallState.Running -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = getString(R.string.tile_label_de1984_on)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = null
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_on, state.backend.name)
                }
                FirewallManager.FirewallState.Stopped -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = getString(R.string.tile_label_de1984_off)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = null
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_off)
                }
                is FirewallManager.FirewallState.Starting -> {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.label = getString(R.string.tile_label_firewall_starting)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = null
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_starting)
                }
                is FirewallManager.FirewallState.Error -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = getString(R.string.tile_label_de1984_off)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_label_firewall_error)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_error)
                }
            }
            tile.updateTile()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error updating tile", e)
        }
    }
}
