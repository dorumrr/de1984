package io.github.dorumrr.de1984.data.monitor

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
    
    fun observeScreenState(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        trySend(true)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
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
        
        trySend(isScreenOn())
        
        awaitClose {
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

