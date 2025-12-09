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
        AppLogger.d(TAG, "Tile clicked - launching MainActivity")
        
        // Launch MainActivity to show appropriate dialog (start or stop)
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
            startActivityAndCollapse(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateTile(state: FirewallManager.FirewallState) {
        try {
            val tile = qsTile ?: return
            
            when (state) {
                is FirewallManager.FirewallState.Running -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = getString(R.string.tile_label_firewall)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_label_firewall_on)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_on, state.backend.name)
                }
                FirewallManager.FirewallState.Stopped -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = getString(R.string.tile_label_firewall)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_label_firewall_off)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_off)
                }
                is FirewallManager.FirewallState.Starting -> {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.label = getString(R.string.tile_label_firewall)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_label_firewall_starting)
                    }
                    tile.contentDescription = getString(R.string.tile_description_firewall_starting)
                }
                is FirewallManager.FirewallState.Error -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = getString(R.string.tile_label_firewall)
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
