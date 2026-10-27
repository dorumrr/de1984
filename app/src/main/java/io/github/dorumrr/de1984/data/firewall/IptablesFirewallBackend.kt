package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptablesFirewallBackend(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    private val _binaryUsable = MutableStateFlow<Boolean?>(null)

    /** The last `iptables --version` that ran; null until one has. A run that did not finish says nothing. */
    val binaryUsable: StateFlow<Boolean?> = _binaryUsable.asStateFlow()
    
    companion object {
        private const val TAG = "IptablesFirewall"

        // Custom chain name to avoid conflicts with Android netd
        // Note: We only use OUTPUT chain because the owner module only works for OUTPUT
        // (locally generated packets). INPUT chain cannot match by UID.
        private val CHAIN_OUTPUT = "${Constants.App.ROOT_NAME_PREFIX}_output"

        // Without a wait, a write fails at once (exit 4) while netd holds the xtables lock, which it
        // does during network changes. Bounded, so a long hold fails into the repair pass instead.
        private const val LOCK_WAIT_SECONDS = 2
        private const val LOCK_WAIT_RC = "DE1984_LOCKWAIT_RC="

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
        private const val LINK_FAIL = "link"
        // Appended to a failure marker: iptables' exit code, so a lock timeout (4) is told apart from an unsupported match.
        private const val RC = "${'$'}?"
        private const val RC_LOCK_BUSY = 4

        // Separators for the chain dump appended to every write script. Reading the chain back is
        // the only way to know what actually landed: iptables writes can fail individually and the
        // exit code carries only the last one.
        private const val STATE_V4 = "DE1984_STATE4"
        private const val STATE_V6 = "DE1984_STATE6"

        private const val LINKED_V4 = "DE1984_LINKED4"
        private const val LINKED_V6 = "DE1984_LINKED6"
        // iptables exit 4: the lock wait ran out, so a read proves nothing about the chain.
        private const val READ_BUSY = "DE1984_READ_BUSY"

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

    // What the last apply asked the chain to hold; null until one has. The health check compares
    // against this, never against blockedUids, which is filled from the chain itself.
    private var intendedUids: Set<Int>? = null
    private var intendedLanUids: Set<Int>? = null

    // A ROM without the IPv6 owner match never holds an IPv6 owner rule. Once one has been seen,
    // a missing IPv6 rule is a hole, not an unsupported device.
    @Volatile
    private var v6OwnerMatchSeen = false

    // " -w 2" where this iptables accepts it, probed at start; older builds reject it with exit 2.
    @Volatile
    private var lockWaitV4 = ""
    @Volatile
    private var lockWaitV6 = ""
    @Volatile
    private var lockWaitProbed = false
    private val IPTABLES: String get() = "iptables$lockWaitV4"
    private val IP6TABLES: String get() = "ip6tables$lockWaitV6"

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

            ensureLockWait()

            createCustomChains().getOrElse { error ->
                return Result.failure(error)
            }

            // iptables rules live in the kernel, not in this process, so they outlive a crash, a
            // force-stop and the app itself. This flag is the only thing that tells a later, fresh
            // instance that there is something out there to undo - and therefore whether an
            // unverifiable teardown is "nothing to do" or "we just failed to remove live rules".
            // commit(), not apply(): the chains exist now, so the record must exist now too.
            setChainsInstalled(true)

            intendedUids = null
            intendedLanUids = null
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
            // The cold-start sweep tears down before any start has probed.
            ensureLockWait()

            clearAllRules().getOrElse { error ->
                AppLogger.w(TAG, "Failed to clear rules during stop: ${error.message}")
            }

            deleteCustomChains().getOrElse { error ->
                AppLogger.w(TAG, "Failed to delete chains during stop: ${error.message}")
            }

            blockedUids.clear()
            blockedLanUids.clear()
            intendedUids = null
            intendedLanUids = null

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
                        context, io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.APP_LIST_FLAGS, profile.userId
                    ).filter(io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper::isInstalledForUser)
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
                intendedUids = uidsToBlock.toSet()
                intendedLanUids = lanUidsForResync
                AppLogger.d(TAG, "🔥 [TIMING] Chain resync: ${uidsToBlock.size} internet + ${lanUidsForResync.size} LAN UIDs (append new, then trim old)")
                resyncChain(uidsToBlock, lanUidsForResync).getOrElse { error ->
                    AppLogger.e(TAG, "Chain resync failed, leaving it armed for the next apply: ${error.message}")
                    return Result.failure(error)
                }
                AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules COMPLETE (resync): total=${System.currentTimeMillis() - startTime}ms")
                AppLogger.d(TAG, "🔥 [TIMING] Final state: ${blockedUids.size} apps blocked (Internet), ${blockedLanUids.size} apps blocked (LAN)")
                return Result.success(Unit)
            }

            val uidsToBlockLan = computeLanUidsToBlock(rulesByUid)
            intendedUids = uidsToBlock.toSet()
            intendedLanUids = uidsToBlockLan

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
            if (exitCode >= 0) _binaryUsable.value = exitCode == 0

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

            executeCommand(linkLine(IPTABLES, ADD_FAIL_V4))

            executeCommand("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand(linkLine(IP6TABLES, ADD_FAIL_V6))

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
            executeCommand(unlinkLine(IPTABLES))
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand(unlinkLine(IP6TABLES))
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
                context, io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.APP_LIST_FLAGS, profile.userId
            ).filter(io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper::isInstalledForUser)
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
     * Once an IPv6 owner rule has landed, though, a rejected one stays armed for a rewrite.
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
            script.appendLine(linkLine(IPTABLES, ADD_FAIL_V4))
            script.appendLine("V4OLD=$sh($IPTABLES -S $CHAIN_OUTPUT 2>/dev/null | grep -c '^-A $CHAIN_OUTPUT')")

            script.appendLine("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")
            script.appendLine(linkLine(IP6TABLES, ADD_FAIL_V6))
            script.appendLine("V6OLD=$sh($IP6TABLES -S $CHAIN_OUTPUT 2>/dev/null | grep -c '^-A $CHAIN_OUTPUT')")

            for (uid in uidsToBlock) {
                script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>&1 || echo $ADD_FAIL_V4 $uid $RC")
                script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>&1 || echo $ADD_FAIL_V6 $uid $RC")
            }

            for (uid in uidsToBlockLan) {
                for (range in LAN_RANGES_V4) {
                    script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>&1 || echo $ADD_FAIL_V4 $uid $RC")
                }
                for (range in LAN_RANGES_V6) {
                    script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>&1 || echo $ADD_FAIL_V6 $uid $RC")
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
            logRejected(output)

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

            // Only a script that ran to the end can show that some IPv6 owner rule was accepted.
            val rejectedV6Rules = rejectedUids(output, ADD_FAIL_V6).size
            if (rejectedV6Rules < uidsToBlock.size + uidsToBlockLan.size * LAN_RANGES_V6.size) {
                v6OwnerMatchSeen = true
            }
            if (failedV6 > 0 && !v6OwnerMatchSeen) {
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
            val v6Unlinked = output.contains("$ADD_FAIL_V6 $LINK_FAIL")
            chainNeedsResync = failedTrim > 0 || lockBusyRejections(output, ADD_FAIL_V6) > 0 ||
                ((rejectedV6Rules > 0 || v6Unlinked) && v6OwnerMatchSeen)

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

    private suspend fun ensureLockWait() {
        if (lockWaitProbed) return
        if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) return
        // A probe that never ran (null) is retried on the next start or stop.
        val v4 = probeLockWait("iptables") ?: return
        val v6 = probeLockWait("ip6tables") ?: return
        lockWaitV4 = v4
        lockWaitV6 = v6
        lockWaitProbed = true
        AppLogger.d(TAG, "xtables lock wait: iptables='${lockWaitV4.trim()}', ip6tables='${lockWaitV6.trim()}'")
    }

    private suspend fun probeLockWait(binary: String): String? {
        val (_, output) = executeCommand("$binary -w $LOCK_WAIT_SECONDS -S OUTPUT >/dev/null 2>&1; echo $LOCK_WAIT_RC${'$'}?")
        // 2 is "bad argument": this iptables has no "-w <seconds>". Any other code (no access) answers nothing.
        return when (output.substringAfter(LOCK_WAIT_RC, "").trim().toIntOrNull()) {
            0, RC_LOCK_BUSY -> " -w $LOCK_WAIT_SECONDS"
            2 -> ""
            else -> null
        }
    }

    // -C exits 1 only when the jump is absent; 4 is the lock wait running out, where "|| -I" would add a second jump.
    private fun linkLine(binary: String, failMarker: String): String {
        val sh = "${'$'}"
        return "$binary -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; r=$sh?; " +
            "if [ ${sh}r -eq 1 ]; then $binary -I OUTPUT -j $CHAIN_OUTPUT 2>&1 || echo $failMarker $LINK_FAIL $RC; " +
            "elif [ ${sh}r -ne 0 ]; then echo $failMarker $LINK_FAIL ${sh}r; fi"
    }

    // Every jump, not one: a duplicate left behind keeps -X from deleting the chain, and the stop reports it stuck.
    private fun unlinkLine(binary: String): String {
        val sh = "${'$'}"
        return "i=0; while [ ${sh}i -lt 10 ] && $binary -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; do i=$sh((i+1)); done"
    }

    // A lock timeout is transient whatever the ROM supports, so it must arm a rewrite even for IPv6.
    private fun lockBusyRejections(output: String, marker: String): Int =
        Regex("$marker \\S+ $RC_LOCK_BUSY\\b").findAll(output).count()

    private fun rejectedUids(output: String, marker: String): List<Int> =
        Regex("$marker (\\d+)").findAll(output).map { it.groupValues[1].toInt() }.toList()

    private fun logRejected(output: String) {
        val v4 = rejectedUids(output, ADD_FAIL_V4)
        val v6 = rejectedUids(output, ADD_FAIL_V6)
        val unlinked = listOf("IPv4" to ADD_FAIL_V4, "IPv6" to ADD_FAIL_V6)
            .filter { (_, marker) -> output.contains("$marker $LINK_FAIL") }.map { it.first }
        if (v4.isEmpty() && v6.isEmpty() && unlinked.isEmpty()) return
        // The -A lines send stderr to stdout, so iptables' own reason is in the output ahead of the markers.
        val reasons = output.substringBefore(STATE_V4).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(ADD_FAIL_V4) && !it.startsWith(ADD_FAIL_V6) && !it.startsWith(TRIM_FAIL) }
            .distinct()
            .take(5)
            .joinToString(" | ")
        AppLogger.w(TAG, "⚠️ iptables rejected rules - IPv4 UIDs=${v4.distinct()}, IPv6 UIDs=${v6.distinct()}, OUTPUT link failed=$unlinked, reason: ${reasons.ifEmpty { "none printed" }}")
    }

    /** True when the chain is known not to hold what the last apply asked for; the next apply rewrites it. */
    // Before an apply has stated what the chain should hold, an armed flag is "adopted, not yet
    // rewritten", not a known gap; an empty rule list keeps it so without anything being short.
    fun repairPending(): Boolean = chainNeedsResync && intendedUids != null

    /**
     * Health check: does the kernel chain still hold exactly what the last apply asked for, linked
     * into OUTPUT? A mismatch arms a rewrite and returns false. A read that proves nothing returns true.
     */
    suspend fun verifyChain(): Boolean = try {
        mutex.withLock { verifyChainLocked() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // The service's health loop stops the backend on an exception; a failed check proves nothing.
        AppLogger.w(TAG, "Chain check failed: ${e.message}")
        true
    }

    private suspend fun verifyChainLocked(): Boolean {
        val wantUids = intendedUids ?: return true
        val wantLan = intendedLanUids ?: return true
        if (chainNeedsResync) return false

        val sh = "${'$'}"
        val script = StringBuilder()
        script.appendLine("$IPTABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; r=$sh?; [ ${sh}r -eq 0 ] && echo $LINKED_V4; [ ${sh}r -ne 4 ] || echo $READ_BUSY")
        script.appendLine("$IP6TABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; r=$sh?; [ ${sh}r -eq 0 ] && echo $LINKED_V6; [ ${sh}r -ne 4 ] || echo $READ_BUSY")
        script.appendLine("echo $STATE_V4")
        script.appendLine("$IPTABLES -S $CHAIN_OUTPUT 2>/dev/null; [ $sh? -ne 4 ] || echo $READ_BUSY")
        script.appendLine("echo $STATE_V6")
        script.appendLine("$IP6TABLES -S $CHAIN_OUTPUT 2>/dev/null; [ $sh? -ne 4 ] || echo $READ_BUSY")

        val (_, output) = executeCommand(script.toString())
        if (!output.contains(STATE_V6) || output.contains(READ_BUSY)) {
            AppLogger.d(TAG, "Chain check could not read the chain - nothing proven")
            return true
        }

        val v4Dump = output.substringAfter(STATE_V4).substringBefore(STATE_V6)
        val v6Dump = output.substringAfter(STATE_V6)
        val v4 = parseChainUids(v4Dump, lanRules = false)
        val v4Lan = parseChainUids(v4Dump, lanRules = true)
        val v6 = parseChainUids(v6Dump, lanRules = false)
        val v6Lan = parseChainUids(v6Dump, lanRules = true)
        if (v6.isNotEmpty() || v6Lan.isNotEmpty()) v6OwnerMatchSeen = true

        val problems = mutableListOf<String>()
        if (!output.contains(LINKED_V4)) problems += "IPv4 chain not linked into OUTPUT"
        if (v4 != wantUids.associateWith { 1 }) {
            problems += "IPv4 rules: missing=${wantUids - v4.keys}, extra=${v4.keys - wantUids}, duplicated=${v4.filterValues { it > 1 }.keys}"
        }
        if (v4Lan != wantLan.associateWith { LAN_RANGES_V4.size }) {
            problems += "IPv4 LAN rules: missing=${wantLan - v4Lan.filterValues { it >= LAN_RANGES_V4.size }.keys}, extra=${v4Lan.keys - wantLan}"
        }
        if (v6OwnerMatchSeen) {
            if (!output.contains(LINKED_V6)) problems += "IPv6 chain not linked into OUTPUT"
            if (v6 != wantUids.associateWith { 1 }) {
                problems += "IPv6 rules: missing=${wantUids - v6.keys}, extra=${v6.keys - wantUids}, duplicated=${v6.filterValues { it > 1 }.keys}"
            }
            if (v6Lan != wantLan.associateWith { LAN_RANGES_V6.size }) {
                problems += "IPv6 LAN rules: missing=${wantLan - v6Lan.filterValues { it >= LAN_RANGES_V6.size }.keys}, extra=${v6Lan.keys - wantLan}"
            }
        }

        if (problems.isEmpty()) return true
        AppLogger.w(TAG, "⚠️ Chain check: the kernel chain does not hold the rules - ${problems.joinToString("; ")}; arming a full rewrite")
        chainNeedsResync = true
        return false
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
                script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>&1 || echo $ADD_FAIL_V4 $uid $RC")
                script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>&1 || echo $ADD_FAIL_V6 $uid $RC")
            }

            if (script.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            script.appendChainReadback()

            val (exitCode, output) = executeCommand(script.toString())
            logRejected(output)

            val actualV4 = parseChainUids(output.substringAfter(STATE_V4, "").substringBefore(STATE_V6), lanRules = false)
            val actualV6 = parseChainUids(output.substringAfter(STATE_V6, ""), lanRules = false)
            if (actualV6.isNotEmpty()) v6OwnerMatchSeen = true

            blockedUids.clear()
            blockedUids.addAll(actualV4.keys)

            val missing = uidsToBlock - actualV4.keys
            val notRemoved = uidsToUnblock.intersect(actualV4.keys)
            val duplicated = actualV4.filterValues { it > 1 }.keys
            // A leftover IPv6 rule for a uid we just unblocked still drops that app's traffic. A
            // missing one counts only once the device has shown it supports the IPv6 owner match.
            val v6NotRemoved = uidsToUnblock.intersect(actualV6.keys)
            val v6Missing = if (v6OwnerMatchSeen || lockBusyRejections(output, ADD_FAIL_V6) > 0) {
                uidsToBlock - actualV6.keys
            } else {
                emptySet()
            }

            if (missing.isNotEmpty() || notRemoved.isNotEmpty() || duplicated.isNotEmpty() ||
                v6NotRemoved.isNotEmpty() || v6Missing.isNotEmpty()
            ) {
                AppLogger.w(
                    TAG,
                    "⚠️ Chain does not match intent - missing=$missing, missingV6=$v6Missing, stillBlockedV4=$notRemoved, " +
                        "stillBlockedV6=$v6NotRemoved, duplicated=$duplicated; arming a full rewrite"
                )
                chainNeedsResync = true
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
                    script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>&1 || echo $ADD_FAIL_V4 $uid $RC")
                }
                for (range in LAN_RANGES_V6) {
                    script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>&1 || echo $ADD_FAIL_V6 $uid $RC")
                }
            }

            if (script.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            script.appendChainReadback()

            val (exitCode, output) = executeCommand(script.toString())
            logRejected(output)

            val actualV4 = parseChainUids(output.substringAfter(STATE_V4, "").substringBefore(STATE_V6), lanRules = true)
            val actualV6 = parseChainUids(output.substringAfter(STATE_V6, ""), lanRules = true)
            if (actualV6.isNotEmpty()) v6OwnerMatchSeen = true

            val fullyBlocked = actualV4.filterValues { it >= LAN_RANGES_V4.size }.keys
            blockedLanUids.clear()
            blockedLanUids.addAll(fullyBlocked)

            val missing = uidsToBlock - fullyBlocked
            val notRemoved = uidsToUnblock.intersect(actualV4.keys)
            val duplicated = actualV4.filterValues { it > LAN_RANGES_V4.size }.keys
            val v6NotRemoved = uidsToUnblock.intersect(actualV6.keys)
            val v6Missing = if (v6OwnerMatchSeen || lockBusyRejections(output, ADD_FAIL_V6) > 0) {
                uidsToBlock - actualV6.filterValues { it >= LAN_RANGES_V6.size }.keys
            } else {
                emptySet()
            }

            if (missing.isNotEmpty() || notRemoved.isNotEmpty() || duplicated.isNotEmpty() ||
                v6NotRemoved.isNotEmpty() || v6Missing.isNotEmpty()
            ) {
                AppLogger.w(
                    TAG,
                    "⚠️ LAN rules do not match intent - missing=$missing, missingV6=$v6Missing, stillBlockedV4=$notRemoved, " +
                        "stillBlockedV6=$v6NotRemoved, duplicated=$duplicated; arming a full rewrite"
                )
                chainNeedsResync = true
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
                // alone (4) is a different key from PACKAGE_DETAIL_FLAGS and every
                // call was a guaranteed miss - one binder round trip per app, inside a filter over
                // every package. Measured on hardware: 53 hit / 587 miss before, and the whole
                // Block All start took 8.35s.
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.PACKAGE_DETAIL_FLAGS,
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

