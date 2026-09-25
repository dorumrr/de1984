package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.uidBlockedNow
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConnectivityManagerFirewallBackend(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    companion object {
        private const val TAG = "ConnectivityManagerFirewall"
        private const val SERVICE_NAME = "connectivity"
        private const val MIN_API_LEVEL = Build.VERSION_CODES.TIRAMISU // Android 13
        private const val FIREWALL_CHAIN_OEM_DENY_3 = 3 // OEM-specific deny chain

        /**
         * How long the WHOLE restore may take, not one command of it.
         *
         * ShellRunner bounds each command, but [restoreBlockedPackages] issues one per denied
         * package, so N packages simply bought N ceilings and the job itself stayed unbounded.
         *
         * Sixty seconds is far above the honest cost - `cmd connectivity` answers in tens of
         * milliseconds, so even several hundred packages finish in seconds - and it only ever bites
         * when something is already wrong. Anything not reached comes back as failed, which keeps it
         * in the durable record, so the next stop retries it. The loop was already built to resume.
         */
        private const val RESTORE_DEADLINE_MS = 60_000L

        // Android's exit-255 answer to an enable when the app holds no denial: the goal state, not a failure.
        private const val NO_DENIAL_ANSWER = "sUidOwnerMap does not have entry for uid"

        /**
         * Process-wide, NOT per-instance.
         *
         * Everything this backend guards - the OEM_DENY_3 chain, the system's per-uid denials,
         * the on-disk record - is shared by every instance. FirewallManager.cleanupAllBackends()
         * builds a second instance while the privileged service may still be inside applyRules, and
         * a per-instance lock let the sweep restore every package and write an empty record while
         * that apply re-denied them, leaving apps offline with the firewall off.
         */
        private val mutex = Mutex()
    }

    private val appliedPolicies = mutableMapOf<String, Boolean>()

    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== ConnectivityManagerFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with ConnectivityManager backend")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "CONNECTIVITY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ ConnectivityManager firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start ConnectivityManager firewall"))
        }
    }

    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Enabling firewall chain")

            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled true")
            if (exitCode != 0) {
                val error = "Failed to enable firewall chain: $output"
                AppLogger.e(TAG, error)
                return Result.failure(Exception(error))
            }

            // Recorded durably so a fresh process after a crash knows this backend ran and has
            // something to undo. It records "De1984 asked for this chain", NOT "De1984 was first":
            // set-chain3-enabled reports success whether or not the chain was already on, and the
            // connectivity shell offers no getter, so a chain an OEM had already enabled cannot be
            // told apart from one we enabled. Undoing it on stop is what the app has always done.
            setChain3EnabledByUs(true)

            AppLogger.d(TAG, "✅ Firewall chain enabled (FIREWALL_CHAIN_OEM_DENY_3)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to enable firewall chain", e)
            Result.failure(errorHandler.handleError(e, "enable ConnectivityManager firewall chain"))
        }
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping ConnectivityManager firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
                // Name the backend. The service holds ONE currentBackend, so an unqualified stop
                // tears down whatever it happens to be running - which during a switch is the
                // backend that was just STARTED, not this one.
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "CONNECTIVITY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "ConnectivityManager firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop ConnectivityManager firewall"))
        }
    }

    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Disabling firewall chain")

            // Re-enable networking for every package we denied, BEFORE the chain goes down.
            // Disabling the chain alone only masks those denials; they stay recorded in the system
            // and come back the moment anything turns the chain on again.
            val notRestored = restoreBlockedPackages()

            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled false")
            var chainStillEnabled = false
            if (exitCode != 0) {
                AppLogger.e(TAG, "Failed to disable firewall chain: $output")
                // This used to say "Don't fail on stop - just log the warning" and swallow it. That
                // is the same hole the iptables backend had: the command that switches enforcement
                // off can fail and the teardown still reports success. OEM_DENY_3 stays enabled
                // system-wide, ready to re-arm any denial still recorded in the system, while the
                // app shows the firewall as off.
                chainStillEnabled = chain3EnabledByUs()
            } else {
                setChain3EnabledByUs(false)
            }

            appliedPolicies.clear()
            AppLogger.d(TAG, "Cleared applied policies cache")

            if (notRestored.isNotEmpty()) {
                AppLogger.e(TAG, "❌ ${notRestored.size} packages still denied networking after stop")
                return Result.failure(
                    errorHandler.handleError(
                        Exception("${notRestored.size} packages could not have networking restored"),
                        "restore ConnectivityManager package networking"
                    )
                )
            }

            // Only OUR chain counts. If we never turned OEM_DENY_3 on, a failed disable leaves it
            // exactly as we found it and is not ours to report.
            if (chainStillEnabled) {
                return Result.failure(
                    errorHandler.handleError(
                        Exception("the OEM_DENY_3 firewall chain we enabled could not be disabled"),
                        "disable ConnectivityManager firewall chain"
                    )
                )
            }

            AppLogger.d(TAG, "Firewall chain disabled")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to disable firewall chain", e)
            Result.failure(errorHandler.handleError(e, "disable ConnectivityManager firewall chain"))
        }
    }

    /**
     * Apply firewall rules using ConnectivityManager shell commands.
     *
     * CRITICAL: This runs in NonCancellable context to prevent shell commands from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * Interrupted commands could leave the firewall in an inconsistent state where some
     * apps are blocked and others aren't.
     */
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = withContext(NonCancellable) {
        mutex.withLock {
            return@withContext try {
                AppLogger.d(TAG, "=== ConnectivityManagerFirewallBackend.applyRules() ===")
            AppLogger.d(TAG, "Rules count: ${rules.size}, networkType: $networkType, screenOn: $screenOn")

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            AppLogger.d(TAG, "Default policy: $defaultPolicy (isBlockAllDefault=$isBlockAllDefault)")

            var appliedCount = 0
            var errorCount = 0
            var skippedCount = 0
            var systemUidCount = 0
            var untouchedCount = 0

            val enabledRules = rules.filter { it.enabled }
            val rulesByUid = enabledRules.groupBy { it.uid }
            val ruledPackages = enabledRules.mapTo(HashSet()) { it.packageName }

            val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
            val allPackages = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
                .getPackagesWithNetworkPermissions(context)

            AppLogger.d(TAG, "Found ${allPackages.size} packages with network permissions across ${userProfiles.size} profiles")

            // The loop below walks packages, so a failed read would apply nothing and still report success.
            if (allPackages.isEmpty()) {
                AppLogger.e(TAG, "Network package read failed - refusing to apply, policies keep their last write")
                return@withContext Result.failure(Exception("Network package read failed"))
            }

            // One verdict per package name: a command acts on that app in every profile, so decide the own copy.
            val ownUserId = Constants.Firewall.ownUserId()
            val ownPackages = allPackages.filter {
                FirewallBackendType.CONNECTIVITY_MANAGER.reachesUser(it.uid / 100000, ownUserId)
            }

            val desiredPolicies = mutableMapOf<String, Boolean>()

            val allowCritical = prefs.getBoolean(
                Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
            )

            val criticalOrVpnUids = ownPackages
                .filter { Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName, it.uid / 100000) }
                .map { it.uid }
                .toSet()

            // Android applies each command to the package's whole uid, so a uid gets one verdict.
            ownPackages.groupBy { it.uid }.forEach { (uid, packagesInUid) ->
                if (!Constants.Firewall.isFirewallableAppUid(uid)) {
                    systemUidCount += packagesInUid.size
                    return@forEach
                }

                val protectedUid = uid in criticalOrVpnUids
                // Not per network: this backend shows one switch per app, so a rule left over from
                // iptables or VPN with one network blocked must block every network, as the switch says.
                val shouldBlock = !(protectedUid && !allowCritical) && uidBlockedNow(
                    rulesByUid[uid], isBlockAllDefault, protectedUid,
                    perNetwork = false, networkType = networkType, screenOn = screenOn,
                )
                AppLogger.d(TAG, "🔍 [RULE DEBUG] UID $uid ${packagesInUid.map { it.packageName }}: " +
                        "${rulesByUid[uid]?.size ?: 0} rule(s), protected=$protectedUid → shouldBlock=$shouldBlock")

                packagesInUid.forEach { desiredPolicies[it.packageName] = shouldBlock }
            }

            AppLogger.d(TAG, "🔍 [CACHE DEBUG] appliedPolicies cache size: ${appliedPolicies.size}, desiredPolicies size: ${desiredPolicies.size}")

            // Record what we are ABOUT to deny, durably, before the first command runs. If the
            // process dies mid-loop the record still covers everything that was denied. It
            // deliberately over-records: a package whose command later failed is listed anyway, and
            // re-enabling an already-enabled package is a harmless no-op. The other direction -
            // denying a package we never recorded - strands it with no network and nothing to
            // point at. One synchronous write per apply, not one per package.
            // Union, not overwrite. After a process restart appliedPolicies is empty, so this loop
            // also re-enables packages that were denied by the previous process. Overwriting the
            // record first would drop them from it, and a death before the loop reached them would
            // strand them with no network and no record to undo it.
            val intendedBlocked = desiredPolicies.filterValues { it }.keys.toSet()
            val existingRecord = loadBlockedPackages()
            // Only pay for the synchronous commit when the record would actually change. Under the
            // Block All default intendedBlocked is close to every network-capable package, and this
            // runs on every network change, screen toggle and rule edit.
            if (!existingRecord.containsAll(intendedBlocked)) {
                saveBlockedPackages(existingRecord + intendedBlocked, durable = true)
            }

            desiredPolicies.forEach { (packageName, shouldBlock) ->
                // Nothing to lift: we have never denied this package, so there is no denial to
                // undo and no command to send. Without this every untouched package cost one
                // Shizuku process per pass, rejected with "sUidOwnerMap does not have entry".
                //
                // One-directional on purpose. existingRecord says what we INTENDED to deny, not
                // what is denied now - it is written before the commands run and survives a reboot
                // that clears the denials themselves - so it must never short-circuit a BLOCK.
                // See issue #93.
                if (!shouldBlock && appliedPolicies[packageName] != true && packageName !in existingRecord) {
                    untouchedCount++
                    return@forEach
                }

                val currentPolicy = appliedPolicies[packageName]

                if (currentPolicy == shouldBlock) {
                    skippedCount++
                    if (packageName in ruledPackages) {
                        AppLogger.d(TAG, "🔍 [CACHE DEBUG] SKIPPED $packageName: currentPolicy=$currentPolicy, shouldBlock=$shouldBlock (has rule)")
                    }
                    return@forEach
                }
                
                AppLogger.d(TAG, "🔍 [CACHE DEBUG] APPLYING $packageName: currentPolicy=$currentPolicy → shouldBlock=$shouldBlock")

                try {
                    val (done, output) = setPackageNetworking(packageName, enabled = !shouldBlock)

                    if (done) {
                        appliedCount++
                        appliedPolicies[packageName] = shouldBlock
                        val ruleStatus = if (packageName in ruledPackages) "has rule" else "no rule (default policy)"
                        AppLogger.d(TAG, "Applied policy for $packageName ($ruleStatus): " +
                                "policy=${if (shouldBlock) "BLOCK (all networks)" else "ALLOW"}")
                    } else {
                        errorCount++
                        AppLogger.e(TAG, "Failed to apply policy for $packageName: $output")
                    }
                } catch (e: Exception) {
                    errorCount++
                    AppLogger.e(TAG, "Failed to apply policy for $packageName", e)
                }
            }

            // Update the record from what this pass actually did, rather than overwriting it with
            // appliedPolicies. A package can be denied and absent from desiredPolicies - it was
            // uninstalled, disabled, or moved out of the enumeration - and after a cache clear it is
            // in neither map. Overwriting dropped it from the record while the system denial stood,
            // leaving it offline with nothing left to undo it. Only an explicit re-enable removes a
            // package from the record.
            val record = loadBlockedPackages().toMutableSet()
            appliedPolicies.forEach { (pkg, isBlocked) ->
                if (isBlocked) record.add(pkg) else record.remove(pkg)
            }
            saveBlockedPackages(record)

                AppLogger.d(TAG, "✅ Applied $appliedCount policies, skipped $skippedCount unchanged, $untouchedCount never ours, $systemUidCount packages on system UIDs Android will not firewall, $errorCount errors")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply rules", e)
                Result.failure(errorHandler.handleError(e, "apply connectivity manager rules"))
            }
        }
    }

    override fun isActive(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            if (!isServiceRunning || backendType != "CONNECTIVITY_MANAGER") {
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
            AppLogger.e(TAG, "Failed to check if ConnectivityManager firewall is active", e)
            false
        }
    }

    override fun getType(): FirewallBackendType = FirewallBackendType.CONNECTIVITY_MANAGER

    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT < MIN_API_LEVEL) {
                val error = "ConnectivityManager firewall requires Android 13+"
                return Result.failure(Exception(error))
            }

            if (!shizukuManager.hasShizukuPermission) {
                val error = "Shizuku permission required"
                return Result.failure(Exception(error))
            }

            val (_, output) = shizukuManager.executeShellCommand("cmd connectivity help")

            if (!output.contains("set-chain3-enabled")) {
                val error = "ConnectivityManager firewall chain API not available"
                return Result.failure(Exception(error))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "ConnectivityManager firewall not available", e)
            Result.failure(errorHandler.handleError(e, "check ConnectivityManager availability"))
        }
    }

    override fun supportsGranularControl(): Boolean = false

    fun clearAppliedPoliciesCache() {
        appliedPolicies.clear()
        AppLogger.d(TAG, "Cleared applied policies cache (forced)")
    }

    /**
     * Undo everything this backend did to the system, from any process.
     *
     * Exists because none of it lives in this app. `set-package-networking-enabled false` and
     * `set-chain3-enabled true` are system state: if De1984 is force-stopped, crashes, or is
     * uninstalled while this backend is running, nothing calls [stopInternal] and the denied apps
     * stay offline with no De1984 on screen to explain it. FirewallManager.cleanupAllBackends()
     * calls this on every stop, so a fresh process cleans up after a dead one.
     *
     * Returns success without touching anything when there is no record of ours - the normal case
     * for the many users who never run this backend. That matters: blindly disabling chain3 could
     * clobber an OEM that uses it for its own purposes.
     */
    suspend fun clearOrphanedPolicies(): Result<Unit> = mutex.withLock {
        val blocked = loadBlockedPackages()
        val weEnabledChain3 = chain3EnabledByUs()

        if (blocked.isEmpty() && !weEnabledChain3) {
            AppLogger.d(TAG, "No ConnectivityManager state of ours to undo")
            appliedPolicies.clear()
            return Result.success(Unit)
        }

        AppLogger.d(TAG, "Undoing ConnectivityManager state: ${blocked.size} denied packages, chain3ByUs=$weEnabledChain3")

        val notRestored = restoreBlockedPackages()

        // A non-empty package record is equally good evidence that this backend ran, and covers an
        // upgrade from a build that never wrote the flag.
        var chainStillEnabled = false
        if (weEnabledChain3 || blocked.isNotEmpty()) {
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled false")
            if (exitCode == 0) {
                setChain3EnabledByUs(false)
            } else {
                AppLogger.e(TAG, "Failed to disable firewall chain during cleanup: $output")
                // Reported, not just logged - same reasoning as stopInternal. Read the flag back
                // rather than reusing weEnabledChain3: it stays true on a failed disable, and that
                // is exactly the state that means the chain is still ours and still on.
                chainStillEnabled = chain3EnabledByUs()
            }
        }

        appliedPolicies.clear()

        if (notRestored.isEmpty() && chainStillEnabled) {
            return Result.failure(
                errorHandler.handleError(
                    Exception("the OEM_DENY_3 firewall chain we enabled could not be disabled"),
                    "disable ConnectivityManager firewall chain"
                )
            )
        }

        return if (notRestored.isEmpty()) {
            AppLogger.d(TAG, "✅ Restored networking for ${blocked.size} packages")
            Result.success(Unit)
        } else {
            AppLogger.e(TAG, "❌ ${notRestored.size} of ${blocked.size} packages could not have networking restored")
            Result.failure(
                errorHandler.handleError(
                    Exception("${notRestored.size} packages could not have networking restored"),
                    "restore ConnectivityManager package networking"
                )
            )
        }
    }

    private suspend fun restoreBlockedPackages(): Set<String> {
        val blocked = loadBlockedPackages()
        if (blocked.isEmpty()) return emptySet()

        val restored = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + RESTORE_DEADLINE_MS

        for (packageName in blocked) {
            if (System.currentTimeMillis() >= deadline) {
                val unreached = blocked - restored - failed
                failed.addAll(unreached)
                AppLogger.e(
                    TAG,
                    "❌ Restore gave up after ${RESTORE_DEADLINE_MS}ms with ${unreached.size} of " +
                        "${blocked.size} package(s) not reached - they stay in the record and the " +
                        "next stop retries them"
                )
                break
            }

            try {
                val (done, output) = setPackageNetworking(packageName, enabled = true)
                if (done) {
                    restored.add(packageName)
                    appliedPolicies.remove(packageName)
                    AppLogger.d(TAG, "Restored networking for $packageName")
                } else if (!isInstalled(packageName)) {
                    // Proof that there is nothing left to undo: the package is gone, so the system
                    // has no denial to hold against it. Without this a package uninstalled while
                    // denied stayed in the record forever, and every stop from then on reported a
                    // teardown failure that could never be cleared.
                    restored.add(packageName)
                    appliedPolicies.remove(packageName)
                    AppLogger.w(TAG, "$packageName is no longer installed - dropping it from the record")
                } else {
                    failed.add(packageName)
                    AppLogger.e(TAG, "Failed to restore networking for $packageName: $output")
                }
            } catch (e: CancellationException) {
                // The teardown itself was cancelled - PrivilegedFirewallService waits five seconds
                // for it and then cancels its scope. Let the cancel through, but write down what
                // was restored first, so the next stop retries only what is genuinely left.
                //
                // The `catch (e: Exception)` this splits out of used to swallow it and then grind
                // through every remaining package, each one failing instantly on the same
                // cancellation, before saving anyway.
                withContext(NonCancellable) {
                    saveBlockedPackages(loadBlockedPackages() - restored, durable = true)
                }
                throw e
            } catch (e: Exception) {
                failed.add(packageName)
                AppLogger.e(TAG, "Failed to restore networking for $packageName", e)
            }
        }

        // Re-read the record instead of writing the snapshot taken before the loop. This function
        // can run on a second backend instance - cleanupAllBackends() builds a fresh one - while the
        // service instance is still inside applyRules and denying new packages. Writing the stale
        // snapshot would erase whatever it recorded in the meantime.
        saveBlockedPackages(loadBlockedPackages() - restored, durable = true)
        return failed
    }

    private suspend fun setPackageNetworking(packageName: String, enabled: Boolean): Pair<Boolean, String> {
        val (exitCode, output) = shizukuManager.executeShellCommand(
            "cmd connectivity set-package-networking-enabled $enabled $packageName"
        )
        return (exitCode == 0 || (enabled && output.contains(NO_DENIAL_ANSWER))) to output
    }

    /**
     * Is this package still installed for any user?
     *
     * Deliberately conservative: any failure to answer returns true, so an unreadable package is
     * kept in the record rather than dropped. Dropping is only ever allowed on a definite "gone".
     */
    private fun isInstalled(packageName: String): Boolean {
        return try {
            // MATCH_UNINSTALLED_PACKAGES covers "pm uninstall -k" - app removed, data kept - and
            // MATCH_DISABLED_COMPONENTS covers an app the user or a device admin disabled. Plain
            // getApplicationInfo(name, 0) throws NameNotFound for both, so a package that is only
            // temporarily out of sight was counted as gone and erased from the record. Re-enable or
            // reinstall it and it comes back with our denial still applied and nothing left able to
            // undo it. Neither is "gone"; only a package with no trace at all is.
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DISABLED_COMPONENTS
            context.packageManager.getApplicationInfo(packageName, flags)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            true
        }
    }

    private fun loadBlockedPackages(): Set<String> {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(Constants.Settings.KEY_CM_BLOCKED_PACKAGES, emptySet())?.toSet()
            ?: emptySet()
    }

    /**
     * @param durable flush synchronously. Use it before denying anything and after restoring, so
     * the record cannot be lost by a process death that lands between the write and the command.
     */
    private suspend fun saveBlockedPackages(packages: Set<String>, durable: Boolean = false) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putStringSet(Constants.Settings.KEY_CM_BLOCKED_PACKAGES, packages)
            if (durable) {
                @Suppress("ApplySharedPref")
                editor.commit()
            } else {
                editor.apply()
            }
        }
    }

    private fun chain3EnabledByUs(): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Constants.Settings.KEY_CM_CHAIN3_ENABLED_BY_US, false)
    }

    private suspend fun setChain3EnabledByUs(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            @Suppress("ApplySharedPref")
            prefs.edit().putBoolean(Constants.Settings.KEY_CM_CHAIN3_ENABLED_BY_US, enabled).commit()
        }
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
}

