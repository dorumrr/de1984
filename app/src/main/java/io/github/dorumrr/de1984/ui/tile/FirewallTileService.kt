package io.github.dorumrr.de1984.ui.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
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
        
        /**
         * Request the system to update the tile state.
         * This forces the tile to call onStartListening() which will fetch the current state.
         */
        fun requestTileUpdate(context: Context) {
            try {
                AppLogger.d(TAG, "Requesting tile state update")
                requestListeningState(
                    context,
                    ComponentName(context, FirewallTileService::class.java)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to request tile update", e)
            }
        }
    }

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        AppLogger.d(TAG, "Tile listening started")

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val firewallManager = (application as De1984Application).dependencies.firewallManager
        
        // Force immediate tile update with current state
        val currentState = firewallManager.firewallState.value
        AppLogger.d(TAG, "Initial state on startListening: $currentState")
        updateTile(currentState)

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
        
        // Use StateFlow state (more reliable than isActive() which uses deprecated APIs)
        val currentState = firewallManager.firewallState.value
        val isActive = currentState is FirewallManager.FirewallState.Running || 
                       currentState is FirewallManager.FirewallState.Starting
        
        // Also check isActive() for redundancy
        val isActiveBackend = firewallManager.isActive()
        
        AppLogger.d(TAG, "Current state: $currentState")
        AppLogger.d(TAG, "isActive from StateFlow: $isActive")
        AppLogger.d(TAG, "isActive from backend: $isActiveBackend")
        
        // Consider active if EITHER check returns true
        val shouldTreatAsActive = isActive || isActiveBackend
        AppLogger.d(TAG, "Final decision - treat as active: $shouldTreatAsActive")
        
        if (shouldTreatAsActive) {
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
            // Firewall is OFF - show "Starting..." immediately for responsive UX
            AppLogger.d(TAG, "Firewall is OFF, showing Starting state immediately...")
            setStartingState()
            
            // Then send broadcast to FirewallToggleReceiver (same as widget)
            AppLogger.d(TAG, "Sending broadcast to FirewallToggleReceiver...")
            val toggleIntent = Intent(this, io.github.dorumrr.de1984.data.receiver.FirewallToggleReceiver::class.java).apply {
                action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
            }
            sendBroadcast(toggleIntent)
            AppLogger.d(TAG, "Broadcast sent to FirewallToggleReceiver")
            
            // Collapse the quick settings panel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val statusBarService = getSystemService("statusbar")
                    val statusBarManager = Class.forName("android.app.StatusBarManager")
                    val collapse = statusBarManager.getMethod("collapsePanels")
                    collapse.invoke(statusBarService)
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Could not collapse panels: ${e.message}")
                }
            }
        }
    }

    /**
     * Immediately set the tile to "Starting..." state for responsive UX.
     * Called when user clicks to start the firewall.
     */
    private fun setStartingState() {
        try {
            val tile = qsTile ?: return
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.tile_label_firewall)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_starting)
            }
            tile.contentDescription = getString(R.string.tile_description_firewall_starting)
            tile.updateTile()
            AppLogger.d(TAG, "Tile set to STARTING state")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting starting state", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateTile(state: FirewallManager.FirewallState) {
        try {
            val tile = qsTile ?: return
            
            // Always use "De1984" as label, state shown in subtitle
            tile.label = getString(R.string.tile_label_firewall)
            
            when (state) {
                is FirewallManager.FirewallState.Running -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_subtitle_active)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_on, state.backend.name)
                }
                FirewallManager.FirewallState.Stopped -> {
                    tile.state = Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_subtitle_inactive)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_off)
                }
                is FirewallManager.FirewallState.Starting -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_subtitle_starting)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_starting)
                }
                is FirewallManager.FirewallState.Error -> {
                    tile.state = Tile.STATE_INACTIVE
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
