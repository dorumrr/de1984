package io.github.dorumrr.de1984.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpnPermissionActivity : Activity() {

    companion object {
        private const val TAG = "VpnPermissionActivity"
        private const val REQUEST_VPN_PERMISSION = 100

        const val EXTRA_RESOLVED_MODE = "io.github.dorumrr.de1984.extra.RESOLVED_MODE"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.d(TAG, "VpnPermissionActivity created")
        
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Null while another VPN is up, so the start below reports the conflict instead of taking its slot.
        val prepareIntent = (application as De1984Application).dependencies.firewallManager.vpnConsentIntent()
        AppLogger.d(TAG, "Consent dialog needed: ${prepareIntent != null}")
        
        if (prepareIntent != null) {
            AppLogger.d(TAG, "🔐 Requesting VPN permission via system dialog...")
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else {
            AppLogger.d(TAG, "No consent dialog (granted, or another VPN is up) - starting firewall")
            startFirewallAndFinish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                AppLogger.d(TAG, "VPN permission granted")
                startFirewallAndFinish()
            } else {
                AppLogger.d(TAG, "VPN permission denied")
                finish()
            }
        }
    }

    private fun startFirewallAndFinish() {
        val app = application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        
        scope.launch(Dispatchers.IO) {
            // Every widget and tile start on a device without root lands here, so this is the path
            // that matters most. It used to hard-code AUTO, throwing away the mode the user picked,
            // and to record "firewall enabled" whether or not the start worked.
            val resolved = intent?.getStringExtra(EXTRA_RESOLVED_MODE)
                ?.let { name -> FirewallMode.entries.firstOrNull { it.name == name } }
            val mode = resolved ?: firewallManager.getCurrentMode()
            AppLogger.d(TAG, "Starting firewall in mode: $mode (from caller: ${resolved != null})")
            val result = firewallManager.startFirewall(mode)
            AppLogger.d(TAG, "startFirewall result: $result")

            result
                .onSuccess {
                    val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()
                }
                .onFailure { error ->
                    // Left untouched on purpose. The preference records what the user wants, and a
                    // start that never happened is not evidence they want it on - writing true here
                    // told boot restore to bring back a firewall that was never up.
                    AppLogger.e(TAG, "Start after VPN permission failed, KEY_FIREWALL_ENABLED untouched", error)
                }

            runOnUiThread {
                finish()
            }
        }
    }
}
