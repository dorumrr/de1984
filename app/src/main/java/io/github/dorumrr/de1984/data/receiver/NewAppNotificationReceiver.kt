package io.github.dorumrr.de1984.data.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.service.NewAppNotificationManager
import io.github.dorumrr.de1984.domain.usecase.ManageNetworkAccessUseCase
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class NewAppNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NewAppNotificationReceiver"
        private const val NOTIFICATION_ID_BASE = 2000 // Must match NewAppNotificationManager
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            // Initialize dependencies
            val app = context.applicationContext as De1984Application
            val manageNetworkAccessUseCase = app.dependencies.provideManageNetworkAccessUseCase()

            val action = intent?.action
            val packageName = intent?.getStringExtra(NewAppNotificationManager.EXTRA_PACKAGE_NAME)
            
            if (packageName.isNullOrBlank()) {
                return
            }
            
            dismissNotification(context, packageName)
            
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                when (action) {
                    NewAppNotificationManager.ACTION_BLOCK_ALL -> {
                        handleBlockAll(context, packageName, manageNetworkAccessUseCase)
                    }
                    NewAppNotificationManager.ACTION_BLOCK_WIFI -> {
                        handleBlockWifi(context, packageName, manageNetworkAccessUseCase)
                    }
                    NewAppNotificationManager.ACTION_BLOCK_MOBILE -> {
                        handleBlockMobile(context, packageName, manageNetworkAccessUseCase)
                    }
                    NewAppNotificationManager.ACTION_ALLOW_ALL -> {
                        handleAllowAll(context, packageName, manageNetworkAccessUseCase)
                    }
                    NewAppNotificationManager.ACTION_ALLOW_WIFI -> {
                        handleAllowWifi(context, packageName, manageNetworkAccessUseCase)
                    }
                    NewAppNotificationManager.ACTION_ALLOW_MOBILE -> {
                        handleAllowMobile(context, packageName, manageNetworkAccessUseCase)
                    }
                }
            }

        } catch (e: Exception) {
            // Error handling notification action
        }
    }
    
    private suspend fun handleBlockAll(context: Context, packageName: String, manageNetworkAccessUseCase: ManageNetworkAccessUseCase) {
        try {
            val wifiResult = manageNetworkAccessUseCase.setWifiBlocking(packageName, blocked = true)
            val mobileResult = manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked = true)
            val roamingResult = manageNetworkAccessUseCase.setRoamingBlocking(packageName, blocked = true)

            if (wifiResult.isSuccess && mobileResult.isSuccess && roamingResult.isSuccess) {
                checkFirewallAndRedirect(context)
            }

        } catch (e: Exception) {
            // Error blocking all networks
        }
    }

    private suspend fun handleBlockWifi(context: Context, packageName: String, manageNetworkAccessUseCase: ManageNetworkAccessUseCase) {
        try {
            manageNetworkAccessUseCase.setWifiBlocking(packageName, blocked = true)
                .onSuccess {
                    checkFirewallAndRedirect(context)
                }

        } catch (e: Exception) {
            // Error blocking WiFi
        }
    }

    private suspend fun handleBlockMobile(context: Context, packageName: String, manageNetworkAccessUseCase: ManageNetworkAccessUseCase) {
        try {
            manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked = true)
                .onSuccess {
                    checkFirewallAndRedirect(context)
                }

        } catch (e: Exception) {
            // Error blocking Mobile
        }
    }
    
    private suspend fun handleAllowAll(context: Context, packageName: String, manageNetworkAccessUseCase: ManageNetworkAccessUseCase) {
        try {
            val wifiResult = manageNetworkAccessUseCase.setWifiBlocking(packageName, blocked = false)
            val mobileResult = manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked = false)
            val roamingResult = manageNetworkAccessUseCase.setRoamingBlocking(packageName, blocked = false)

            if (wifiResult.isSuccess && mobileResult.isSuccess && roamingResult.isSuccess) {
                checkFirewallAndRedirect(context)
            }

        } catch (e: Exception) {
            // Error allowing all networks
        }
    }

    private suspend fun handleAllowWifi(context: Context, packageName: String, manageNetworkAccessUseCase: ManageNetworkAccessUseCase) {
        try {
            manageNetworkAccessUseCase.setWifiBlocking(packageName, blocked = false)
                .onSuccess {
                    checkFirewallAndRedirect(context)
                }

        } catch (e: Exception) {
            // Error allowing WiFi
        }
    }

    private suspend fun handleAllowMobile(context: Context, packageName: String, manageNetworkAccessUseCase: ManageNetworkAccessUseCase) {
        try {
            manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked = false)
                .onSuccess {
                    checkFirewallAndRedirect(context)
                }

        } catch (e: Exception) {
            // Error allowing Mobile
        }
    }

    private fun checkFirewallAndRedirect(context: Context) {
        try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(
                Constants.Settings.KEY_FIREWALL_ENABLED,
                Constants.Settings.DEFAULT_FIREWALL_ENABLED
            )

            if (!isFirewallEnabled) {
                val intent = Intent(context, io.github.dorumrr.de1984.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("show_firewall_prompt", true)
                }

                context.startActivity(intent)
            }

        } catch (e: Exception) {
            // Error checking firewall status
        }
    }

    private fun dismissNotification(context: Context, packageName: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = NOTIFICATION_ID_BASE + packageName.hashCode()
            
            notificationManager.cancel(notificationId)
        } catch (e: Exception) {
            // Error dismissing notification
        }
    }
}
