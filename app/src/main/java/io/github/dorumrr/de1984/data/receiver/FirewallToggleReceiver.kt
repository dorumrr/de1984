package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.ui.widget.FirewallWidget
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles firewall toggle requests from widgets, tiles, and notifications.
 * 
 * This receiver provides a centralized way to toggle the firewall on/off from
 * any context (Widget, TileService, Notification actions). It handles VPN
 * permission requirements by launching MainActivity when needed.
 */
class FirewallToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FirewallToggleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.d(TAG, "━━━━━ onReceive() called ━━━━━")
        AppLogger.d(TAG, "Received intent action: ${intent?.action}")
        AppLogger.d(TAG, "Intent extras: ${intent?.extras}")
        
        if (intent?.action != Constants.Firewall.ACTION_TOGGLE_FIREWALL) {
            AppLogger.d(TAG, "⚠️ Action mismatch, ignoring. Expected: ${Constants.Firewall.ACTION_TOGGLE_FIREWALL}")
            return
        }

        AppLogger.d(TAG, "✅ ACTION_TOGGLE_FIREWALL received!")

        val app = context.applicationContext as De1984Application
        val firewallManager = app.dependencies.firewallManager
        
        AppLogger.d(TAG, "FirewallManager obtained, isActive=${firewallManager.isActive()}")

        // Use goAsync() to keep receiver alive while coroutine runs
        val pendingResult = goAsync()
        AppLogger.d(TAG, "goAsync() called, starting coroutine...")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val isCurrentlyActive = firewallManager.isActive()
                AppLogger.d(TAG, "Current firewall state: isActive=$isCurrentlyActive")
                
                if (isCurrentlyActive) {
                    // Firewall is ON - open app for stop confirmation (to prevent accidental stops)
                    AppLogger.d(TAG, "🔴 Firewall is active, opening app for stop confirmation...")
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(activityIntent)
                    AppLogger.d(TAG, "MainActivity launched for stop confirmation")
                } else {
                    // Firewall is OFF - start directly without opening app (quick toggle from widget)
                    AppLogger.d(TAG, "🟢 Firewall is stopped, starting directly...")

                    // Immediately show loading state on widgets for responsive UX
                    FirewallWidget.setLoadingState(context)

                    // Check if VPN permission is needed
                    val planResult = firewallManager.computeStartPlan(FirewallMode.AUTO)
                    val plan = planResult.getOrNull()
                    AppLogger.d(TAG, "computeStartPlan result: $plan")
                    AppLogger.d(TAG, "requiresVpnPermission: ${plan?.requiresVpnPermission}")

                    if (plan?.requiresVpnPermission == true) {
                        AppLogger.d(TAG, "🔐 VPN permission required, launching transparent VpnPermissionActivity...")
                        // Launch transparent activity in its own task to handle VPN permission dialog only
                        // Using NEW_TASK + MULTIPLE_TASK + NO_ANIMATION to avoid bringing main app to focus
                        val activityIntent = Intent(context, io.github.dorumrr.de1984.ui.VpnPermissionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        }
                        context.startActivity(activityIntent)
                        AppLogger.d(TAG, "VpnPermissionActivity launched")
                    } else {
                        AppLogger.d(TAG, "🚀 No VPN permission needed, starting firewall directly...")
                        val startResult = firewallManager.startFirewall(FirewallMode.AUTO)
                        AppLogger.d(TAG, "startFirewall() result: $startResult")
                        // Update SharedPreferences to reflect the change
                        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()
                        AppLogger.d(TAG, "SharedPreferences updated: KEY_FIREWALL_ENABLED=true")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ Error toggling firewall", e)
            } finally {
                AppLogger.d(TAG, "Coroutine complete, calling pendingResult.finish()")
                // Signal that async work is complete
                pendingResult.finish()
            }
        }
    }
}
