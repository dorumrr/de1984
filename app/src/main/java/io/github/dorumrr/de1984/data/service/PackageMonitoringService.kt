package io.github.dorumrr.de1984.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.*


class PackageMonitoringService : Service() {
    
    
    lateinit var handleNewAppInstallUseCase: HandleNewAppInstallUseCase
    
    
    lateinit var newAppNotificationManager: NewAppNotificationManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    // Track packages by (packageName, userId) for multi-user support
    private var lastKnownPackages: Set<Pair<String, Int>> = emptySet()
    
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
            AppLogger.d(TAG, "📦 Detected ${newPackages.size} new packages")
            newPackages.forEach { (packageName, userId) ->
                processNewPackage(packageName, userId)
            }
            lastKnownPackages = currentPackages
        }
    }

    /**
     * Get all installed packages across all user profiles.
     * Returns Set of (packageName, userId) pairs.
     */
    private fun getCurrentInstalledPackages(): Set<Pair<String, Int>> {
        return try {
            val result = mutableSetOf<Pair<String, Int>>()
            val userProfiles = HiddenApiHelper.getUsers(this)

            for (profile in userProfiles) {
                val packages = HiddenApiHelper.getInstalledApplicationsAsUser(
                    this,
                    PackageManager.GET_META_DATA,
                    profile.userId
                )

                packages
                    .filter { appInfo ->
                        // Only track user apps with internet permission
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        hasInternetPermission(appInfo.packageName, profile.userId)
                    }
                    .forEach { appInfo ->
                        result.add(appInfo.packageName to profile.userId)
                    }
            }

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get installed packages: ${e.message}", e)
            emptySet()
        }
    }

    private fun hasInternetPermission(packageName: String, userId: Int = 0): Boolean {
        return try {
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                this, packageName, PackageManager.GET_PERMISSIONS, userId
            ) ?: return false
            packageInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processNewPackage(packageName: String, userId: Int) {
        try {
            // Calculate UID for the use case: userId * 100000 + appId
            // Use HiddenApiHelper for multi-user support - work profile apps need userId to be queried
            val appInfo = try {
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                    this, packageName, 0, userId
                )
            } catch (e: Exception) {
                null
            }
            val appId = appInfo?.uid?.rem(100000) ?: 0
            val uid = userId * 100000 + appId

            AppLogger.d(TAG, "📦 Processing new package: $packageName (userId=$userId, uid=$uid)")

            handleNewAppInstallUseCase.execute(packageName, uid)
                .onSuccess {
                    newAppNotificationManager.showNewAppNotification(packageName)
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error processing new package $packageName: ${e.message}", e)
        }
    }
}
