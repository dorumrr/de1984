package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.utils.Constants

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "De1984.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                restoreFirewallState(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                restoreFirewallState(context)
            }
            else -> {
                Log.w(TAG, "   ⚠️ Unknown action: $action")
            }
        }

    }

    private fun restoreFirewallState(context: Context) {
        try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            if (wasEnabled) {
                val serviceIntent = Intent(context, FirewallVpnService::class.java).apply {
                    action = FirewallVpnService.ACTION_START
                }
                
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start firewall service", e)
                }
            } else {
                Log.d(TAG, "ℹ️ Firewall was disabled, not starting service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restoring firewall state", e)
        }
    }
}

