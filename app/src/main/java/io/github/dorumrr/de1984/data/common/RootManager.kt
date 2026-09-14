package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.ShellRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class RootStatus {
    NOT_ROOTED,

    ROOTED_NO_PERMISSION,

    ROOTED_WITH_PERMISSION,

    CHECKING
}

fun hasPrivilegedAccess(rootStatus: RootStatus, shizukuStatus: ShizukuStatus): Boolean =
    rootStatus == RootStatus.ROOTED_WITH_PERMISSION || shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION

fun privilegeProbesAnswered(rootStatus: RootStatus, shizukuStatus: ShizukuStatus): Boolean =
    rootStatus != RootStatus.CHECKING && shizukuStatus != ShizukuStatus.CHECKING

// False unless both probes answered within timeoutMs; CHECKING is not an answer.
suspend fun awaitPrivilegeProbes(rootStatus: StateFlow<RootStatus>, shizukuStatus: StateFlow<ShizukuStatus>, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { combine(rootStatus, shizukuStatus, ::privilegeProbesAnswered).first { it } } != null

class RootManager(private val context: Context) {

    companion object {
        private const val TAG = "RootManager"
        private const val PREFS_NAME = "de1984_root"
        private const val KEY_ROOT_PERMISSION_REQUESTED = "root_permission_requested"

        /** Attempts before a missing root shell is recorded as NOT_ROOTED, unless the caller passes retry = false. */
        private const val MAX_ROOT_ATTEMPTS = 3

        /** Breathing room for a root manager that is still starting - KernelSU LKM, a slow grant dialog. */
        private const val ROOT_RETRY_DELAY_MS = 800L

        /**
         * How many long root commands are running on the shared shell right now.
         *
         * A read that blows its 5s ceiling has two very different causes, and the recovery for one
         * is fatal to the other:
         *
         * - the shell is genuinely wedged, and dropping it is the only way out;
         * - or a legitimate command is simply still running. `ShellRunner.ceilingFor` gives an
         *   iptables chain rewrite 30s to 10min, and libsu serialises every job on one `ShellImpl`,
         *   so a read issued during one ALWAYS waits for it and ALWAYS blows a 5s ceiling.
         *
         * In the second case dropping the shell kills the rewrite half-written, and
         * `IptablesFirewallBackend` then has the old rules PLUS a partial new set to trim on the
         * retry - so each attempt is bigger, slower, and likelier to be cut than the last, and the
         * chain stops converging. `PackageMonitoringService` polls every 30s while the screen is on,
         * for every profile EXCEPT user 0, and not at all while the screen is off - so on a rooted
         * device in use this race is real, not rare.
         *
         * This counter is the only thing that tells the two apart.
         */
        private val longRootCommandsInFlight = AtomicInteger(0)

        /**
         * Drops the shared root shell after a command has blown its ceiling.
         *
         * libsu runs every job on ONE long-lived shell, so a single wedged command does not hang one
         * caller - it hangs every caller after it, for the life of the process. Dropping the shell is
         * the only way out, and libsu opens a fresh one on the next `Shell.getShell()`.
         *
         * It works because `ShellImpl.close()` is NOT synchronized while its `exec0` is, so it can
         * run while a job is stuck and take away the streams that job is blocked reading - the same
         * lever `Process.destroy()` gives us elsewhere. Verified in libsu 6.0.0's own bytecode.
         *
         * That same power is why a SHORT read must not call this directly - use
         * [closeRootShellIfIdle]. This one is for a command that blew its OWN generous ceiling,
         * where "wedged" is the only remaining explanation.
         *
         * Lives here rather than at each call site because HiddenApiHelper needs the same lever, and
         * two copies of "how to recover the root shell" is how they drift apart.
         */
        fun closeWedgedRootShell() {
            AppLogger.w(TAG, "⚠️ Dropping the cached root shell - a command did not finish in time")
            runCatching { Shell.getCachedShell()?.close() }
        }

        /**
         * Recovery for a SHORT read that timed out: drop the shell only if nothing legitimate is
         * using it. See [longRootCommandsInFlight] for why the distinction is load-bearing.
         *
         * Waiting is safe. A truly wedged shell is still recovered, just by the long command's own
         * ceiling instead of by a 5s read that happened to queue behind it.
         */
        fun closeRootShellIfIdle() {
            val inFlight = longRootCommandsInFlight.get()
            if (inFlight > 0) {
                AppLogger.d(
                    TAG,
                    "A read did not finish in time, but $inFlight root command(s) are still running " +
                        "- keeping the shell so their work is not cut"
                )
                return
            }
            closeWedgedRootShell()
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _rootStatus = MutableStateFlow<RootStatus>(RootStatus.CHECKING)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private var hasCheckedOnce = false

    val hasRootPermission: Boolean
        get() = _rootStatus.value == RootStatus.ROOTED_WITH_PERMISSION

    fun hasRequestedRootPermission(): Boolean {
        return prefs.getBoolean(KEY_ROOT_PERMISSION_REQUESTED, false)
    }

    fun markRootPermissionRequested() {
        prefs.edit().putBoolean(KEY_ROOT_PERMISSION_REQUESTED, true).apply()
    }

    suspend fun checkRootStatus() {
        checkRootStatusInternalWithCaching(forceRecheck = false)
    }

    /**
     * Re-checks root, ignoring the cached answer. Public, with call sites in BootReceiver,
     * BootWorker, MainActivity, BootProtectionManager, PrivilegedFirewallService and the backend
     * health loop.
     *
     * Not free on a device that is NOT rooted: it runs the full retry loop below - three
     * `Shell.getShell()` builds, each a failed `su` exec then a short-lived `sh`, with two 800ms
     * waits between them. Measured on hardware: about 1.9s of wall time and 0.46-0.49s of the app's
     * own CPU, not counting the `su` and `sh` processes.
     *
     * That cost is deliberate and must not be "optimised" with a negative cache. The retry IS the
     * fix for issue #79: KernelSU's LKM can load after boot has completed, so a rooted device that
     * answers NOT_ROOTED at first can start answering correctly a few seconds later. The guard at
     * [checkRootStatus] skips only ROOTED_WITH_PERMISSION for the same reason.
     *
     * Pass [retry] = false from a caller that asks again on a timer: its next call is the retry.
     * A ROOTED_WITH_PERMISSION status still gets every try, so one slow answer cannot demote it.
     */
    suspend fun forceRecheckRootStatus(retry: Boolean = true) {
        checkRootStatusInternalWithCaching(forceRecheck = true, retry = retry)
    }

    private suspend fun checkRootStatusInternalWithCaching(forceRecheck: Boolean, retry: Boolean = true) {
        val currentStatus = _rootStatus.value

        AppLogger.d(TAG, "=== checkRootStatusInternalWithCaching() called ===")
        AppLogger.d(TAG, "Current status: $currentStatus, hasCheckedOnce: $hasCheckedOnce, forceRecheck: $forceRecheck")

        // Only skip check if we have definitive permission AND caller did not
        // explicitly request a re-check (e.g., health monitoring after root
        // revocation from Magisk).
        if (!forceRecheck && hasCheckedOnce && currentStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            AppLogger.d(TAG, "Skipping check - already have permission and no forceRecheck")
            return
        }

        if (!hasCheckedOnce) {
            _rootStatus.value = RootStatus.CHECKING
            AppLogger.d(TAG, "First check - setting status to CHECKING")
        }

        val attempts = if (retry || currentStatus == RootStatus.ROOTED_WITH_PERMISSION) MAX_ROOT_ATTEMPTS else 1
        val newStatus = checkRootStatusInternal(attempts)
        _rootStatus.value = newStatus
        hasCheckedOnce = true
        AppLogger.d(TAG, "Root status check complete: $newStatus")
    }

    private suspend fun verifyRootWithCachedShell(): Boolean {
        val cachedShell = Shell.getCachedShell()
        if (cachedShell == null) {
            AppLogger.d(TAG, "No cached shell available")
            return false
        }
        if (!cachedShell.isAlive) {
            AppLogger.d(TAG, "Cached shell is no longer alive")
            return false
        }
        if (!cachedShell.isRoot) {
            AppLogger.d(TAG, "Cached shell is not a root shell (isRoot=false)")
            return false
        }

        // Verify root is still valid by running a command on the existing shell
        // This does NOT spawn a new su process = NO TOAST
        return try {
            val outputList = mutableListOf<String>()
            // Bounded. This runs on the health-check loop; an unbounded exec() here meant a wedged
            // shell silently stopped the app ever noticing that root had gone.
            val result = ShellRunner.bounded(
                label = "root verify: ${Constants.RootAccess.ROOT_VERIFICATION_COMMAND}",
                timeoutMs = ShellRunner.READ_TIMEOUT_MS,
                onAbandon = ::closeRootShellIfIdle
            ) {
                cachedShell.newJob()
                    .add(Constants.RootAccess.ROOT_VERIFICATION_COMMAND)
                    .to(outputList)
                    .exec()
            }

            if (result == null) {
                AppLogger.w(TAG, "⚠️ Cached shell did not answer in time - root is not verified")
                return false
            }

            val isValid = result.isSuccess && 
                outputList.any { it.contains(Constants.RootAccess.ROOT_VERIFICATION_SUCCESS_MARKER) }
            
            if (isValid) {
                AppLogger.d(TAG, "✅ Cached shell verified - root still valid (no toast triggered)")
            } else {
                AppLogger.w(TAG, "⚠️ Cached shell verification failed - root likely revoked")
            }
            isValid
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception verifying cached shell: ${e.message}", e)
            false
        }
    }

    private suspend fun checkRootStatusInternal(attempts: Int): RootStatus = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "🔍 CHECKING ROOT STATUS (using libsu)")

            if (verifyRootWithCachedShell()) {
                AppLogger.d(TAG, "✅ Root verified via cached shell (no toast triggered)")
                return@withContext RootStatus.ROOTED_WITH_PERMISSION
            }

            // STEP 2: Check if we have a cached NON-root shell
            // This can happen if initial shell creation timed out (e.g., Magisk grant dialog)
            // In this case, we need to invalidate the cache and try fresh
            val cachedShell = Shell.getCachedShell()
            if (cachedShell != null && cachedShell.isAlive && !cachedShell.isRoot) {
                AppLogger.w(TAG, "⚠️ Found cached NON-root shell - this may be from a previous timeout")
                AppLogger.w(TAG, "   Closing cached shell and retrying fresh...")
                try {
                    cachedShell.close()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "   Exception closing cached shell: ${e.message}")
                }
            }

            // STEP 3: Get/create a shell
            // Shell.getShell() will:
            // - Return existing cached shell if alive (no toast)
            // - Create new shell if none exists (shows toast ONCE on first grant)
            // - Show permission dialog if never granted
            AppLogger.d(TAG, "Getting main shell (may show toast on first creation)...")

            // Retry before concluding the device has no root.
            //
            // A single failed attempt does NOT mean NOT_ROOTED. It also covers "the root manager was
            // not ready yet", and that is a real case rather than a theoretical one: KernelSU in LKM
            // mode loads its module during boot, so an app that asks early gets a non-root shell from
            // a device that is perfectly rooted. libsu then CACHES that shell, and every later check
            // reads the cached answer - which is how a user ends up with "none of the root features
            // are available" for the whole session. Reported in issue #79 on KernelSU v1.1.1, LKM
            // mode, stock kernel.
            //
            // Each attempt closes the cached non-root shell first, so the retry genuinely re-asks
            // instead of re-reading the same stale answer.
            //
            // Same shape as PackageSafetyLoader's MAX_LOAD_ATTEMPTS: try a bounded number of times,
            // and only then record the negative. A user who has actually denied root pays a short
            // delay once per check, which is the cheaper mistake of the two.
            var shell = Shell.getShell()
            var attempt = 1

            while (!shell.isRoot && attempt < attempts) {
                AppLogger.d(TAG, "No root on attempt $attempt of $attempts - the manager may not be ready, retrying")
                delay(ROOT_RETRY_DELAY_MS)

                Shell.getCachedShell()?.let { stale ->
                    if (!stale.isRoot) {
                        try {
                            stale.close()
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Could not close the cached non-root shell: ${e.message}")
                        }
                    }
                }

                attempt++
                shell = Shell.getShell()
            }

            return@withContext if (shell.isRoot) {
                AppLogger.d(TAG, "✅ Root access GRANTED - ROOTED_WITH_PERMISSION (attempt $attempt)")
                RootStatus.ROOTED_WITH_PERMISSION
            } else {
                AppLogger.d(TAG, "❌ No root after $attempt attempts - NOT_ROOTED")
                RootStatus.NOT_ROOTED
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Exception during root check: ${e.message}", e)
            return@withContext RootStatus.NOT_ROOTED
        }
    }

    /**
     * Runs one command on the shared root shell. Failure is `-1` plus a message, as before.
     *
     * Bounded since 2026-08-28. `Shell.cmd(...).exec()` blocks the calling thread with no ceiling of
     * any kind, and this is the path a ROOTED device takes for the ENTIRE firewall - every iptables
     * rewrite, boot protection, the lot. It was the last unbounded shell route in the app.
     *
     * The ceiling scales with the command because IptablesFirewallBackend sends its whole chain
     * rewrite as one; see ShellRunner.ceilingFor. The reason a run failed is written to the log by
     * ShellRunner, with the root cause rather than the wrapper's null message.
     */
    suspend fun executeRootCommand(command: String): Pair<Int, String> {
        if (!hasRootPermission) {
            return Pair(-1, "No root permission")
        }

        val result = ShellRunner.bounded(
            label = "root shell: $command",
            timeoutMs = ShellRunner.ceilingFor(command),
            onAbandon = ::closeWedgedRootShell
        ) {
            // Counted INSIDE the work, not around the bounded() call. ShellRunner returns the
            // moment the ceiling expires while the job keeps running detached, so counting outside
            // would drop the count to zero at exactly the wrong moment - while the shell is still
            // busy - and let a read close the shell out from under this command.
            longRootCommandsInFlight.incrementAndGet()
            try {
                Shell.cmd(command).exec()
            } finally {
                longRootCommandsInFlight.decrementAndGet()
            }
        } ?: return Pair(-1, "Root command did not finish - see the log")

        return Pair(result.code, result.out.joinToString("\n"))
    }
}

