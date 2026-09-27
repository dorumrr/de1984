package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.ui.widget.FirewallWidget
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

        val pendingResult = goAsync()
        AppLogger.d(TAG, "goAsync() called, starting coroutine...")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val isCurrentlyActive = firewallManager.isActive()
                AppLogger.d(TAG, "Current firewall state: isActive=$isCurrentlyActive")
                
                if (isCurrentlyActive) {
                    if (firewallManager.shouldConfirmStop()) {
                        AppLogger.d(TAG, "🔴 Firewall is active, opening app for stop confirmation...")
                        val activityIntent = Intent(context, MainActivity::class.java).apply {
                            action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(activityIntent)
                        AppLogger.d(TAG, "MainActivity launched for stop confirmation")
                    } else {
                        // The user turned the confirmation off (issue #91). Stop here rather than
                        // opening the app, and SAY SO - a tile tap that silently drops all
                        // protection is exactly what the confirmation existed to prevent.
                        AppLogger.d(TAG, "🔴 Firewall is active and confirmation is off - stopping directly")
                        firewallManager.stopFirewall()
                            .onSuccess {
                                // stopFirewallInternal never clears user intent; left true, the startup
                                // probe, boot restore and widget would each undo or hide this stop.
                                val prefs = context.getSharedPreferences(
                                    Constants.Settings.PREFS_NAME,
                                    Context.MODE_PRIVATE
                                )
                                prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false).apply()
                                AppLogger.d(TAG, "Firewall stopped from tile/widget, KEY_FIREWALL_ENABLED=false")
                                firewallManager.showFirewallStoppedNotification()
                            }
                            .onFailure {
                                // Leave the pref alone on failure, the same asymmetry the start
                                // branch uses: the firewall is still up, so intent has not changed.
                                AppLogger.e(TAG, "Failed to stop firewall: ${it.message}")
                            }
                    }
                } else {
                    AppLogger.d(TAG, "🟢 Firewall is stopped, starting directly...")

                    FirewallWidget.setLoadingState(context)

                    // Honour the mode the user picked in Settings. Hard-coding AUTO here started a
                    // different backend than the one they chose, and a manual VPN choice was ignored
                    // every time the firewall was started from the widget or the tile.
                    val persistedMode = firewallManager.getCurrentMode()
                    AppLogger.d(TAG, "Using persisted firewall mode: $persistedMode")

                    // computeStartPlan falls back to AUTO itself when the stored mode's backend is
                    // unavailable - root lost, Shizuku gone - and reports the mode it settled on.
                    // This used to be a second copy of that fallback living here; the widget was the
                    // only start path that had one, which is exactly why boot restore and the in-app
                    // Start button did not.
                    val planResult = firewallManager.computeStartPlan(persistedMode)
                    val plan = planResult.getOrNull()
                    val mode = plan?.mode ?: persistedMode
                    if (plan != null && plan.mode != persistedMode) {
                        AppLogger.w(TAG, "Persisted mode $persistedMode is unavailable; plan resolved to ${plan.mode}")
                    }
                    AppLogger.d(TAG, "computeStartPlan result: $plan")
                    // Null while another VPN is up, so the start below reports the conflict instead of asking for its slot.
                    val needsUserForVpn = plan?.selectedBackendType == FirewallBackendType.VPN &&
                        firewallManager.vpnConsentIntent() != null
                    AppLogger.d(TAG, "needsUserForVpn: $needsUserForVpn")

                    if (needsUserForVpn) {
                        // Android 14 blocks a receiver from opening the consent dialog (BAL_BLOCK); a notification tap may,
                        // and it carries `mode` as resolved above so the AUTO fallback is not recomputed and lost.
                        AppLogger.w(TAG, "🔐 VPN permission required - a receiver cannot open the dialog, notifying instead")
                        firewallManager.reportVpnPermissionRequiredFromBackground(mode)
                    } else {
                        AppLogger.d(TAG, "🚀 No VPN permission needed, starting firewall directly...")
                        val startResult = firewallManager.startFirewall(mode)
                        AppLogger.d(TAG, "startFirewall() result: $startResult")

                        // Only record "enabled" when the start actually succeeded. Writing it
                        // unconditionally told boot restore the firewall had been running when it
                        // never started, so the next reboot tried to restore a firewall that was
                        // never up - and the widget showed ON over an unprotected device.
                        startResult
                            .onSuccess {
                                val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                                prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()
                                AppLogger.d(TAG, "SharedPreferences updated: KEY_FIREWALL_ENABLED=true")
                            }
                            .onFailure { error ->
                                // The widget clears its own loading state: a failed start reports
                                // down through FirewallManager, which broadcasts the new state and
                                // FirewallWidget.onReceive redraws from it.
                                AppLogger.e(TAG, "❌ Start from widget/tile failed, leaving KEY_FIREWALL_ENABLED untouched", error)
                            }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ Error toggling firewall", e)
            } finally {
                AppLogger.d(TAG, "Coroutine complete, calling pendingResult.finish()")
                pendingResult.finish()
            }
        }
    }
}
