package io.github.dorumrr.de1984.data.monitor

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class ScreenStateMonitor(
    private val context: Context
) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        private const val TAG = "ScreenStateMonitor"
    }

    fun observeScreenState(): Flow<Boolean> = callbackFlow {
        AppLogger.d(TAG, "📱 Starting screen state monitoring")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        AppLogger.d(TAG, "📱 SYSTEM EVENT: Screen turned ON")
                        trySend(true)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        AppLogger.d(TAG, "📱 SYSTEM EVENT: Screen turned OFF")
                        trySend(false)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(receiver, filter)

        val initialState = isScreenOn()
        AppLogger.d(TAG, "📱 Initial screen state: ${if (initialState) "ON" else "OFF"}")
        trySend(initialState)

        awaitClose {
            AppLogger.d(TAG, "📱 Stopping screen state monitoring")
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()
    
    fun isScreenOn(): Boolean {
        return powerManager.isInteractive
    }
    
    fun isScreenOff(): Boolean {
        return !isScreenOn()
    }
}

