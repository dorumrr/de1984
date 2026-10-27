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

@RequiresApi(Build.VERSION_CODES.N)
class FirewallTileService : TileService() {

    companion object {
        private const val TAG = "FirewallTileService"
        
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
        
        val currentState = firewallManager.firewallState.value
        AppLogger.d(TAG, "Initial state on startListening: $currentState")
        updateTile(currentState)

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
        
        AppLogger.d(TAG, "Current state: ${firewallManager.firewallState.value}")

        val shouldTreatAsActive = firewallManager.isShownOn()
        AppLogger.d(TAG, "Final decision - treat as active: $shouldTreatAsActive")
        
        if (shouldTreatAsActive && !firewallManager.shouldConfirmStop()) {
            // Confirmation turned off (issue #91): hand it to the receiver, which owns the stop and
            // posts the notification. Same predicate as the widget path, asked of the same place.
            AppLogger.d(TAG, "Firewall is ON and confirmation is off - stopping without opening the app")
            val toggleIntent = Intent(this, io.github.dorumrr.de1984.data.receiver.FirewallToggleReceiver::class.java).apply {
                action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
            }
            sendBroadcast(toggleIntent)
        } else if (shouldTreatAsActive) {
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
                // The API 34 form is used above. Both ids are needed: @Suppress("DEPRECATION")
                // silences the Kotlin warning, and lint's own check has its own name.
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        } else {
            // No local Starting draw: the manager is still Stopped while the start plans, and a tap must act on what is drawn.
            AppLogger.d(TAG, "Sending broadcast to FirewallToggleReceiver...")
            val toggleIntent = Intent(this, io.github.dorumrr.de1984.data.receiver.FirewallToggleReceiver::class.java).apply {
                action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
            }
            sendBroadcast(toggleIntent)
            AppLogger.d(TAG, "Broadcast sent to FirewallToggleReceiver")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    // "statusbar" has no public Context constant - it is hidden - so the literal
                    // is the only way to ask for it. Lint compares against the public list and
                    // cannot know that.
                    @Suppress("WrongConstant")
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

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateTile(state: FirewallManager.FirewallState) {
        try {
            val tile = qsTile ?: return
            
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
