package io.github.dorumrr.de1984.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.*


class PackageMonitoringService : Service() {
    
    
    lateinit var handleNewAppInstallUseCase: HandleNewAppInstallUseCase
    
    
    lateinit var newAppNotificationManager: NewAppNotificationManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var lastKnownPackages: Set<String> = emptySet()
    
    companion object {
        private const val TAG = "PackageMonitoringService"
        const val ACTION_START_MONITORING = "io.github.dorumrr.de1984.action.START_PACKAGE_MONITORING"
        const val ACTION_STOP_MONITORING = "io.github.dorumrr.de1984.action.STOP_PACKAGE_MONITORING"
        
        fun startMonitoring(context: Context) {
            val intent = Intent(context, PackageMonitoringService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            context.startService(intent)
        }
        
        fun stopMonitoring(context: Context) {
            val intent = Intent(context, PackageMonitoringService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()

        // Initialize dependencies manually
        val app = application as De1984Application
        val deps = app.dependencies
        handleNewAppInstallUseCase = deps.provideHandleNewAppInstallUseCase()
        newAppNotificationManager = deps.newAppNotificationManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun startMonitoring() {
        if (monitoringJob?.isActive == true) {
            return
        }
        
        lastKnownPackages = getCurrentInstalledPackages()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(15_000)
                    checkForNewPackages()
                } catch (e: Exception) {
                    delay(60_000)
                }
            }
        }
    }
    
    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }
    
    private suspend fun checkForNewPackages() {
        val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val notificationsEnabled = prefs.getBoolean(
            Constants.Settings.KEY_NEW_APP_NOTIFICATIONS,
            Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS
        )
        
        if (!notificationsEnabled) {
            return
        }
        
        val currentPackages = getCurrentInstalledPackages()
        val newPackages = currentPackages - lastKnownPackages
        
        if (newPackages.isNotEmpty()) {
            newPackages.forEach { packageName ->
                processNewPackage(packageName)
            }
            lastKnownPackages = currentPackages
        }
    }
    
    private fun getCurrentInstalledPackages(): Set<String> {
        return try {
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { packageInfo ->
                    packageInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true &&
                    packageInfo.applicationInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 } == true
                }
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    private suspend fun processNewPackage(packageName: String) {
        try {
            handleNewAppInstallUseCase.execute(packageName)
                .onSuccess {
                    newAppNotificationManager.showNewAppNotification(packageName)
                }
        } catch (e: Exception) {
            // Error processing new package
        }
    }
}
