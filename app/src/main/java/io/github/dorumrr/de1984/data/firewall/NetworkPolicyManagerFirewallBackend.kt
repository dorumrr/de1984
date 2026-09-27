package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.PartialApplyException
import io.github.dorumrr.de1984.domain.firewall.rulesByCurrentUid
import io.github.dorumrr.de1984.domain.firewall.uidBlockedNow
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

class NetworkPolicyManagerFirewallBackend(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    companion object {
        private const val TAG = "NetworkPolicyManagerFirewall"
        
        private const val POLICY_NONE = 0x0
        private const val POLICY_REJECT_METERED_BACKGROUND = 0x1

        // 0x4 is POLICY_ALLOW_METERED_BACKGROUND in AOSP - an ALLOWANCE, not a block. It was used
        // here as POLICY_REJECT_ALL, which would have granted a metered-background allowance to
        // every app the UI showed as blocked. It never fired only because the old probe always
        // failed. Named here so it cannot be mistaken for a blocking value again.
        private const val POLICY_ALLOW_METERED_BACKGROUND = 0x4

        // POLICY_REJECT_ALL is not in AOSP. LineageOS and similar ROMs add it at 0x40000 and their
        // dumpsys decodes 262144 as REJECT_ALL. ROMs without it are detected at runtime, by writing
        // the value and reading it back - see calibrateBlockingPolicy.
        private const val POLICY_REJECT_ALL = 0x40000

        private const val SERVICE_NAME = "netpolicy"

        /**
         * Process-wide, NOT per-instance. Same shape and same reason as
         * ConnectivityManagerFirewallBackend's.
         *
         * Everything this backend guards - the system's per-UID policies and the on-disk record of
         * what each UID looked like first - is shared by every instance. FirewallManager,
         * PrivilegedFirewallService and cleanupAllBackends each build their own, so a per-instance
         * lock made none of them exclusive: the sweep could revert every UID and clear the record
         * while the service was still inside applyRules re-blocking them.
         *
         * ConnectivityManager was moved to a process-wide lock when that was found; iptables and
         * this backend have the identical shape and were simply older than the fix.
         */
        private val mutex = Mutex()

        /**
         * Guards the record of "what each UID looked like before this backend touched it".
         *
         * Observed on hardware: uid 10212 held POLICY_REJECT_ALL (262144). Instance A read it
         * correctly and wrote the blocking policy at 22:04:31.917; instance B read the same uid
         * 3 ms later, got A's value, and saved 1 as the "original". The real value was lost.
         *
         * Now that [mutex] is process-wide this is a second guard over the same window, since every
         * caller below already holds [mutex]. It is kept because it enforces the invariant at the
         * exact read-modify-write rather than relying on callers, and this is the one path that has
         * already destroyed a real user policy. Removing it is a cleanup, not a fix.
         *
         * Lock order is always [mutex] first, then this. Never the other way round.
         */
        private val originalPolicyLock = Mutex()
    }

    private val appliedPolicies = mutableMapOf<Int, Boolean>()

    // Track which policy constant works on this device
    // Starts at the value every ROM enforces, and is upgraded only once calibration proves this ROM
    // implements POLICY_REJECT_ALL - see calibrateBlockingPolicy.
    private var blockingPolicy: Int = POLICY_REJECT_METERED_BACKGROUND
    private var policyTested: Boolean = false

    private var networkPolicyManagerClass: Class<*>? = null
    private var stubClass: Class<*>? = null
    private var asInterfaceMethod: Method? = null
    private var setUidPolicyMethod: Method? = null
    private var getUidPolicyMethod: Method? = null

    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== NetworkPolicyManagerFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with NetworkPolicyManager backend")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "NETWORK_POLICY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ NetworkPolicyManager firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start NetworkPolicyManager firewall"))
        }
    }

    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Initializing reflection")

            if (!initializeReflection()) {
                val error = errorHandler.handleError(
                    Exception("Failed to initialize reflection for NetworkPolicyManager"),
                    "start NetworkPolicyManager firewall"
                )
                return Result.failure(error)
            }

            AppLogger.d(TAG, "✅ Reflection initialized")
            AppLogger.d(TAG, "ℹ️  Policy support will be tested on first rule application")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize reflection", e)
            Result.failure(errorHandler.handleError(e, "initialize NetworkPolicyManager reflection"))
        }
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping NetworkPolicyManager firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
                // Name the backend. The service holds ONE currentBackend, so an unqualified stop
                // tears down whatever it happens to be running - which during a switch is the
                // backend that was just STARTED, not this one.
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "NETWORK_POLICY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "NetworkPolicyManager firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop NetworkPolicyManager firewall"))
        }
    }

    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Cleaning up")

            val result = clearBlockedUidPoliciesInternal()

            AppLogger.d(TAG, "Cleanup complete")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop NetworkPolicyManager firewall"))
        }
    }

    /**
     * Set every UID this backend blocked back to POLICY_NONE.
     *
     * Android persists UID policies in `/data/system/netpolicy.xml`, so a policy written here
     * outlives the firewall, this process, a reboot and even an uninstall - and no Android screen
     * exposes it. Stopping used to clear only the in-memory cache, which left the affected apps
     * blocked with no way back.
     *
     * Use this from outside the backend; [stopInternal] already holds the mutex and calls the
     * internal form directly.
     */
    suspend fun clearOrphanedPolicies(): Result<Unit> = mutex.withLock {
        return clearBlockedUidPoliciesInternal()
    }

    /**
     * Body of [clearOrphanedPolicies]. The caller MUST already hold [mutex].
     *
     * Reverts the union of the in-memory cache and the persisted UID list. The persisted list is
     * what makes cleanup possible from a fresh process - after a crash or a backend switch,
     * [appliedPolicies] is empty but the system policies are still in place.
     */
    private suspend fun clearBlockedUidPoliciesInternal(): Result<Unit> = withContext(Dispatchers.IO) {
        // Off the caller's thread on purpose. stopFirewall() reaches here from viewModelScope, which
        // is Dispatchers.Main.immediate, and this loop makes one blocking binder call per UID -
        // hundreds of them for a block-all-by-default user. On the main thread that is an ANR.
        originalPolicyLock.withLock {
            val originals = loadOriginalPolicies()
            if (originals.isEmpty()) {
                AppLogger.d(TAG, "No UID policies of ours to restore")
                appliedPolicies.clear()
                    return@withContext Result.success(Unit)
            }

            if (!initializeReflection()) {
                AppLogger.e(TAG, "Reflection unavailable - ${originals.size} UID policies left in place")
                    return@withContext Result.failure(
                    errorHandler.handleError(
                        Exception("Failed to initialize reflection for NetworkPolicyManager"),
                        "restore network policies"
                    )
                )
            }

            val networkPolicyManager = getNetworkPolicyManager()
            if (networkPolicyManager == null) {
                AppLogger.e(TAG, "Cannot reach NetworkPolicyManager - ${originals.size} UID policies left in place")
                    return@withContext Result.failure(
                    errorHandler.handleError(
                        Exception("Failed to get NetworkPolicyManager instance"),
                        "restore network policies"
                    )
                )
            }

            // Anything still in here after the loop failed to restore, so it stays on disk for the next
            // attempt rather than being silently forgotten.
            val remaining = originals.toMutableMap()
            originals.forEach { (uid, original) ->
                try {
                    setUidPolicyMethod?.invoke(networkPolicyManager, uid, original)
                    remaining.remove(uid)
                    appliedPolicies.remove(uid)
                    AppLogger.d(TAG, "Restored UID $uid to policy $original")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to restore policy for UID $uid", e)

                    // Drop this UID from the record ONLY when we can PROVE there is nothing left to
                    // undo - it currently holds no policy at all.
                    //
                    // An earlier version dropped on `original == POLICY_NONE` alone. That was wrong
                    // and could lose data: originals are recorded BEFORE the blocking write (see the
                    // durable saveOriginalPolicies above), so POLICY_NONE is the normal recorded
                    // original for every ordinary app we then successfully block. A transient
                    // failure - Shizuku's binder dying mid-loop, permission revoked - would have
                    // erased the record for apps that were still blocked, stranding them offline
                    // with nothing left that knew about it.
                    //
                    // Reading it back settles both cases without guessing at exception types. If the
                    // read fails, readUidPolicy returns null and we keep the record, which is also
                    // what a dead binder produces. Only a clean "no policy here" lets it go.
                    //
                    // What this still fixes: UIDs Android refuses to write - system ones, and the
                    // system UIDs inside a work profile - which have no policy to begin with, so
                    // they read back POLICY_NONE and drain. Measured on hardware 2026-08-23:
                    // 1001, 2000, 1001001, 1001002, 1001027, 1002000 stuck this way while
                    // /data/system/netpolicy.xml held no uid policies at all.
                    val stillSet = readUidPolicy(networkPolicyManager, uid)
                    if (original == POLICY_NONE && stillSet == POLICY_NONE) {
                        AppLogger.w(TAG, "UID $uid rejects writes and holds no policy - dropping it from the record")
                        remaining.remove(uid)
                        appliedPolicies.remove(uid)
                    } else {
                        AppLogger.w(TAG, "UID $uid kept in the record (original=$original, current=$stillSet)")
                    }
                }
            }

            saveOriginalPolicies(remaining)

            return@withContext if (remaining.isEmpty()) {
                AppLogger.d(TAG, "✅ Restored ${originals.size} UID policies")
                appliedPolicies.clear()
                Result.success(Unit)
            } else {
                AppLogger.e(TAG, "❌ ${remaining.size} of ${originals.size} UID policies could not be restored")
                Result.failure(
                    errorHandler.handleError(
                        Exception("${remaining.size} UID policies could not be restored"),
                        "restore network policies"
                    )
                )
            }
        }
    }

    /**
     * Read a UID's current policy. Returns null if it cannot be read, in which case the caller must
     * not write to that UID - overwriting a policy we cannot record would destroy it.
     */
    private fun readUidPolicy(networkPolicyManager: Any, uid: Int): Int? {
        return try {
            getUidPolicyMethod?.invoke(networkPolicyManager, uid) as? Int
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read current policy for UID $uid", e)
            null
        }
    }

    private fun loadOriginalPolicies(): Map<Int, Int> {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(Constants.Settings.KEY_NPM_ORIGINAL_POLICIES, emptySet())
            ?.mapNotNull { entry ->
                val parts = entry.split(":")
                val uid = parts.getOrNull(0)?.toIntOrNull()
                val policy = parts.getOrNull(1)?.toIntOrNull()
                if (uid != null && policy != null) uid to policy else null
            }
            ?.toMap()
            ?: emptyMap()
    }

    /**
     * @param durable flush synchronously. Use it before writing any policy, so the record of what
     * was there cannot be lost by a process death that happens after the write.
     */
    private fun saveOriginalPolicies(originals: Map<Int, Int>, durable: Boolean = false) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putStringSet(
                Constants.Settings.KEY_NPM_ORIGINAL_POLICIES,
                originals.map { (uid, policy) -> "$uid:$policy" }.toSet()
            )

        if (durable) {
            @Suppress("ApplySharedPref")
            editor.commit()
        } else {
            editor.apply()
        }
    }

    /**
     * Apply firewall rules using NetworkPolicyManager reflection.
     *
     * CRITICAL: This runs in NonCancellable context to prevent reflection calls from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * Interrupted operations could leave the firewall in an inconsistent state where some
     * apps are blocked and others aren't.
     */
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = withContext(NonCancellable) {
        mutex.withLock {
            return@withContext try {
                AppLogger.d(TAG, "=== NetworkPolicyManagerFirewallBackend.applyRules() ===")
            AppLogger.d(TAG, "Rules count: ${rules.size}, networkType: $networkType, screenOn: $screenOn")


                val networkPolicyManager = getNetworkPolicyManager()
                if (networkPolicyManager == null) {
                    val error = errorHandler.handleError(
                        Exception("Failed to get NetworkPolicyManager instance"),
                        "apply network policies"
                    )
                    return@withContext Result.failure(error)
                }

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            val allowCritical = prefs.getBoolean(
                Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
            )

            AppLogger.d(TAG, "Default policy: $defaultPolicy (isBlockAllDefault=$isBlockAllDefault, allowCritical=$allowCritical)")

            var appliedCount = 0
            var errorCount = 0
            var systemUidCount = 0

            val rulesByUid = rulesByCurrentUid(rules, io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledUids(context))

            val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
            val allPackages = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
                .getPackagesWithNetworkPermissions(context)

            AppLogger.d(TAG, "Found ${allPackages.size} packages with network permissions across ${userProfiles.size} profiles")

            // The loop below walks packages, so a failed read would apply nothing and still report success.
            if (allPackages.isEmpty()) {
                AppLogger.e(TAG, "Network package read failed - refusing to apply, policies keep their last write")
                return@withContext Result.failure(Exception("Network package read failed"))
            }

            // Pre-compute UIDs that contain critical packages (for UID-level exemption checks)
            // This is needed because we block by UID, not by package - so if ANY package
            // in a UID is critical with no rule, the entire UID should be allowed
            val uidsWithCritical = if (allowCritical) {
                allPackages
                    .filter { Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName, it.uid / 100000) }
                    .map { it.uid }
                    .toSet()
            } else {
                emptySet()
            }

            val desiredPolicies = mutableMapOf<Int, Boolean>()

            allPackages.forEach { appInfo ->
                val uid = appInfo.uid

                // setUidPolicy throws "cannot apply policy to UID <uid>" for any appId outside the
                // installed-app range, and the throw still costs a Shizuku process. Measured on a
                // work-profile device: 6 of these on every pass, forever. Same guard, same reason as
                // ConnectivityManagerFirewallBackend. See issue #93.
                if (!Constants.Firewall.isFirewallableAppUid(uid)) {
                    systemUidCount++
                    return@forEach
                }

                // Never block UIDs that contain system-critical packages or VPN apps
                // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                if (isUidExempted(uid, allPackages)) {
                    // Set, not skipped: a block written before the uid became exempt must be lifted.
                    desiredPolicies[uid] = false
                    return@forEach
                }

                // Not per network: one switch per app here, and WiFi blocking does not work on stock
                // Android, so a per-network rule would leave an app the UI shows blocked on WiFi.
                desiredPolicies[uid] = uidBlockedNow(
                    rulesByUid[uid], isBlockAllDefault, protectedUid = uid in uidsWithCritical,
                    perNetwork = false, networkType = networkType, screenOn = screenOn,
                )
            }

            var skippedCount = 0
            var untouchedCount = 0

            // UIDs this backend has written to, with the policy that was in place beforehand.
            // A UID in this map is "ours"; anything else belongs to the user or the ROM.
            // One lock for every instance: reading a UID's current policy and writing over it must
            // be atomic across the whole process, or a concurrent instance's write gets recorded as
            // the original. See originalPolicyLock.
            originalPolicyLock.withLock {
                val originalPolicies = loadOriginalPolicies().toMutableMap()
                val originalPoliciesBefore = originalPolicies.toMap()

                // Record every original BEFORE a single policy is written, and flush that record to
                // disk first. Writing as we went and saving once at the end inverted the durable
                // order: a process death mid-loop left UIDs holding our blocking policy with nothing
                // on disk saying we put it there. The next run would then read our own block back as
                // that UID's "original" and restore the block forever.
                desiredPolicies.forEach { (uid, shouldBlock) ->
                    if (!shouldBlock) return@forEach
                    if (appliedPolicies[uid] == true) return@forEach
                    if (originalPolicies.containsKey(uid)) return@forEach

                    val existing = readUidPolicy(networkPolicyManager, uid)
                    if (existing == null) {
                        val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"
                        AppLogger.e(TAG, "Cannot read current policy for $packageName (UID $uid) - " +
                                "refusing to overwrite it")
                        return@forEach
                    }
                    originalPolicies[uid] = existing
                }
                if (originalPolicies != originalPoliciesBefore) {
                    saveOriginalPolicies(originalPolicies, durable = true)
                }

                desiredPolicies.forEach { (uid, shouldBlock) ->
                    val currentPolicy = appliedPolicies[uid]

                    if (currentPolicy == shouldBlock) {
                        skippedCount++
                        return@forEach
                    }

                    val isOurs = originalPolicies.containsKey(uid)

                    // Leave every UID we never blocked exactly as it is.
                    //
                    // POLICY_NONE does not mean "no opinion" - it erases whatever policy is set,
                    // including one the user chose. Android's own "Restrict background data" switch
                    // writes POLICY_REJECT_METERED_BACKGROUND, the same value used to block here, and
                    // custom ROMs write POLICY_REJECT_ALL for their per-app restrictions. Writing
                    // POLICY_NONE to every unblocked app silently destroyed all of it.
                    if (!shouldBlock && !isOurs) {
                        untouchedCount++
                        appliedPolicies[uid] = false
                        return@forEach
                    }

                    // No recorded original means the pass above could not read it, so we must not
                    // write - overwriting a policy we cannot record would destroy it.
                    if (shouldBlock && !isOurs) {
                        errorCount++
                        val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"
                        AppLogger.e(TAG, "Not blocking $packageName (UID $uid): its current policy is unrecorded")
                        return@forEach
                    }

                    try {
                        if (shouldBlock) {
                            // Calibrate once, on the first UID actually being blocked. Its original
                            // is already recorded by the pass above, so a calibration write is
                            // recoverable, and the real blocking write follows immediately.
                            if (!policyTested) {
                                // A null answer means this UID could not settle the question, so
                                // try again on the next one instead of latching a verdict.
                                calibrateBlockingPolicy(networkPolicyManager, uid)?.let { calibrated ->
                                    blockingPolicy = calibrated
                                    policyTested = true
                                }
                            }
                            setUidPolicyMethod?.invoke(networkPolicyManager, uid, blockingPolicy)
                        } else {
                            // Ours, and no longer blocked: restore exactly what was there before.
                            // Read first, drop the record only once the write has actually landed.
                            // Removing up front meant a throwing setUidPolicy left the uid holding
                            // our blocking policy with nothing on disk recording that we set it.
                            val original = originalPolicies[uid] ?: POLICY_NONE
                            setUidPolicyMethod?.invoke(networkPolicyManager, uid, original)
                            originalPolicies.remove(uid)
                        }

                        appliedPolicies[uid] = shouldBlock
                        appliedCount++

                        val policyName = when (blockingPolicy) {
                            POLICY_REJECT_ALL -> "REJECT_ALL (WiFi+Mobile)"
                            POLICY_REJECT_METERED_BACKGROUND -> "REJECT_METERED (Mobile only)"
                            else -> "UNKNOWN"
                        }

                        val rulesForUid = rulesByUid[uid]
                        val ruleStatus = if (rulesForUid != null) "has rule" else "no rule (default policy)"

                        val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"

                        AppLogger.d(TAG, "Applied policy for $packageName (UID $uid, $ruleStatus): " +
                                "policy=${if (shouldBlock) "BLOCK ($policyName)" else "RESTORED"}")
                    } catch (e: Exception) {
                        errorCount++
                        val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"
                        AppLogger.e(TAG, "Failed to apply policy for $packageName (UID $uid)", e)
                    }
                }

                if (originalPolicies != originalPoliciesBefore) {
                    saveOriginalPolicies(originalPolicies)
                }
            }

                AppLogger.d(TAG, "✅ Applied $appliedCount policies, skipped $skippedCount unchanged, " +
                        "left $untouchedCount foreign policies alone, $systemUidCount packages on system " +
                        "UIDs Android will not firewall, $errorCount errors")
                if (errorCount > 0) Result.failure(PartialApplyException(errorCount)) else Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply rules", e)
                Result.failure(errorHandler.handleError(e, "apply network policies"))
            }
        }
    }

    override fun isActive(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            if (!isServiceRunning || backendType != "NETWORK_POLICY_MANAGER") {
                return false
            }

            // SharedPreferences says service is running, but verify the service is actually alive
            // This is important after app reinstall (e.g., dev.sh update) where SharedPreferences
            // persist but the service process is killed
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (activityManager != null) {
                @Suppress("DEPRECATION")
                val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
                val serviceClassName = "io.github.dorumrr.de1984.data.service.PrivilegedFirewallService"
                val isServiceActuallyRunning = runningServices.any { service ->
                    service.service.className == serviceClassName
                }

                if (!isServiceActuallyRunning) {
                    AppLogger.w(TAG, "SharedPreferences says privileged service is running, but service is not actually running. Clearing flags.")
                    prefs.edit()
                        .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
                        .remove(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE)
                        .apply()
                    return false
                }

                return true
            }

            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check if NetworkPolicyManager firewall is active", e)
            false
        }
    }

    override fun getType(): FirewallBackendType = FirewallBackendType.NETWORK_POLICY_MANAGER

    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            AppLogger.d(TAG, "=== NetworkPolicyManagerFirewallBackend.checkAvailability() ===")
            
            if (!shizukuManager.hasShizukuPermission) {
                AppLogger.d(TAG, "❌ NetworkPolicyManager not available: No Shizuku permission")
                val error = errorHandler.createRootRequiredError("NetworkPolicyManager firewall")
                return Result.failure(error)
            }
            
            if (!initializeReflection()) {
                AppLogger.e(TAG, "❌ NetworkPolicyManager not available: Failed to initialize reflection")
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "NetworkPolicyManager firewall",
                    reason = "Failed to access NetworkPolicyManager API (reflection failed)"
                )
                return Result.failure(error)
            }
            
            val networkPolicyManager = getNetworkPolicyManager()
            if (networkPolicyManager == null) {
                AppLogger.e(TAG, "❌ NetworkPolicyManager not available: Failed to get service instance")
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "NetworkPolicyManager firewall",
                    reason = "Failed to access NetworkPolicyManager service"
                )
                return Result.failure(error)
            }
            
            AppLogger.d(TAG, "✅ NetworkPolicyManager is available")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "NetworkPolicyManager availability check failed", e)
            Result.failure(errorHandler.handleError(e, "check NetworkPolicyManager availability"))
        }
    }

    private fun initializeReflection(): Boolean {
        if (networkPolicyManagerClass != null && 
            stubClass != null && 
            asInterfaceMethod != null && 
            setUidPolicyMethod != null &&
            getUidPolicyMethod != null) {
            return true
        }

        return try {
            AppLogger.d(TAG, "Initializing reflection for NetworkPolicyManager...")
            
            networkPolicyManagerClass = Class.forName("android.net.INetworkPolicyManager")
            AppLogger.d(TAG, "✅ Found INetworkPolicyManager class")
            
            stubClass = Class.forName("android.net.INetworkPolicyManager\$Stub")
            AppLogger.d(TAG, "✅ Found INetworkPolicyManager.Stub class")
            
            asInterfaceMethod = stubClass?.getMethod("asInterface", IBinder::class.java)
            AppLogger.d(TAG, "✅ Found asInterface method")
            
            setUidPolicyMethod = networkPolicyManagerClass?.getMethod(
                "setUidPolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            AppLogger.d(TAG, "✅ Found setUidPolicy method")
            
            getUidPolicyMethod = networkPolicyManagerClass?.getMethod(
                "getUidPolicy",
                Int::class.javaPrimitiveType
            )
            AppLogger.d(TAG, "✅ Found getUidPolicy method")
            
            AppLogger.d(TAG, "✅ Reflection initialization complete")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize reflection", e)
            networkPolicyManagerClass = null
            stubClass = null
            asInterfaceMethod = null
            setUidPolicyMethod = null
            getUidPolicyMethod = null
            false
        }
    }

    private suspend fun getNetworkPolicyManager(): Any? = withContext(Dispatchers.IO) {
        try {
            val serviceBinder = shizukuManager.getSystemServiceBinder(SERVICE_NAME)
            if (serviceBinder == null) {
                AppLogger.e(TAG, "Failed to get system service binder for: $SERVICE_NAME")
                return@withContext null
            }
            
            val networkPolicyManager = asInterfaceMethod?.invoke(null, serviceBinder)
            if (networkPolicyManager == null) {
                AppLogger.e(TAG, "Failed to convert binder to INetworkPolicyManager")
                return@withContext null
            }
            
            AppLogger.d(TAG, "✅ Got NetworkPolicyManager instance")
            return@withContext networkPolicyManager
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get NetworkPolicyManager instance", e)
            return@withContext null
        }
    }

    /**
     * Decide which policy value actually blocks on this ROM.
     *
     * The previous probe wrote to **UID 0** and treated "did not throw" as support. Android's
     * NetworkPolicyManagerService rejects a policy on any non-app UID, so that call threw on every
     * device and every Android version, and the backend permanently degraded to blocking metered
     * background data only - while the UI kept saying the app was Blocked.
     *
     * @return the policy to block with, or null if this UID could not settle the question. Null must
     * not latch a verdict: calibrating on a UID that throws would pin the weakest policy for the
     * whole process. applyRules now filters non-app UIDs out before they reach here, so the check
     * below is defence in depth rather than the only guard.
     */
    private suspend fun calibrateBlockingPolicy(networkPolicyManager: Any, uid: Int): Int? {
        if (!Constants.Firewall.isFirewallableAppUid(uid)) {
            AppLogger.d(TAG, "UID $uid is not an app UID - not calibrating on it")
            return null
        }

        return try {
            setUidPolicyMethod?.invoke(networkPolicyManager, uid, POLICY_REJECT_ALL)

            val storedPolicy = readUidPolicy(networkPolicyManager, uid)

            when {
                storedPolicy == POLICY_ALLOW_METERED_BACKGROUND -> {
                    // The historical trap: 0x4 used to be hard-coded here as "REJECT_ALL". If a ROM
                    // ever stores it in response to a blocking write, we are granting an allowance to
                    // an app the UI shows as blocked. Never keep it.
                    AppLogger.e(TAG, "❌ This ROM stored POLICY_ALLOW_METERED_BACKGROUND for a " +
                            "blocking write - that is an ALLOWANCE, not a block. Falling back to " +
                            "POLICY_REJECT_METERED_BACKGROUND")
                    POLICY_REJECT_METERED_BACKGROUND
                }

                storedPolicy != POLICY_REJECT_ALL -> {
                    AppLogger.w(TAG, "⚠️  This ROM did not store POLICY_REJECT_ALL - falling back to " +
                            "POLICY_REJECT_METERED_BACKGROUND | WiFi will NOT be blocked, only " +
                            "metered background data | For full blocking use the iptables backend")
                    POLICY_REJECT_METERED_BACKGROUND
                }

                romEnforcesRejectAll(uid) -> {
                    AppLogger.d(TAG, "✅ POLICY_REJECT_ALL is supported - blocking WiFi and Mobile")
                    POLICY_REJECT_ALL
                }

                else -> {
                    AppLogger.w(TAG, "⚠️  This ROM stored POLICY_REJECT_ALL but does not know the " +
                            "constant, so nothing enforces it - falling back to " +
                            "POLICY_REJECT_METERED_BACKGROUND | WiFi will NOT be blocked")
                    POLICY_REJECT_METERED_BACKGROUND
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Calibration on UID $uid was inconclusive - will try another UID", e)
            null
        }
    }

    /**
     * Whether this ROM actually implements POLICY_REJECT_ALL, rather than merely storing the number.
     *
     * Reading the value back is not enough. AOSP's `setUidPolicy` does not validate the policy bits,
     * so a ROM with no REJECT_ALL support stores `0x40000` and hands it back unchanged while nothing
     * enforces it - every "blocked" app would then have full network access, which is worse than the
     * metered-background fallback. `POLICY_REJECT_ALL` is not in AOSP at all; LineageOS and similar
     * ROMs add it.
     *
     * The ROM's own dumpsys decoder is the honest signal: it prints the constant's name only for a
     * value it knows about.
     */
    private suspend fun romEnforcesRejectAll(uid: Int): Boolean {
        return try {
            val (exitCode, output) = shizukuManager.executeShellCommand("dumpsys netpolicy")
            if (exitCode != 0) {
                AppLogger.w(TAG, "Could not read dumpsys netpolicy to confirm REJECT_ALL support")
                return false
            }
            output.lineSequence().any { it.contains("UID=$uid ") && it.contains("REJECT_ALL") }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to confirm REJECT_ALL support", e)
            false
        }
    }

    override fun supportsGranularControl(): Boolean = false

    fun clearAppliedPoliciesCache() {
        appliedPolicies.clear()
        AppLogger.d(TAG, "Cleared applied policies cache (forced)")
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     *
     * userId has NO DEFAULT on purpose. It used to default to 0, and every enforcement call site
     * omitted it - so a VPN app installed only in the work profile was looked up in the personal
     * profile, not found, and treated as an ordinary app. Block All then cut the work profile's
     * VPN. Making it required means the compiler catches the next caller that forgets.
     */
    private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
                // GET_PERMISSIONS is requested but never read. It is here so this shares a cache
                // entry with the getPackagesWithNetworkPermissions sweep, which asks for both.
                // getPackageInfoAsUser keys its cache on "userId:flags:packageName", so GET_SERVICES
                // alone (4) is a different key from GET_PERMISSIONS or GET_SERVICES (4100) and every
                // call was a guaranteed miss - one binder round trip per app, inside a filter over
                // every package. Measured on hardware: 53 hit / 587 miss before, and the whole
                // Block All start took 8.35s.
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isUidExempted(uid: Int, allPackages: List<android.content.pm.ApplicationInfo>): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        val packagesWithUid = allPackages.filter { it.uid == uid }

        return packagesWithUid.any { appInfo ->
            (!allowCritical && Constants.Firewall.isSystemCritical(appInfo.packageName)) ||
            (!allowCritical && hasVpnService(appInfo.packageName, appInfo.uid / 100000))
        }
    }
}

