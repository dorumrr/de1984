package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.rulesByCurrentUid
import io.github.dorumrr.de1984.domain.firewall.uidBlockedNow
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptablesFirewallBackend(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {
    
    companion object {
        private const val TAG = "IptablesFirewall"

        // Custom chain name to avoid conflicts with Android netd
        // Note: We only use OUTPUT chain because the owner module only works for OUTPUT
        // (locally generated packets). INPUT chain cannot match by UID.
        private val CHAIN_OUTPUT = "${Constants.App.ROOT_NAME_PREFIX}_output"

        private const val IPTABLES = "iptables"
        private const val IP6TABLES = "ip6tables"

        // Hoisted out of applyLanRulesBatch so resyncChain writes the same ranges it deletes.
        // Two lists would drift, and a range only one of them knew about would be unremovable.
        private val LAN_RANGES_V4 = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
        private val LAN_RANGES_V6 = listOf("fc00::/7", "fe80::/10")

        // Printed by any resync command that fails. The script's exit code cannot carry this: a
        // shell reports the status of its LAST line, so a command that fails in the middle is
        // invisible behind a successful final one. Proved on device - a script whose second of
        // three -A commands failed still exited 0, with only 2 of 3 rules installed.
        //
        // Split by family because the two failures mean different things. A rejected IPv4 rule is
        // a hole in the firewall and must fail the apply; a rejected IPv6 rule usually means a ROM
        // without the IPv6 owner match, which the old diff path tolerated by degrading to v4-only.
        private const val ADD_FAIL_V4 = "DE1984_ADD_FAIL4"
        private const val ADD_FAIL_V6 = "DE1984_ADD_FAIL6"

        // Separators for the chain dump appended to every write script. Reading the chain back is
        // the only way to know what actually landed: iptables writes can fail individually and the
        // exit code carries only the last one.
        private const val STATE_V4 = "DE1984_STATE4"
        private const val STATE_V6 = "DE1984_STATE6"

        private val UID_OWNER_REGEX = Regex("--uid-owner (\\d+)")

        // A stale rule that would not delete. Protection is intact - the new rules are already in
        // front of it - but a leftover duplicate is what breaks unblocking, so it must be retried.
        private const val TRIM_FAIL = "DE1984_TRIM_FAIL"

        private const val PROBE_PRESENT = "DE1984_CHAIN_PRESENT"
        private const val PROBE_ABSENT = "DE1984_CHAIN_ABSENT"
        private const val PROBE_NOPRIV = "DE1984_CHAIN_NOPRIV"

        /**
         * Process-wide, NOT per-instance. Same shape and same reason as
         * ConnectivityManagerFirewallBackend's.
         *
         * The de1984_output chains live in the kernel and the "chains are installed" record lives
         * on disk, so both are shared by every caller. A per-instance lock made none of them
         * exclusive: the sweep could delete the chains while the service was still inside applyRules
         * adding rules to them.
         *
         * This stays process-wide even now that there is one shared instance (see
         * De1984Dependencies.iptablesBackend). Do NOT downgrade it: the callers reach this object
         * from different coroutines - a firewall start, the service's rules flow, the cold-start
         * sweep - and the lock is what keeps them off the chains at the same time. One instance
         * removed the duplicate WORK; it did not remove the concurrency.
         *
         * ConnectivityManager was moved to a process-wide lock when that was found; this backend and
         * NetworkPolicyManager have the identical shape and were simply older than the fix.
         *
         * None of the locking functions here call another, so widening the scope cannot deadlock.
         */
        private val mutex = Mutex()
    }

    private val blockedUids = mutableSetOf<Int>()

    private val blockedLanUids = mutableSetOf<Int>()

    /**
     * True while the kernel chain may hold rules this instance never wrote.
     *
     * iptables state lives in the kernel, so it survives a crash, a force-stop and the app itself;
     * `blockedUids` above does not. On a first apply in a fresh process that set is empty while the
     * chain may be full, so "what this object has applied" is not "what the chain contains".
     *
     * There is now exactly ONE instance of this class per process
     * (De1984Dependencies.iptablesBackend). Do not add another: this flag is per-instance, so a
     * second object arms its own copy and rewrites the whole chain even when the first has just
     * done it. That cost 10.4 s of shell work per start, measured on hardware, until it was
     * found.
     *
     * Starts true for exactly that reason, and is reset by startInternal() because a start can
     * adopt a chain a previous process left behind.
     */
    private var chainNeedsResync = true

    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== IptablesFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with iptables backend")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "IPTABLES")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ iptables firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start iptables firewall", e)
            val error = errorHandler.handleError(e, "start iptables firewall")
            Result.failure(error)
        }
    }

    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Creating iptables chains")

            checkAvailability().getOrElse { error ->
                return Result.failure(error)
            }

            createCustomChains().getOrElse { error ->
                return Result.failure(error)
            }

            // iptables rules live in the kernel, not in this process, so they outlive a crash, a
            // force-stop and the app itself. This flag is the only thing that tells a later, fresh
            // instance that there is something out there to undo - and therefore whether an
            // unverifiable teardown is "nothing to do" or "we just failed to remove live rules".
            // commit(), not apply(): the chains exist now, so the record must exist now too.
            setChainsInstalled(true)

            // createCustomChains() is "-N ... || true": if the chain was already there it is
            // adopted as-is, contents and all. Whatever it holds was written by a process that is
            // gone, so the next applyRules must rewrite it rather than diff against it.
            chainNeedsResync = true

            AppLogger.d(TAG, "✅ iptables chains created")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create iptables chains", e)
            val error = errorHandler.handleError(e, "create iptables chains")
            Result.failure(error)
        }
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping iptables firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
                // Name the backend. The service holds ONE currentBackend, so an unqualified stop
                // tears down whatever it happens to be running - which during a switch is the
                // backend that was just STARTED, not this one.
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "IPTABLES")
            }
            context.startService(intent)

            AppLogger.d(TAG, "iptables firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop iptables firewall", e)
            val error = errorHandler.handleError(e, "stop iptables firewall")
            Result.failure(error)
        }
    }

    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Deleting iptables chains")

            val chainsWereInstalled = wereChainsInstalled()

            clearAllRules().getOrElse { error ->
                AppLogger.w(TAG, "Failed to clear rules during stop: ${error.message}")
            }

            deleteCustomChains().getOrElse { error ->
                AppLogger.w(TAG, "Failed to delete chains during stop: ${error.message}")
            }

            blockedUids.clear()
            blockedLanUids.clear()

            // Both sets were just emptied and the chains were just deleted, so this instance no
            // longer knows anything about kernel state. startInternal() arms the flag on the way
            // up; without arming it here too, an instance that stops and is then handed a rule
            // apply would diff against a chain that is gone.
            chainNeedsResync = true

            // Ask the kernel instead of trusting the commands we just ran. Every teardown command
            // ends in "|| true" and none of them has its exit code inspected, which is deliberate -
            // deleting a chain that is already gone is not an error. The cost of that is that the
            // commands can ALL fail (revoked root, xtables lock, permission denied) and still look
            // fine, so this method used to return success over chains that were still dropping
            // traffic. FirewallManager turns that success into "firewall stopped", clears the
            // warning banner and shows OFF. The only honest way to end a teardown is to look.
            when (probeChains()) {
                TeardownProof.CLEAN -> {
                    setChainsInstalled(false)
                    AppLogger.d(TAG, "iptables chains deleted and verified gone")
                    Result.success(Unit)
                }
                TeardownProof.RESIDUE -> {
                    AppLogger.e(TAG, "iptables teardown FAILED - chain $CHAIN_OUTPUT is still installed")
                    Result.failure(
                        errorHandler.handleError(
                            IllegalStateException("iptables chain $CHAIN_OUTPUT is still installed"),
                            "delete iptables chains"
                        )
                    )
                }
                TeardownProof.UNVERIFIABLE -> {
                    // We could not run the probe at all - no root, no Shizuku. That is only a
                    // failure if there was something to remove. A device that never created the
                    // chains has nothing to lose, and reporting a failed stop there would fire the
                    // warning on every stop of every other backend, because the sweep runs this
                    // cleanup unconditionally.
                    if (chainsWereInstalled) {
                        AppLogger.e(TAG, "iptables teardown UNVERIFIED and chains were installed - assuming rules are still live")
                        Result.failure(
                            errorHandler.handleError(
                                IllegalStateException("cannot verify iptables teardown - no root or Shizuku access"),
                                "delete iptables chains"
                            )
                        )
                    } else {
                        AppLogger.d(TAG, "iptables teardown unverified, but no chains were ever installed - nothing to undo")
                        Result.success(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete iptables chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }

    private enum class TeardownProof {
        CLEAN,

        RESIDUE,

        /** The probe itself could not run, so nothing is proven either way. */
        UNVERIFIABLE
    }

    private suspend fun probeChains(): TeardownProof {
        val v4 = probeChain(IPTABLES)
        val v6 = probeChain(IP6TABLES)

        // A family that will not answer while the OTHER family answered is not a privilege problem.
        // Privilege belongs to the shell, not to an address family: the same root or Shizuku-root
        // shell runs both binaries. So if iptables answered and ip6tables did not, ip6tables is
        // unusable on this device - no ip6_tables module, no binary, no IPv6 filter table - and an
        // unusable family cannot be holding our chains either. That is CLEAN for it, not "could not
        // look".
        //
        // Reading it as "could not look" was a real defect: checkAvailability only ever tests
        // "iptables --version" and createCustomChains inspects no exit codes, so a v4-only device
        // starts fine and sets the chains-installed flag. Every stop then collapsed a proven-clean
        // v4 into UNVERIFIABLE, which with the flag set is a hard failure - and the flag is cleared
        // only on the CLEAN path, so the STUCK badge, banner and notification latched forever on a
        // firewall that was genuinely off, with no way out inside the app.
        val v4Answered = v4 != TeardownProof.UNVERIFIABLE
        val v6Answered = v6 != TeardownProof.UNVERIFIABLE

        return when {
            v4 == TeardownProof.RESIDUE || v6 == TeardownProof.RESIDUE -> TeardownProof.RESIDUE

            // Neither family answered: the shell itself could not look. Genuinely unverifiable.
            !v4Answered && !v6Answered -> TeardownProof.UNVERIFIABLE

            // At least one answered and nothing was found. Any silent family is unusable, so it
            // holds nothing.
            else -> {
                if (!v4Answered || !v6Answered) {
                    val silent = if (v4Answered) IP6TABLES else IPTABLES
                    AppLogger.d(TAG, "$silent is unusable on this device - treating it as holding no chains")
                }
                TeardownProof.CLEAN
            }
        }
    }

    /**
     * Probe one address family.
     *
     * The probe echoes its own token rather than letting the caller read an exit code or an error
     * message, because neither survives the trip reliably. RootManager returns libsu's result.out,
     * which is stdout only - the shell is built without FLAG_REDIRECT_STDERR - so iptables' "No
     * chain/target/match by that name" never arrives on the root path, while ShizukuManager does
     * return stderr. Matching on that text would have read every clean teardown on a rooted device
     * as unverifiable, and with chains installed that is a "firewall would not stop" warning on
     * every single successful stop. Tokens are the same on both paths and in every locale.
     *
     * The outer "-S" with no chain name is the privilege test: listing the whole filter table needs
     * exactly the access that listing one chain needs, so a failure there means we could not look,
     * not that the chain is gone. Without it, a permission-denied probe is indistinguishable from a
     * clean one - which is the false "all clear" this whole method exists to prevent.
     */
    private suspend fun probeChain(binary: String): TeardownProof {
        val probe = "if $binary -S >/dev/null 2>&1; then " +
            "if $binary -S $CHAIN_OUTPUT >/dev/null 2>&1; then echo $PROBE_PRESENT; else echo $PROBE_ABSENT; fi; " +
            "else echo $PROBE_NOPRIV; fi"

        val (exitCode, output) = executeCommand(probe)
        return when {
            output.contains(PROBE_PRESENT) -> TeardownProof.RESIDUE
            output.contains(PROBE_ABSENT) -> TeardownProof.CLEAN
            else -> {
                // PROBE_NOPRIV, or no token at all: no root and no Shizuku, so executeCommand
                // returned Pair(-1, ...) without running anything.
                AppLogger.w(TAG, "Chain probe could not answer for $binary (exit=$exitCode): $output")
                TeardownProof.UNVERIFIABLE
            }
        }
    }

    private fun wereChainsInstalled(): Boolean =
        context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(Constants.Settings.KEY_IPTABLES_CHAINS_INSTALLED, false)

    private fun setChainsInstalled(installed: Boolean) {
        context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.Settings.KEY_IPTABLES_CHAINS_INSTALLED, installed)
            .commit()
    }
    
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = mutex.withLock {
        val startTime = System.currentTimeMillis()
        return try {
            AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules START: ${rules.size} rules, network=$networkType, screenOn=$screenOn")


            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            val uidsToBlock = mutableSetOf<Int>()

            // Apps can share a uid (sharedUserId) and the chain matches the uid: one verdict per uid.
            val rulesByUid = rulesByCurrentUid(rules, io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledUids(context))

            if (isBlockAllDefault) {
                val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
                val allPackages = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
                    .getPackagesWithNetworkPermissions(context)

                AppLogger.d(TAG, "Block All mode: found ${allPackages.size} packages with network permissions across ${userProfiles.size} profiles")

                // A failed read makes a start report down instead of healthy over an unwritten chain; a running chain keeps its last write.
                if (allPackages.isEmpty()) {
                    AppLogger.e(TAG, "Block All: network package read failed - refusing to apply, the chain keeps its last write")
                    return Result.failure(Exception("Network package read failed"))
                }

                val allowCritical = prefs.getBoolean(
                    Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                    Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
                )

                // Computed once for the whole pass. We block by UID, not by package, so if ANY
                // package in a UID is critical or a VPN app the whole UID has to be handled as one.
                //
                // The two sets below are complements: with "allow critical" ON these uids get no
                // rule of their own but are allowed by default, with it OFF they are exempt from
                // being written at all. Same membership, opposite use, so one computation serves
                // both - it used to be worked out twice, one of them per package.
                val criticalOrVpnUids = uidsWithCriticalOrVpn(allPackages)
                val uidsWithCritical = if (allowCritical) criticalOrVpnUids else emptySet()
                val exemptUids = if (allowCritical) emptySet() else criticalOrVpnUids

                for (appInfo in allPackages) {
                    val uid = appInfo.uid

                    // Never block UIDs that contain system-critical packages or VPN apps
                    // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                    if (isUidExempted(uid, exemptUids)) {
                        continue
                    }

                    val shouldBlock = uidBlockedNow(
                        rulesByUid[uid], isBlockAllDefault, protectedUid = uid in uidsWithCritical,
                        perNetwork = true, networkType = networkType, screenOn = screenOn,
                    )
                    AppLogger.d(TAG, "  ${appInfo.packageName} (UID $uid): ${rulesByUid[uid]?.size ?: 0} rule(s), shouldBlock=$shouldBlock")

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            } else {

                val userProfilesForAllowAll = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
                val allPackages = userProfilesForAllowAll.flatMap { profile ->
                    io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                        context, PackageManager.GET_META_DATA, profile.userId
                    )
                }

                val exemptUids = if (allowCriticalEnabled()) {
                    emptySet()
                } else {
                    uidsWithCriticalOrVpn(allPackages)
                }

                for ((uid, rulesForUid) in rulesByUid) {
                    // Never block UIDs that contain system-critical packages or VPN apps
                    // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                    if (isUidExempted(uid, exemptUids)) {
                        continue
                    }

                    val shouldBlock = uidBlockedNow(
                        rulesForUid, blockAllDefault = false, protectedUid = false,
                        perNetwork = true, networkType = networkType, screenOn = screenOn,
                    )

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            }

            // Diffing against blockedUids assumes the chain holds exactly what this instance put
            // there. On a first apply it does not - see chainNeedsResync - so every rule the chain
            // already had was appended a second time, roughly two copies per start.
            //
            // That is not untidiness. Unblocking emits ONE `-D` per uid and `-D` removes ONE
            // matching rule, so the surplus copies outlived the unblock: the app kept losing every
            // packet while the UI reported it allowed. Rewrite the chain instead of diffing it.
            if (chainNeedsResync) {
                val lanUidsForResync = computeLanUidsToBlock(rulesByUid)

                // A rewrite driven by an empty answer would delete every rule in the chain. Both
                // callers read the rule list with getAllRules().first(), a single Room emission,
                // and an empty one is indistinguishable here from "the user blocks nothing". Wiping
                // a populated chain on that reading is fail-open; keeping stale rules until a real
                // answer arrives is fail-closed, which is the correct direction for a firewall.
                // The flag stays armed, so the next apply with actual rules does the rewrite.
                if (rules.isEmpty() && uidsToBlock.isEmpty() && lanUidsForResync.isEmpty()) {
                    AppLogger.w(TAG, "Chain resync skipped: rule list is empty, refusing to clear the chain on that basis")
                    return Result.success(Unit)
                }
                AppLogger.d(TAG, "🔥 [TIMING] Chain resync: ${uidsToBlock.size} internet + ${lanUidsForResync.size} LAN UIDs (append new, then trim old)")
                resyncChain(uidsToBlock, lanUidsForResync).getOrElse { error ->
                    AppLogger.e(TAG, "Chain resync failed, leaving it armed for the next apply: ${error.message}")
                    return Result.failure(error)
                }
                AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules COMPLETE (resync): total=${System.currentTimeMillis() - startTime}ms")
                AppLogger.d(TAG, "🔥 [TIMING] Final state: ${blockedUids.size} apps blocked (Internet), ${blockedLanUids.size} apps blocked (LAN)")
                return Result.success(Unit)
            }

            val uidsToAdd = uidsToBlock - blockedUids
            val uidsToRemove = blockedUids - uidsToBlock

            AppLogger.d(TAG, "🔥 [TIMING] Rule diff calculated: +${System.currentTimeMillis() - startTime}ms")
            AppLogger.d(TAG, "🔥 [TIMING] Rule diff: add=${uidsToAdd.size} UIDs, remove=${uidsToRemove.size} UIDs, keep=${blockedUids.intersect(uidsToBlock).size} UIDs")

            if (uidsToAdd.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] UIDs to ADD (block): $uidsToAdd")
            }
            if (uidsToRemove.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] UIDs to REMOVE (unblock): $uidsToRemove")
            }

            val ruleStartTime = System.currentTimeMillis()
            if (uidsToRemove.isNotEmpty() || uidsToAdd.isNotEmpty()) {
                applyRulesBatch(uidsToAdd, uidsToRemove).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to apply batched rules: ${error.message}")
                }
                AppLogger.d(TAG, "🔥 [TIMING] Batched rules (unblock=${uidsToRemove.size}, block=${uidsToAdd.size}) took ${System.currentTimeMillis() - ruleStartTime}ms")
            }


            val uidsToBlockLan = computeLanUidsToBlock(rulesByUid)

            val uidsToAddLan = uidsToBlockLan - blockedLanUids
            val uidsToRemoveLan = blockedLanUids - uidsToBlockLan

            AppLogger.d(TAG, "LAN blocking diff: add=${uidsToAddLan.size}, remove=${uidsToRemoveLan.size}, keep=${blockedLanUids.intersect(uidsToBlockLan).size}")

            val lanStartTime = System.currentTimeMillis()
            if (uidsToRemoveLan.isNotEmpty() || uidsToAddLan.isNotEmpty()) {
                applyLanRulesBatch(uidsToAddLan, uidsToRemoveLan).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to apply batched LAN rules: ${error.message}")
                }
                AppLogger.d(TAG, "🔥 [TIMING] Batched LAN rules (unblock=${uidsToRemoveLan.size}, block=${uidsToAddLan.size}) took ${System.currentTimeMillis() - lanStartTime}ms")
            }

            AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules COMPLETE: total=${System.currentTimeMillis() - startTime}ms")
            AppLogger.d(TAG, "🔥 [TIMING] Final state: ${blockedUids.size} apps blocked (Internet), ${blockedLanUids.size} apps blocked (LAN)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply rules", e)
            val error = errorHandler.handleError(e, "apply iptables rules")
            Result.failure(error)
        }
    }
    
    override fun isActive(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            if (!isServiceRunning || backendType != "IPTABLES") {
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
            AppLogger.e(TAG, "Failed to check if iptables firewall is active", e)
            false
        }
    }
    
    override fun getType(): FirewallBackendType = FirewallBackendType.IPTABLES
    
    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            val hasRoot = rootManager.hasRootPermission
            val hasShizuku = shizukuManager.hasShizukuPermission
            val hasAccess = hasRoot || hasShizuku

            if (!hasAccess) {
                val error = errorHandler.createRootRequiredError("iptables firewall")
                return Result.failure(error)
            }

            if (hasShizuku && !hasRoot) {
                val isRootMode = shizukuManager.isShizukuRootMode()

                if (!isRootMode) {
                    val error = errorHandler.createUnsupportedDeviceError(
                        operation = "iptables firewall",
                        reason = "Shizuku must be started with ROOT privileges (not ADB) to use iptables firewall"
                    )
                    return Result.failure(error)
                }
            }

            val (exitCode, _) = executeCommand("$IPTABLES --version")

            if (exitCode != 0) {
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "iptables firewall",
                    reason = "iptables not available on this device"
                )
                return Result.failure(error)
            }

            Result.success(Unit)
        } catch (e: java.util.concurrent.CancellationException) {
            AppLogger.d(TAG, "checkAvailability cancelled")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "checkAvailability cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check iptables availability", e)
            val error = errorHandler.handleError(e, "check iptables availability")
            Result.failure(error)
        }
    }
    
    /**
     * Create custom chains for rule isolation.
     * Only creates OUTPUT chain since owner module only works for OUTPUT.
     */
    private suspend fun createCustomChains(): Result<Unit> {
        return try {
            executeCommand("$IPTABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand("$IPTABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IPTABLES -I OUTPUT -j $CHAIN_OUTPUT")

            executeCommand("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand("$IP6TABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IP6TABLES -I OUTPUT -j $CHAIN_OUTPUT")

            AppLogger.d(TAG, "Custom chains created successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create custom chains", e)
            val error = errorHandler.handleError(e, "create iptables chains")
            Result.failure(error)
        }
    }
    
    private suspend fun deleteCustomChains(): Result<Unit> {
        return try {
            executeCommand("$IPTABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand("$IP6TABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            AppLogger.d(TAG, "Custom chains deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete custom chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }
    
    /**
     * Block an app by UID.
     * Only blocks OUTPUT since owner module only works for locally generated packets.
     *
     * CRITICAL: This runs in NonCancellable context to prevent iptables commands from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * An interrupted iptables command could leave the firewall in an inconsistent state.
     */
    private suspend fun blockApp(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            AppLogger.d(TAG, "=== Blocking UID $uid ===")

            val ipv4Command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            AppLogger.d(TAG, "Executing IPv4 command: $ipv4Command")
            val (ipv4ExitCode, ipv4Output) = executeCommand(ipv4Command)
            AppLogger.d(TAG, "IPv4 result: exitCode=$ipv4ExitCode, output='$ipv4Output'")

            val ipv6Command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            AppLogger.d(TAG, "Executing IPv6 command: $ipv6Command")
            val (ipv6ExitCode, ipv6Output) = executeCommand(ipv6Command)
            AppLogger.d(TAG, "IPv6 result: exitCode=$ipv6ExitCode, output='$ipv6Output'")

            if (ipv4ExitCode == 0 && ipv6ExitCode == 0) {
                blockedUids.add(uid)
                AppLogger.d(TAG, "✅ Successfully blocked UID $uid (IPv4 and IPv6)")
            } else {
                AppLogger.e(TAG, "❌ Failed to block UID $uid - IPv4 exitCode=$ipv4ExitCode, IPv6 exitCode=$ipv6ExitCode")
                return@withContext Result.failure(Exception("Failed to block UID $uid"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to block UID $uid", e)
            val error = errorHandler.handleError(e, "block app UID $uid")
            Result.failure(error)
        }
    }
    
    private suspend fun blockAppLan(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            AppLogger.d(TAG, "=== Blocking LAN for UID $uid ===")

            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            for (range in ipv4Ranges) {
                val command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP"
                AppLogger.d(TAG, "Executing IPv4 LAN command: $command")
                val (exitCode, output) = executeCommand(command)
                AppLogger.d(TAG, "IPv4 LAN result: exitCode=$exitCode, output='$output'")
                if (exitCode != 0) {
                    AppLogger.e(TAG, "❌ Failed to block LAN IPv4 range $range for UID $uid")
                    return@withContext Result.failure(Exception("Failed to block LAN IPv4 for UID $uid"))
                }
            }

            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")
            for (range in ipv6Ranges) {
                val command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP"
                AppLogger.d(TAG, "Executing IPv6 LAN command: $command")
                val (exitCode, output) = executeCommand(command)
                AppLogger.d(TAG, "IPv6 LAN result: exitCode=$exitCode, output='$output'")
                if (exitCode != 0) {
                    AppLogger.e(TAG, "❌ Failed to block LAN IPv6 range $range for UID $uid")
                    return@withContext Result.failure(Exception("Failed to block LAN IPv6 for UID $uid"))
                }
            }

            blockedLanUids.add(uid)
            AppLogger.d(TAG, "✅ Successfully blocked LAN for UID $uid (IPv4 and IPv6)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to block LAN for UID $uid", e)
            val error = errorHandler.handleError(e, "block LAN for app UID $uid")
            Result.failure(error)
        }
    }

    private suspend fun unblockApp(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            blockedUids.remove(uid)
            AppLogger.d(TAG, "Unblocked UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unblock UID $uid", e)
            val error = errorHandler.handleError(e, "unblock app UID $uid")
            Result.failure(error)
        }
    }

    private suspend fun unblockAppLan(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            for (range in ipv4Ranges) {
                executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
            }

            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")
            for (range in ipv6Ranges) {
                executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
            }

            blockedLanUids.remove(uid)
            AppLogger.d(TAG, "Unblocked LAN for UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unblock LAN for UID $uid", e)
            val error = errorHandler.handleError(e, "unblock LAN for app UID $uid")
            Result.failure(error)
        }
    }

    private suspend fun clearAllRules(): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")

            blockedUids.clear()
            blockedLanUids.clear()
            AppLogger.d(TAG, "All rules cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear rules", e)
            val error = errorHandler.handleError(e, "clear iptables rules")
            Result.failure(error)
        }
    }
    
    /**
     * Which UIDs should have their LAN traffic dropped.
     *
     * Pulled out of applyRules because the resync path needs the answer BEFORE it touches the
     * chain, and the diff path needs it after. Two copies of this loop would be two chances to
     * disagree about who gets blocked.
     */
    private fun computeLanUidsToBlock(rulesByUid: Map<Int, List<FirewallRule>>): Set<Int> {
        val userProfilesForLan = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
        val allPackagesForLan = userProfilesForLan.flatMap { profile ->
            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                context, PackageManager.GET_META_DATA, profile.userId
            )
        }

        val uidsToBlockLan = mutableSetOf<Int>()

        val exemptUids = if (allowCriticalEnabled()) {
            emptySet()
        } else {
            uidsWithCriticalOrVpn(allPackagesForLan)
        }

        for ((uid, rulesForUid) in rulesByUid) {
            if (isUidExempted(uid, exemptUids)) {
                continue
            }

            if (rulesForUid.any { rule -> rule.lanBlocked }) {
                uidsToBlockLan.add(uid)
            }
        }

        return uidsToBlockLan
    }

    /**
     * Rewrite the chain to exactly `uidsToBlock` + `uidsToBlockLan`, in ONE script, WITHOUT ever
     * leaving it emptier than it already is.
     *
     * The obvious shape - flush, then re-add - is wrong twice over. It drops protection for the
     * entire refill: an iptables exec costs ~18ms on real hardware, so 200 blocked apps spend
     * ~7 seconds with the chain empty and still linked into OUTPUT. And if the script dies partway
     * the chain STAYS empty while FirewallManager goes on reporting Running.
     *
     * So: append the new rules first, then delete the old ones BY POSITION - rule 1, N times, N
     * counted before anything was added. At every instant the chain holds the old set, or the old
     * set plus the new one; never less than it started with. A failure before the trim leaves the
     * previous rules untouched, which is the safe direction for a firewall.
     *
     * Nothing here trusts the exit code. A shell reports the status of its LAST line, so a rule
     * rejected in the middle - xtables lock contention while netd rewrites during a network change,
     * for instance - hides behind a successful final line. Verified on device: a script whose
     * second of three -A commands failed still exited 0. Every command that matters prints a
     * marker instead.
     *
     * The chain is created and linked here too, idempotently. Another instance's teardown sweep can
     * delete it between startInternal() and the first apply, and without this every -A would fail.
     *
     * IPv6 add failures warn rather than fail: ROMs lacking the IPv6 owner match predate this
     * change, and the old diff path degraded to working v4 rules instead of refusing to start.
     */
    private suspend fun resyncChain(
        uidsToBlock: Set<Int>,
        uidsToBlockLan: Set<Int>
    ): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            // Kotlin would swallow a bare $ as a template; this keeps the shell's own variables
            // readable in the lines below.
            val sh = "${'$'}"
            val script = StringBuilder()

            script.appendLine("$IPTABLES -N $CHAIN_OUTPUT 2>/dev/null || true")
            script.appendLine("$IPTABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IPTABLES -I OUTPUT -j $CHAIN_OUTPUT || echo $ADD_FAIL_V4")
            script.appendLine("V4OLD=$sh($IPTABLES -S $CHAIN_OUTPUT 2>/dev/null | grep -c '^-A $CHAIN_OUTPUT')")

            script.appendLine("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")
            script.appendLine("$IP6TABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IP6TABLES -I OUTPUT -j $CHAIN_OUTPUT || echo $ADD_FAIL_V6")
            script.appendLine("V6OLD=$sh($IP6TABLES -S $CHAIN_OUTPUT 2>/dev/null | grep -c '^-A $CHAIN_OUTPUT')")

            for (uid in uidsToBlock) {
                script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP || echo $ADD_FAIL_V4")
                script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP || echo $ADD_FAIL_V6")
            }

            for (uid in uidsToBlockLan) {
                for (range in LAN_RANGES_V4) {
                    script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP || echo $ADD_FAIL_V4")
                }
                for (range in LAN_RANGES_V6) {
                    script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP || echo $ADD_FAIL_V6")
                }
            }

            // Only now is the old set redundant. Deleting by position rather than by rule text is
            // what makes duplicates go away: -D <spec> removes ONE match and would leave the rest.
            script.appendLine("i=0; while [ \"${sh}i\" -lt \"${sh}V4OLD\" ]; do $IPTABLES -D $CHAIN_OUTPUT 1 2>/dev/null || echo $TRIM_FAIL; i=$sh((i+1)); done")
            script.appendLine("i=0; while [ \"${sh}i\" -lt \"${sh}V6OLD\" ]; do $IP6TABLES -D $CHAIN_OUTPUT 1 2>/dev/null || echo $TRIM_FAIL; i=$sh((i+1)); done")

            val (exitCode, output) = executeCommand(script.toString())
            val failedV4 = output.split(ADD_FAIL_V4).size - 1
            val failedV6 = output.split(ADD_FAIL_V6).size - 1
            val failedTrim = output.split(TRIM_FAIL).size - 1

            if (exitCode != 0 || failedV4 > 0) {
                // Say so honestly and stay armed; the next apply retries the whole rewrite.
                //
                // What the chain actually holds here depends on how far the script got, and since
                // ShellRunner gained a ceiling that can fire there are TWO shapes, not one:
                //
                //  - CUT SHORT. The ceiling fired and the shell was destroyed part way through.
                //    The trim is the last thing in the script, so the chain holds the old set plus
                //    whatever new rules were added - over-blocking, never a hole. This is the shape
                //    the old "a broken script never reaches the trim" note described, and until
                //    ShellRunner's timeout became reachable it was the only one.
                //
                //  - RAN TO THE END with a rejected -A. There is no `set -e`, so the trim runs
                //    anyway: the old rules ARE removed and a uid whose rule was rejected is left
                //    with none until the next apply. Armed-and-retry is what closes that.
                //
                // The first shape is why ShellRunner.ceilingFor scales with the script instead of
                // using one flat number: a cut rewrite leaves MORE rules for the next attempt to
                // delete than this one had, so a ceiling that fires once tends to fire again on a
                // bigger chain, and the rewrite stops converging.
                AppLogger.e(TAG, "Chain resync failed: exitCode=$exitCode, v4Failures=$failedV4, v6Failures=$failedV6, output=$output")
                return@withContext Result.failure(
                    errorHandler.handleError(
                        IllegalStateException(
                            "iptables resync failed: exitCode=$exitCode, $failedV4 IPv4 rule(s) rejected: $output"
                        ),
                        "resync iptables chain"
                    )
                )
            }

            if (failedV6 > 0) {
                AppLogger.w(TAG, "⚠️ $failedV6 IPv6 rule(s) rejected - IPv6 traffic for those apps is NOT blocked (device may lack the IPv6 owner match)")
            }
            if (failedTrim > 0) {
                AppLogger.w(TAG, "⚠️ $failedTrim stale rule(s) could not be removed - staying armed so the next apply retries")
            }

            blockedUids.clear()
            blockedUids.addAll(uidsToBlock)
            blockedLanUids.clear()
            blockedLanUids.addAll(uidsToBlockLan)
            // Duplicates may survive a failed trim, and duplicates are exactly what breaks
            // unblocking, so an incomplete trim keeps the rewrite armed for next time.
            chainNeedsResync = failedTrim > 0

            AppLogger.d(TAG, "✅ Chain resynced: ${uidsToBlock.size} internet, ${uidsToBlockLan.size} LAN, stillArmed=$chainNeedsResync")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to resync chain", e)
            val error = errorHandler.handleError(e, "resync iptables chain")
            Result.failure(error)
        }
    }

    /**
     * Count, per uid, how many rules of one kind the chain actually holds.
     *
     * `lanRules = false` counts the blanket DROPs (no `-d`), `true` counts the LAN-range DROPs.
     * Counts rather than a set, because both failure directions matter: fewer than expected means
     * a rule never landed, more means a duplicate - and duplicates are what break unblocking,
     * since `-D` removes exactly one match.
     */
    private fun parseChainUids(dump: String, lanRules: Boolean): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        for (line in dump.lineSequence()) {
            if (!line.startsWith("-A $CHAIN_OUTPUT")) continue
            if (line.contains(" -d ") != lanRules) continue
            val uid = UID_OWNER_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            counts[uid] = (counts[uid] ?: 0) + 1
        }
        return counts
    }

    /**
     * Append a dump of both chains so the caller can see what the writes above actually did.
     */
    private fun StringBuilder.appendChainReadback() {
        appendLine("echo $STATE_V4")
        appendLine("$IPTABLES -S $CHAIN_OUTPUT 2>/dev/null || true")
        appendLine("echo $STATE_V6")
        appendLine("$IP6TABLES -S $CHAIN_OUTPUT 2>/dev/null || true")
    }

    /**
     * Add and remove blanket DROP rules, then BELIEVE THE KERNEL, not the commands.
     *
     * This used to log a warning on a non-zero exit and carry on, recording every uid it was asked
     * to block as blocked. Two things made that a lie. A shell reports only its last line's status,
     * so a rule rejected mid-script - xtables lock contention while netd rewrites during a network
     * change - was invisible. And once a uid sat in `blockedUids`, the diff path never emitted its
     * rule again: `uidsToAdd = uidsToBlock - blockedUids` excluded it forever. The app showed the
     * app as blocked and it had open network for the rest of the session.
     *
     * So the script now ends by dumping the chain, and `blockedUids` is set to what is really
     * there. Anything that does not match what was asked for arms a full rewrite, which is safe
     * and duplicate-free (see resyncChain). IPv6 failures only warn - ROMs without the IPv6 owner
     * match must keep working on v4, as they did before.
     */
    private suspend fun applyRulesBatch(
        uidsToBlock: Set<Int>,
        uidsToUnblock: Set<Int>
    ): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            val script = StringBuilder()

            for (uid in uidsToUnblock) {
                script.appendLine("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")
                script.appendLine("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")
            }

            for (uid in uidsToBlock) {
                script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP || echo $ADD_FAIL_V4")
                script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP || echo $ADD_FAIL_V6")
            }

            if (script.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            script.appendChainReadback()

            val (exitCode, output) = executeCommand(script.toString())
            val failedV6 = output.split(ADD_FAIL_V6).size - 1

            val actualV4 = parseChainUids(output.substringAfter(STATE_V4, "").substringBefore(STATE_V6), lanRules = false)
            val actualV6 = parseChainUids(output.substringAfter(STATE_V6, ""), lanRules = false)

            blockedUids.clear()
            blockedUids.addAll(actualV4.keys)

            val missing = uidsToBlock - actualV4.keys
            val notRemoved = uidsToUnblock.intersect(actualV4.keys)
            val duplicated = actualV4.filterValues { it > 1 }.keys
            // A leftover IPv6 rule for a uid we just unblocked still drops that app's traffic, so
            // it counts as a mismatch. A MISSING IPv6 rule does not - that is the unsupported-ROM
            // case, and failing there would break devices the old code served.
            val v6NotRemoved = uidsToUnblock.intersect(actualV6.keys)

            if (missing.isNotEmpty() || notRemoved.isNotEmpty() || duplicated.isNotEmpty() || v6NotRemoved.isNotEmpty()) {
                AppLogger.w(
                    TAG,
                    "⚠️ Chain does not match intent - missing=$missing, stillBlockedV4=$notRemoved, " +
                        "stillBlockedV6=$v6NotRemoved, duplicated=$duplicated; arming a full rewrite"
                )
                chainNeedsResync = true
            }
            if (failedV6 > 0) {
                AppLogger.w(TAG, "⚠️ $failedV6 IPv6 rule(s) rejected - IPv6 traffic for those apps is NOT blocked")
            }
            if (exitCode != 0) {
                AppLogger.w(TAG, "Batch rule script returned non-zero: exitCode=$exitCode")
            }

            AppLogger.d(TAG, "✅ Batched rules applied: asked=${uidsToBlock.size} blocked/${uidsToUnblock.size} unblocked, chain now holds ${actualV4.size}, armed=$chainNeedsResync")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply batched rules", e)
            val error = errorHandler.handleError(e, "apply batched iptables rules")
            Result.failure(error)
        }
    }

    /**
     * Same contract as applyRulesBatch, for the LAN-range rules.
     *
     * A uid counts as LAN-blocked only when ALL its ranges are present; a partial set means some
     * private network is still reachable, so it arms a rewrite rather than being recorded as done.
     */
    private suspend fun applyLanRulesBatch(
        uidsToBlock: Set<Int>,
        uidsToUnblock: Set<Int>
    ): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            val script = StringBuilder()

            for (uid in uidsToUnblock) {
                for (range in LAN_RANGES_V4) {
                    script.appendLine("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
                }
                for (range in LAN_RANGES_V6) {
                    script.appendLine("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
                }
            }

            for (uid in uidsToBlock) {
                for (range in LAN_RANGES_V4) {
                    script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP || echo $ADD_FAIL_V4")
                }
                for (range in LAN_RANGES_V6) {
                    script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP || echo $ADD_FAIL_V6")
                }
            }

            if (script.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            script.appendChainReadback()

            val (exitCode, output) = executeCommand(script.toString())
            val failedV6 = output.split(ADD_FAIL_V6).size - 1

            val actualV4 = parseChainUids(output.substringAfter(STATE_V4, "").substringBefore(STATE_V6), lanRules = true)
            val actualV6 = parseChainUids(output.substringAfter(STATE_V6, ""), lanRules = true)

            val fullyBlocked = actualV4.filterValues { it >= LAN_RANGES_V4.size }.keys
            blockedLanUids.clear()
            blockedLanUids.addAll(fullyBlocked)

            val missing = uidsToBlock - fullyBlocked
            val notRemoved = uidsToUnblock.intersect(actualV4.keys)
            val duplicated = actualV4.filterValues { it > LAN_RANGES_V4.size }.keys
            val v6NotRemoved = uidsToUnblock.intersect(actualV6.keys)

            if (missing.isNotEmpty() || notRemoved.isNotEmpty() || duplicated.isNotEmpty() || v6NotRemoved.isNotEmpty()) {
                AppLogger.w(
                    TAG,
                    "⚠️ LAN rules do not match intent - missing=$missing, stillBlockedV4=$notRemoved, " +
                        "stillBlockedV6=$v6NotRemoved, duplicated=$duplicated; arming a full rewrite"
                )
                chainNeedsResync = true
            }
            if (failedV6 > 0) {
                AppLogger.w(TAG, "⚠️ $failedV6 IPv6 LAN rule(s) rejected - IPv6 LAN traffic for those apps is NOT blocked")
            }
            if (exitCode != 0) {
                AppLogger.w(TAG, "Batch LAN rule script returned non-zero: exitCode=$exitCode")
            }

            AppLogger.d(TAG, "✅ Batched LAN rules applied: asked=${uidsToBlock.size} blocked/${uidsToUnblock.size} unblocked, chain now holds ${fullyBlocked.size}, armed=$chainNeedsResync")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply batched LAN rules", e)
            val error = errorHandler.handleError(e, "apply batched LAN iptables rules")
            Result.failure(error)
        }
    }

    private suspend fun executeCommand(command: String): Pair<Int, String> {
        return if (rootManager.hasRootPermission) {
            rootManager.executeRootCommand(command)
        } else if (shizukuManager.hasShizukuPermission) {
            shizukuManager.executeShellCommand(command)
        } else {
            Pair(-1, "No root or Shizuku access")
        }
    }

    override fun supportsGranularControl(): Boolean = true

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

    /**
     * Every uid holding at least one system-critical package or a VPN app, in ONE pass.
     *
     * This predicate used to exist twice, computed opposite ways round. The Block All path built it
     * as a set when "allow critical" was ON, while isUidExempted re-derived the same test per uid
     * when it was OFF - each call re-reading SharedPreferences and rescanning the whole package
     * list. Inside `for (appInfo in allPackages)` that is O(n squared): on a 466-package device,
     * ~217,000 comparisons and 466 preference reads for an answer that does not change during a
     * pass. Now computed once and read as a set.
     *
     * hasVpnService is the expensive half - one binder call per package - so the caller must hold
     * onto the result rather than recompute it.
     */
    private fun uidsWithCriticalOrVpn(
        allPackages: List<android.content.pm.ApplicationInfo>
    ): Set<Int> = allPackages
        .filter {
            Constants.Firewall.isSystemCritical(it.packageName) ||
                hasVpnService(it.packageName, it.uid / 100000)
        }
        .map { it.uid }
        .toSet()

    /** Reads the "allow critical" preference once, for a caller about to loop. */
    private fun allowCriticalEnabled(): Boolean = context
        .getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

    /**
     * @param exemptUids from [uidsWithCriticalOrVpn], already emptied by the caller when the user
     * has allowed critical and VPN packages to be blocked.
     */
    private fun isUidExempted(uid: Int, exemptUids: Set<Int>): Boolean {
        // A uid we could not resolve. Unlike the Shizuku backends, iptables deliberately has no
        // app-uid range guard - it can and should block system uids - but the sentinel is not a uid
        // at all, and "--uid-owner -1" is a command that can only fail. See HiddenApiHelper.
        if (uid < 0) {
            AppLogger.d(TAG, "UID $uid is not a real uid - not writing a rule for it")
            return true
        }

        return exemptUids.contains(uid)
    }
}

