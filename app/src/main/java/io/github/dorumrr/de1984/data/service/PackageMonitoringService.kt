package io.github.dorumrr.de1984.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.*


class PackageMonitoringService : Service() {
    
    
    lateinit var handleNewAppInstallUseCase: HandleNewAppInstallUseCase
    
    
    lateinit var newAppNotificationManager: NewAppNotificationManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var lastKnownPackages: Set<Pair<String, Int>> = emptySet()

    /**
     * False until an enumeration has actually succeeded once.
     *
     * Without it, an empty [lastKnownPackages] is ambiguous: it means either "this device has no
     * user apps" or "we have never managed to look". Treating the second as the first makes every
     * installed app look newly installed on the first successful pass.
     */
    private var hasBaseline = false

    /**
     * userId -> the packages disabled in that profile, as of the last successful read.
     *
     * Separate from [lastKnownPackages] because the two answer different questions. That set is
     * (packageName, userId) pairs, and disabling an app does not remove it from the device - so
     * membership never changes and an enable or disable is completely invisible to it. That is the
     * whole of issue #61a: ACTION_PACKAGE_CHANGED reaches only user 0, so a work-profile app turned
     * off by another app stayed "Enabled" in De1984 until something unrelated forced a refresh.
     *
     * A profile is absent from this map until it has been read successfully once, so a first read
     * never counts as a change.
     */
    private var lastKnownDisabled: MutableMap<Int, Set<String>> = mutableMapOf()
    
    /**
     * Whether a profile other than user 0 existed on the previous tick.
     *
     * A work or clone profile appearing must NOT be read as "every app in it was just installed" -
     * that would fire one rule write and one notification per app. When the answer flips, the
     * baseline is rebuilt instead of diffed.
     */
    private var hadSecondaryProfiles = false

