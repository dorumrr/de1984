package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BootProtectionManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager
) {
    companion object {
        private const val TAG = "BootProtectionManager"

        /**
         * Wait for the xtables lock instead of failing instantly.
         *
         * netd rewrites the tables constantly around boot, which is exactly when this code runs. A
         * lock collision without this makes iptables exit non-zero, and a teardown that never
         * happened then looks like one that did.
         */
        private const val XT_WAIT = "-w 5"

        /** Bound on the unlink loop, so a jump that cannot be removed cannot spin forever. */
        private const val MAX_JUMP_REMOVALS = 16
    }

    suspend fun isBootScriptSupportAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "Checking if boot script support is available...")

            val command = "test -d ${Constants.BootProtection.MAGISK_POST_FS_DIR} && echo 'exists' || echo 'not_found'"
            val result = executeCommand(command)

            val available = result.first == 0 && result.second.trim() == "exists"
            AppLogger.d(TAG, "Boot script support available: $available (exitCode=${result.first}, output='${result.second.trim()}')")

            available
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check boot script support availability", e)
            false
        }
    }

    suspend fun isBootProtectionEnabled(): Boolean = isBootProtectionInstalled() == true

    /**
     * Is the boot script on disk? `null` means we could not find out.
     *
     * The distinction matters more than it looks. A dropped Shizuku call, an expired su grant or a
     * command timeout all fail the check, and reporting those as "not installed" is how a device that
     * is blocked at every boot ends up looking perfectly fine - or worse, how the only record that
     * boot protection is on gets overwritten with false.
     */
    suspend fun isBootProtectionInstalled(): Boolean? = withContext(Dispatchers.IO) {
        try {
            if (!hasBootProtectionPrivilege()) {
                AppLogger.d(TAG, "No privilege - cannot tell whether the boot script is installed")
                return@withContext null
            }

            val command = "test -f ${Constants.BootProtection.BOOT_SCRIPT_PATH} && echo 'exists' || echo 'not_found'"
            val (exitCode, output) = executeCommand(command)

            when {
                exitCode == 0 && output.trim() == "exists" -> true
                exitCode == 0 && output.trim() == "not_found" -> false
                else -> {
                    AppLogger.w(TAG, "Boot script check inconclusive (exit=$exitCode, output='${output.trim()}')")
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check boot protection status", e)
            null
        }
    }

    suspend fun setBootProtection(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "${if (enabled) "ENABLING" else "DISABLING"} BOOT PROTECTION")

            if (enabled) {
                createBootScript()
            } else {
                deleteBootScript()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to ${if (enabled) "enable" else "disable"} boot protection", e)
            Result.failure(e)
        }
    }

    private suspend fun createBootScript(): Result<Unit> {
        AppLogger.d(TAG, "Creating boot protection script...")

        // Script content that blocks all network traffic during boot
        // We use a custom chain to isolate boot protection rules from system rules
        // This allows clean removal when De1984 starts
        val scriptContent = """#!/system/bin/sh
# De1984 Boot Protection
# Blocks all network traffic until De1984 starts
# Author: Doru Moraru

# -w 5 on every call: this function also runs from the expiry timer 120s after boot, when netd is
# very much alive and rewriting the tables. Without the lock wait a collision makes iptables fail,
# and the block would simply stay up.
remove_boot_chain() {
    while iptables -w 5 -D OUTPUT -j ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null; do :; done
    while ip6tables -w 5 -D OUTPUT -j ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null; do :; done
    iptables -w 5 -F ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null
    iptables -w 5 -X ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null
    ip6tables -w 5 -F ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null
    ip6tables -w 5 -X ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null
}

# If De1984 has been uninstalled this script is orphaned: no app will ever lift the
# block. Delete ourselves and leave the device alone. These paths are device-encrypted
# so the check works before the user unlocks.
# Globbed across every user, not just user 0. Installed for a secondary user or a work profile
# only, a user-0-shaped check finds nothing and deletes a script that should still run.
# An unmatched glob stays literal in sh, and [ -e ] on a literal is false, so this is safe.
de1984_present() {
    for _d in /data/user_de/*/${BuildConfig.APPLICATION_ID}; do
        [ -e "${'$'}_d" ] && return 0
    done
    return 1
}

if ! de1984_present; then
    rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}
    exit 0
fi

# Create custom chain for boot protection
iptables -w 5 -N ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null || iptables -w 5 -F ${Constants.BootProtection.BOOT_CHAIN}
ip6tables -w 5 -N ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null || ip6tables -w 5 -F ${Constants.BootProtection.BOOT_CHAIN}

# Allow loopback traffic (required for system services)
iptables -w 5 -A ${Constants.BootProtection.BOOT_CHAIN} -o lo -j ACCEPT
ip6tables -w 5 -A ${Constants.BootProtection.BOOT_CHAIN} -o lo -j ACCEPT

# Allow critical system UIDs needed for network connectivity.
#
#    0  root           netd and the other core network daemons
# 1000  system         system_server and the Android framework
# 1001  radio          telephony stack (RIL) - without it mobile data cannot come up
# 1010  wifi           wpa_supplicant, wificond
# 1016  vpn            VPN plumbing. Previously commented "media" here; media is 1013.
# 1029  clat           464XLAT translator. On IPv6-only carriers all IPv4 traffic, system
#                      traffic included, egresses as this uid.
# 1051  dns            DNS resolver. Previously commented "gps" here; gps is 1021.
# 1073  network_stack  connectivity and captive-portal probes. Without it Android can mark
#                      the network unvalidated and keep showing "no internet" after the
#                      block lifts, until the next probe succeeds.
# 2000  shell          adb, and Shizuku when it runs in ADB mode rather than root mode.
#                      Blocking it can lock a Shizuku-only user out for good: Shizuku cannot
#                      start, so the firewall never starts, so nothing ever lifts the block.
#                      It also keeps wireless adb alive as a recovery route. USB adb is
#                      unaffected either way, since it is not network traffic.
for uid in 0 1000 1001 1010 1016 1029 1051 1073 2000; do
    iptables -w 5 -A ${Constants.BootProtection.BOOT_CHAIN} -m owner --uid-owner ${'$'}uid -j ACCEPT
    ip6tables -w 5 -A ${Constants.BootProtection.BOOT_CHAIN} -m owner --uid-owner ${'$'}uid -j ACCEPT
done

# Block everything else (user apps)
iptables -w 5 -A ${Constants.BootProtection.BOOT_CHAIN} -j DROP
ip6tables -w 5 -A ${Constants.BootProtection.BOOT_CHAIN} -j DROP

# Link the chain into OUTPUT - but only if the allow-list actually landed.
#
# There is no "set -e" here and the DROP above is unconditional. If the uid rules fail - no xt_owner
# module in the kernel, a lost xtables lock, EPERM - the chain becomes nothing but [lo ACCEPT, DROP].
# Linking that blacks out the ENTIRE device: root, netd, system_server, adb, everything. De1984 could
# not even reach the system to undo it. Failing open is strictly better than bricking the network:
# the app's own firewall takes over moments later anyway.
#
# The -C guard also stops a re-run stacking a second jump that a single -D would miss.
link_if_sane() {
    _t="${'$'}1"
    if ${'$'}_t -w 5 -C ${Constants.BootProtection.BOOT_CHAIN} -m owner --uid-owner 0 -j ACCEPT 2>/dev/null; then
        ${'$'}_t -w 5 -C OUTPUT -j ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null || ${'$'}_t -w 5 -I OUTPUT -j ${Constants.BootProtection.BOOT_CHAIN}
    else
        echo "de1984: ${'$'}_t allow-list missing, refusing to link a drop-all chain" > /dev/kmsg 2>/dev/null
        ${'$'}_t -w 5 -F ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null
        ${'$'}_t -w 5 -X ${Constants.BootProtection.BOOT_CHAIN} 2>/dev/null
    fi
}

link_if_sane iptables
link_if_sane ip6tables

# Safety net. The block is only meant to cover the gap before De1984 takes over.
# If that never happens - firewall left off, start failed, screen still locked, app
# data cleared - nothing else would ever lift it and the device would have no network
# on every boot, forever. This makes the block expire on its own.
# De1984 normally removes the chain within seconds, long before this fires.
(
    sleep ${Constants.BootProtection.SELF_HEAL_TIMEOUT_SECONDS}
    remove_boot_chain
) &
"""

        val createCommand = "echo '${scriptContent.replace("'", "'\\''")}' > ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val createResult = executeCommand(createCommand)

        if (createResult.first != 0) {
            val error = "Failed to create boot script (exit code: ${createResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }

        AppLogger.d(TAG, "✅ Boot script created successfully")

        // Read the script back and verify it landed intact. A partial write would install the
        // catch-all DROP without the ACCEPT rules above it, which blocks the device at every boot.
        // The caller reboots on success, so we must never report success on an unverified write.
        val verifyResult = executeCommand("cat ${Constants.BootProtection.BOOT_SCRIPT_PATH}")
        if (verifyResult.first != 0 || verifyResult.second.trimEnd() != scriptContent.trimEnd()) {
            AppLogger.e(TAG, "Boot script readback did not match what was written - removing it")
            executeCommand("rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}")
            return Result.failure(Exception("Boot script was written incorrectly and has been removed"))
        }

        AppLogger.d(TAG, "✅ Boot script content verified")

        val chmodCommand = "chmod ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS} ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val chmodResult = executeCommand(chmodCommand)

        if (chmodResult.first != 0) {
            val error = "Failed to set script permissions (exit code: ${chmodResult.first})"
            AppLogger.e(TAG, error)
            // Do not leave an orphan script behind: the preference stays off, so nothing would
            // ever remove it and the user would have no way to see or clear it.
            executeCommand("rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}")
            return Result.failure(Exception(error))
        }

        AppLogger.d(TAG, "✅ Script permissions set to ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS}")
        AppLogger.d(TAG, "✅ Boot protection enabled successfully")

        return Result.success(Unit)
    }

    /**
     * Reboot the device.
     *
     * Called immediately after boot protection is successfully enabled or disabled, so that the
     * on-disk script and the live iptables state can never disagree. Only ever called after a
     * verified successful change - never after a failed one.
     */
    suspend fun rebootDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            AppLogger.d(TAG, "Rebooting device to apply boot protection change")
            val result = executeCommand("svc power reboot")

            if (result.first != 0) {
                val error = "Failed to reboot device (exit code: ${result.first})"
                AppLogger.e(TAG, error)
                Result.failure(Exception(error))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to reboot device", e)
            Result.failure(e)
        }
    }

    /**
     * Last-resort removal for the case where the Boot Protection switch is greyed out.
     *
     * Lockout scenario 5: boot protection was enabled while the device had root, and root was lost
     * afterwards - Magisk removed, a denied prompt, an OTA, a ROM change. The script is still under
     * /data/adb, which is root-only, so the app can neither read nor delete it. Every boot then
     * blocks all apps until the script's own 120-second timer lifts it.
     *
     * The help text used to tell the user to run `su`, which is precisely what they lost. This is
     * the button that replaces that advice, and it is worth having because "lost root" is usually
     * "root not granted right now" - Magisk not awake yet, or a single Deny - rather than root
     * genuinely gone.
     *
     * The privilege wake is the same one [clearBootBlockIfInstalled] does, for the same reason:
     * hasRootPermission is a cached answer, and asking Magisk again is what turns a stale false into
     * a working su. Removal itself goes through [deleteBootScript], so it keeps that function's
     * read-back check and its live-chain teardown rather than becoming a second way to do the same
     * thing.
     *
     * @return failure carrying [NoPrivilegeException] when root is genuinely gone - the caller needs
     *   to tell the user that plainly instead of blaming the deletion.
     */
    suspend fun retryRemoveBootProtection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "Retrying boot protection removal after a privilege loss")

            if (!hasBootProtectionPrivilege()) {
                AppLogger.d(TAG, "No privilege - asking for root again before giving up")
                rootManager.forceRecheckRootStatus()
            }

            if (!hasBootProtectionPrivilege()) {
                AppLogger.w(TAG, "Still no privileged access - the script cannot be removed from here")
                return@withContext Result.failure(NoPrivilegeException())
            }

            AppLogger.d(TAG, "Privilege recovered - removing the boot script")
            deleteBootScript()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Retry removal of boot protection failed", e)
            Result.failure(e)
        }
    }

    /** Root is genuinely unavailable, as opposed to the removal itself going wrong. */
    class NoPrivilegeException : Exception("No root or Shizuku root access")

    private suspend fun deleteBootScript(): Result<Unit> {
        AppLogger.d(TAG, "Deleting boot protection script...")

        val deleteCommand = "rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val deleteResult = executeCommand(deleteCommand)

        if (deleteResult.first != 0) {
            val error = "Failed to delete boot script (exit code: ${deleteResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }

        // Confirm the file is actually gone. The caller reboots on success and would otherwise
        // reboot straight back into a device that is still blocked at every boot.
        if (isBootProtectionInstalled() != false) {
            val error = "Boot script still present after deletion, or its removal could not be confirmed"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }

        AppLogger.d(TAG, "✅ Boot script deleted successfully")

        // Remove the live chain too. The caller reboots immediately, which would also clear it,
        // but if that reboot never happens the device must still recover on its own.
        resetIptablesPolicies()

        AppLogger.d(TAG, "✅ Boot protection disabled successfully")

        return Result.success(Unit)
    }

    /**
     * Lift the boot-protection block if the script is installed on disk.
     *
     * Called early in both boot paths, BEFORE any decision about the firewall. This used to happen
     * only inside startFirewall().onSuccess, which meant a firewall the user had switched off - or one
     * that failed to start - left the device blocked on every boot with no in-app way out.
     *
     * Deliberately keyed on the script actually being present on disk, not on the boot_protection
     * preference, because clearing app data resets that preference to false while leaving the script
     * in place.
     */
    suspend fun clearBootBlockIfInstalled(): Result<Unit> {
        // At boot the app has not yet asked Magisk for root, so hasRootPermission is still false and
        // every command here would silently no-op - isBootProtectionEnabled() would report "false"
        // for a script that is plainly on disk. Wake the privilege first.
        if (!hasBootProtectionPrivilege()) {
            AppLogger.d(TAG, "No privilege yet - requesting root before checking boot protection")
            rootManager.forceRecheckRootStatus()
        }

        if (!hasBootProtectionPrivilege()) {
            // Cannot check and cannot act. Say so rather than reporting "not enabled": the boot
            // script may well be installed and still blocking. The script's own expiry timer is
            // the remaining safety net.
            AppLogger.w(TAG, "No privileged access - cannot check or lift a boot protection block")
            return Result.failure(Exception("No root or Shizuku access"))
        }

        // Only skip on a definite "no". If the check was inconclusive we tear down regardless: doing
        // it needlessly costs a few harmless iptables calls, skipping it wrongly leaves the device
        // with no network.
        if (isBootProtectionInstalled() == false) {
            AppLogger.d(TAG, "No boot protection script installed - nothing to lift")
            return Result.success(Unit)
        }

        AppLogger.d(TAG, "Boot protection script is installed - lifting its block")
        return resetIptablesPolicies()
    }

    suspend fun resetIptablesPolicies(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                AppLogger.d(TAG, "Removing boot protection iptables rules...")

                // Without privilege every command below is a silent no-op that still "succeeds".
                // Callers reboot, or decide the device is safe, on the strength of this result.
                if (!hasBootProtectionPrivilege()) {
                    val error = "No root (or root-mode Shizuku) - boot protection block was NOT lifted"
                    AppLogger.e(TAG, error)
                    return@withContext Result.failure(Exception(error))
                }

                for (table in listOf("iptables", "ip6tables")) {
                    // Always attempt the delete, never gate it on a probe. -D returns 0 when it
                    // removed a jump and non-zero when there was nothing left, so the loop ends by
                    // itself and a stacked jump cannot survive. An earlier version only ran -D when a
                    // -C probe returned 0, which meant any probe failure - a held xtables lock, a
                    // denial - produced zero teardown attempts.
                    var removed = 0
                    while (removed < MAX_JUMP_REMOVALS &&
                        executeCommand("$table $XT_WAIT -D OUTPUT -j ${Constants.BootProtection.BOOT_CHAIN}").first == 0
                    ) {
                        removed++
                    }
                    AppLogger.d(TAG, "$table: removed $removed ${Constants.BootProtection.BOOT_CHAIN} jump(s) from OUTPUT")

                    executeCommand("$table $XT_WAIT -F ${Constants.BootProtection.BOOT_CHAIN}")
                    executeCommand("$table $XT_WAIT -X ${Constants.BootProtection.BOOT_CHAIN}")
                }

                // Verify the outcome. Exit codes must be read carefully: -C returns 0 when the jump
                // is still there, 1 when the rule is absent and 2 when the chain is absent - both of
                // which mean gone - but it also returns other codes for a held lock or a denial.
                // Treating "could not check" as "gone" is how this function used to report a verified
                // teardown on a device that was still fully blocked.
                val unresolved = mutableListOf<String>()
                for (table in listOf("iptables", "ip6tables")) {
                    val code = executeCommand("$table $XT_WAIT -C OUTPUT -j ${Constants.BootProtection.BOOT_CHAIN}").first
                    when (code) {
                        0 -> unresolved += "$table (jump still linked)"
                        1, 2 -> Unit
                        else -> unresolved += "$table (could not verify, exit $code)"
                    }
                }

                if (unresolved.isNotEmpty()) {
                    val error = "Boot protection teardown unverified: ${unresolved.joinToString()}"
                    AppLogger.e(TAG, error)
                    return@withContext Result.failure(Exception(error))
                }

                AppLogger.d(TAG, "✅ Boot protection iptables rules removed and verified gone")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to remove boot protection iptables rules", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Whether we can actually act on boot protection.
     *
     * Shizuku permission alone is not enough. In ADB mode Shizuku runs as uid 2000, which can touch
     * neither iptables nor /data/adb, so every command would fail while the code believed it had
     * privilege. Only root, or Shizuku running in root mode, can do this work.
     */
    private fun hasBootProtectionPrivilege(): Boolean =
        rootManager.hasRootPermission ||
            (shizukuManager.hasShizukuPermission && shizukuManager.isShizukuRootMode())

    private suspend fun executeCommand(command: String): Pair<Int, String> {
        return if (rootManager.hasRootPermission) {
            rootManager.executeRootCommand(command)
        } else if (shizukuManager.hasShizukuPermission) {
            shizukuManager.executeShellCommand(command)
        } else {
            Pair(-1, "No root or Shizuku access")
        }
    }
}