    companion object {
        private const val TAG = "PackageMonitoringService"
        const val ACTION_START_MONITORING = "io.github.dorumrr.de1984.action.START_PACKAGE_MONITORING"
        const val ACTION_STOP_MONITORING = "io.github.dorumrr.de1984.action.STOP_PACKAGE_MONITORING"

        /**
         * How often to look while the screen is ON.
         *
         * The only event this poll can see first is an app appearing in a work or clone profile, and
         * that is something a person does while looking at the phone. 30s is comfortably inside the
         * time an install itself takes, so the notification still feels immediate.
         *
         * Was a flat 15s in every state. Measured on hardware 2026-08-29: 480 root shell spawns an
         * hour and 2.4% CPU with the screen OFF, on a device with 83 apps and two profiles. The cost
         * grows with installed app count - getCurrentInstalledPackages enumerates every app in every
         * profile on every tick - which is why users with several hundred apps reported heavy drain.
         */
        private const val POLL_INTERVAL_SCREEN_ON_MS = 30_000L

        /** After a failed tick. Unchanged. */
        private const val POLL_ERROR_BACKOFF_MS = 60_000L

        private const val FRESH_INSTALL_WINDOW_MS = 10 * 60_000L
        
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

        val app = application as De1984Application
        val deps = app.dependencies
        handleNewAppInstallUseCase = deps.provideHandleNewAppInstallUseCase()
        newAppNotificationManager = deps.newAppNotificationManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()

            // A NULL action is what START_STICKY redelivers after Android has killed and restarted
            // this service - low memory, a crash, an app update. Nothing matched it, so the service
            // came back alive and did NOTHING: the poll never restarted, and because this service is
            // the only thing that sees installs in a work or clone profile, those simply stopped
            // being noticed until the user next opened the app or rebooted. Silently, with no signal.
            //
            // Found 2026-08-29 while measuring the poll: an install killed the process, the service
            // was restarted by the system with a null intent, and it logged nothing for the whole
            // test window.
            //
            // Resuming is safe to do unconditionally - startMonitoring() returns immediately if the
            // job is already active.
            null -> {
                AppLogger.i(TAG, "Restarted by the system with no intent - resuming monitoring")
                startMonitoring()
            }
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

        hadSecondaryProfiles = secondaryProfilesExist()
        if (hadSecondaryProfiles) {
            getCurrentInstalledPackages()?.let {
                lastKnownPackages = it
                hasBaseline = true
            }
        }
        registerEventReceiver()

        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (isScreenOn()) {
                        delay(POLL_INTERVAL_SCREEN_ON_MS)
                        // The screen may have gone off during that sleep. Do not spend the tick.
                        if (isScreenOn()) runOneCheck()
                    } else {
                        // NOT a long sleep - a suspend. Zero ticks while the screen is off.
                        //
                        // A long screen-off interval was the first attempt, to catch an app pushed
                        // unattended into a managed work profile. That reasoning does not hold:
                        //
                        // - This app holds NO wakelock and sets NO alarm, so delay() cannot wake a
                        //   suspended CPU. A screen-off poll could never reliably run in exactly the
                        //   sleeping-phone case it was being kept for.
                        // - Enforcement never depended on this poll anyway. Under a Block All
                        //   default, IptablesFirewallBackend.applyRules enumerates every package
                        //   ITSELF rather than reading the rules table, and PrivilegedFirewallService
                        //   re-applies on screen-off, screen-on AND network changes. A newly
                        //   installed app is blocked whether or not this service ever saw it.
                        //
                        // What is left - the rule row, the "new app" notification, UI freshness - is
                        // only ever experienced with the screen on, and the check below delivers all
                        // three the moment it comes back.
                        AppLogger.d(TAG, "Screen off - suspending package monitoring until it returns")
                        screenOnSignal.receive()
                        AppLogger.d(TAG, "Screen on - running one immediate check")
                        runOneCheck()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Poll tick failed, backing off: ${e.message}")
                    delay(POLL_ERROR_BACKOFF_MS)
                }
            }
        }
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive ?: true
    }

    /**
     * Is there any profile besides the one De1984 runs in?
     *
     * Everything this poll can see first is an event in another profile. For De1984's own profile,
     * PackageChangedReceiver delivers installs, full uninstalls and enable/disable instantly, and
     * [eventReceiver] an app's return - so on a single-profile device, which is most devices, this poll
     * had nothing to contribute and was pure cost.
     *
     * Cheap to ask: HiddenApiHelper.getUsers caches, and reads UserManager.getUserProfiles, a binder
     * call - not a shell command.
     */
    private fun secondaryProfilesExist(): Boolean = try {
        val ownUserId = Constants.Firewall.ownUserId()
        HiddenApiHelper.getUsers(this).any { it.userId != ownUserId }
    } catch (e: Exception) {
        AppLogger.w(TAG, "Could not list profiles - assuming none: ${e.message}")
        false
    }

    /**
     * One poll tick, with the profile gate and the appear/disappear transitions around it.
     */
    private suspend fun runOneCheck() {
        val hasSecondary = secondaryProfilesExist()

        if (!hasSecondary) {
            if (hadSecondaryProfiles) {
                // The last secondary profile went away. Drop its state so a profile later re-created
                // on the same userId is not compared against the deleted one's snapshot.
                AppLogger.d(TAG, "No secondary profiles left - clearing baseline and idling")
                lastKnownPackages = emptySet()
                hasBaseline = false
                lastKnownDisabled.clear()
                hadSecondaryProfiles = false
                announcePackageSetChange()
            }
            return
        }

        if (!hadSecondaryProfiles) {
            // A profile just appeared. Everything in it is pre-existing from this service's point of
            // view; diffing here would report every app in it as newly installed.
            AppLogger.d(TAG, "A secondary profile appeared - establishing a baseline, not reporting installs")
            hadSecondaryProfiles = true
            getCurrentInstalledPackages()?.let {
                lastKnownPackages = it
                hasBaseline = true
            }
            announcePackageSetChange()
            return
        }

        checkForNewPackages()
    }

    /**
     * Wakes the monitoring loop when the screen comes back on.
     *
     * CONFLATED, so a burst of screen-ons collapses to one pending wake and the receiver never
     * blocks. It only signals - the check itself runs on the loop, so there is exactly one checker
     * and no chance of two running at once.
     */
    private val screenOnSignal = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )

    private val eventReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOnSignal.trySend(Unit)
                // An app back in this profile with its old uid (restored, unhidden) writes no rule, so nothing else re-applies.
                Intent.ACTION_PACKAGE_ADDED ->
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        announcePackageSetChange()
                        intent.data?.schemeSpecificPart?.let { pkg -> serviceScope.launch { processOwnProfileInstall(pkg) } }
                    }
            }
        }
    }
    private var eventReceiverRegistered = false

    private fun registerEventReceiver() {
        if (eventReceiverRegistered) return
        runCatching {
            registerReceiver(eventReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
            eventReceiverRegistered = true
            // Android sends ACTION_PACKAGE_ADDED without FLAG_RECEIVER_INCLUDE_BACKGROUND: manifest receivers never get it.
            registerReceiver(eventReceiver, android.content.IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
        }.onFailure { AppLogger.w(TAG, "Could not register the screen-on and package-added receiver: ${it.message}") }
    }

    private fun unregisterEventReceiver() {
        if (!eventReceiverRegistered) return
        runCatching { unregisterReceiver(eventReceiver) }
        eventReceiverRegistered = false
    }
    
    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        unregisterEventReceiver()
    }
    
    private suspend fun checkForNewPackages() {
        // The notification preference used to return here. This service is the ONLY code that sees
        // installs in other user profiles - a manifest PACKAGE_ADDED receiver in De1984's own profile never does -
        // so with notifications off a work-profile app got no rule at all, and a reinstalled one
        // kept a uid that matches nothing. Only the notification is optional; the rule is not.
        // null means the enumeration itself failed, which is NOT the same as "no packages". It used
        // to return an empty set on any exception, and the baseline was then overwritten with it -
        // so one Shizuku or binder hiccup made every installed app look new on the very next tick,
        // firing a rule write and a "new app" notification for each of them. Keep the old baseline
        // and try again on the next tick.
        val currentPackages = getCurrentInstalledPackages()
        if (currentPackages == null) {
            AppLogger.w(TAG, "Package enumeration failed - keeping the previous baseline")
            return
        }

        if (!hasBaseline) {
            // First enumeration that worked. Everything on the device right now is the starting
            // point, not a burst of installs - the startup enumeration must have failed.
            AppLogger.d(TAG, "Baseline established from the first successful enumeration")
            lastKnownPackages = currentPackages
            hasBaseline = true
            return
        }

        val newPackages = currentPackages - lastKnownPackages

        if (newPackages.isNotEmpty()) {
            AppLogger.d(TAG, "📦 Detected ${newPackages.size} new packages")
            newPackages.forEach { (packageName, userId) ->
                processNewPackage(packageName, userId)
            }
        }

        checkForEnabledStateChanges()

        val setChanged = currentPackages != lastKnownPackages
        // Updated unconditionally. Inside the branch above, an uninstall left the package in the
        // baseline, so it was never "new" again and a reinstall was never processed - the exact
        // case the stale-uid refresh exists for.
        lastKnownPackages = currentPackages

        if (setChanged) announcePackageSetChange()
    }

    /** An install or uninstall changes which rules count for a uid (rulesByCurrentUid), and nothing else re-applies for it. */
    private fun announcePackageSetChange() {
        HiddenApiHelper.clearInstalledAppsCache()
        sendBroadcast(Intent("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED").setPackage(packageName))
        // The screen groups rules by the same installed-app lists, so it rescans with the firewall.
        (application as De1984Application).dependencies.notifyPackageDataChanged()
    }

    /**
     * Get all installed packages across all user profiles.
     *
     * @return the (packageName, userId) pairs, or null when the enumeration failed. Null and empty
     * mean different things to the caller, so they must not be collapsed into one value.
     */
    private fun getCurrentInstalledPackages(): Set<Pair<String, Int>>? {
        return try {
            val result = mutableSetOf<Pair<String, Int>>()
            // De1984's own profile is deliberately skipped: PackageChangedReceiver and eventReceiver deliver
            // installs, returns, full uninstalls and enable/disable for it instantly. Other profiles get no such broadcast.
            val ownUserId = Constants.Firewall.ownUserId()
            val userProfiles = HiddenApiHelper.getUsers(this).filter { it.userId != ownUserId }

            for (profile in userProfiles) {
                // Flags 0, not GET_META_DATA. The lambda below reads only .flags and .packageName;
                // nothing in the app reads .metaData at all (a repo-wide grep for it returns
                // nothing), so filling a Bundle for every app on every tick bought nothing.
                //
                // Deliberately changed HERE ONLY. installedAppsCache is keyed by userId alone, never
                // by flags, so a flags-blind list can be served to a GET_META_DATA caller inside its
                // 5s window - harmless while nothing reads .metaData, but it is why the other seven
                // call sites are left as they are rather than swept along with this one.
                val packages = HiddenApiHelper.getInstalledApplicationsAsUser(
                    this,
                    0,
                    profile.userId
                )

                packages
                    .filter { appInfo -> isWatchedApp(appInfo, profile.userId) }
                    .forEach { appInfo ->
                        result.add(appInfo.packageName to profile.userId)
                    }
            }

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get installed packages: ${e.message}", e)
            null
        }
    }

    /**
     * One `pm list packages -d` per profile, not per package, keeps this cheap enough for every tick.
     * A failed read keeps the old snapshot, or it would look like every app enabled then disabled again.
     */
    private fun checkForEnabledStateChanges() {
        val profiles = try {
            // De1984's own profile skipped: ACTION_PACKAGE_CHANGED already reaches PackageChangedReceiver for it,
            // and reading it here costs a root or Shizuku process spawn on every tick.
            val ownUserId = Constants.Firewall.ownUserId()
            HiddenApiHelper.getUsers(this).filter { it.userId != ownUserId }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not list profiles for the enabled-state check: ${e.message}")
            return
        }

        var changed = false

        for (profile in profiles) {
            val fresh = HiddenApiHelper.readDisabledPackagesFresh(profile.userId)
            if (fresh == null) {
                AppLogger.d(TAG, "Disabled-state read failed for user ${profile.userId} - leaving the previous snapshot alone")
                continue
            }

            val previous = lastKnownDisabled[profile.userId]
            lastKnownDisabled[profile.userId] = fresh

            if (previous == null) {
                // First successful read for this profile. It is the starting point, not a change.
                continue
            }

            if (previous != fresh) {
                val nowDisabled = fresh - previous
                val nowEnabled = previous - fresh
                AppLogger.d(
                    TAG,
                    "📦 Enabled state changed in user ${profile.userId}: " +
                        "${nowDisabled.size} newly disabled, ${nowEnabled.size} newly enabled"
                )
                changed = true
            }
        }

        // Forget profiles that no longer exist. Without this, a work profile removed and later
        // re-created reusing the same userId is compared against the DELETED profile's snapshot, so
        // its very first read reports a change that never happened.
        val liveIds = profiles.map { it.userId }.toSet()
        lastKnownDisabled.keys.retainAll(liveIds)

        if (changed) {
            // The disabled sets are already refreshed by the read above, but the built
            // ApplicationInfo objects are cached separately for a few seconds with the OLD enabled
            // flag baked in - so without this the screen can redraw showing exactly what we just
            // detected had changed. Only on a real change, which is rare, so the cost is not paid
            // on ordinary polls.
            HiddenApiHelper.clearInstalledAppsCache()
            (application as De1984Application).dependencies.notifyPackageDataChanged()
        }
    }

    private fun isWatchedApp(appInfo: android.content.pm.ApplicationInfo, userId: Int): Boolean =
        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
            hasInternetPermission(appInfo.packageName, userId)

    /** Unhiding or restoring an app sends the same broadcast as installing it; only a recent first install is new. */
    private suspend fun processOwnProfileInstall(packageName: String) {
        val userId = Constants.Firewall.ownUserId()
        val info = try {
            HiddenApiHelper.getPackageInfoAsUser(this, packageName, 0, userId)
        } catch (e: Exception) {
            null
        } ?: return
        val appInfo = info.applicationInfo ?: return
        if (System.currentTimeMillis() - info.firstInstallTime > FRESH_INSTALL_WINDOW_MS) return
        if (!isWatchedApp(appInfo, userId)) return
        processNewPackage(packageName, userId)
    }

    private fun hasInternetPermission(packageName: String, userId: Int = 0): Boolean {
        return try {
            // GET_PERMISSIONS *or* GET_SERVICES, matching HiddenApiHelper.kt:604 and
            // AndroidPackageDataSource.getPackageMetadataBatch. Only GET_PERMISSIONS is read here,
            // but the flags are part of the cache key - packageInfoCache is keyed
            // "userId:flags:packageName" (HiddenApiHelper.kt:1092). Asking for a narrower set meant
            // this poll could never hit the entry the network-permission sweep had just cached, and
            // stored a second copy of every package alongside it. The comment at HiddenApiHelper:602
            // warns about exactly this; the poll was making the mistake from another file.
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                this,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            ) ?: return false
            packageInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processNewPackage(packageName: String, userId: Int) {
        try {
            val appInfo = try {
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                    this, packageName, 0, userId
                )
            } catch (e: Exception) {
                null
            }
            // appId 0 is a placeholder, not a real appId - but the userId half of this value IS
            // real, and HandleNewAppInstallUseCase derives the profile from it (`uid / 100000`).
            // A sentinel here was tried and reverted: it made that division yield profile 0, so a
            // work-profile app whose ApplicationInfo could not be read had its rule created in the
            // personal profile instead. The placeholder is harmless because the use case re-reads
            // the real uid itself before writing anything.
            val appId = appInfo?.uid?.rem(100000) ?: 0
            val uid = userId * 100000 + appId

            AppLogger.d(TAG, "📦 Processing new package: $packageName (userId=$userId, uid=$uid)")

            handleNewAppInstallUseCase.execute(packageName, uid)
                .onSuccess {
                    newAppNotificationManager.showNewAppNotification(packageName, userId)
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error processing new package $packageName: ${e.message}", e)
        }
    }
}
