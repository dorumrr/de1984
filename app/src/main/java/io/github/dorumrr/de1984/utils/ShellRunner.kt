package io.github.dorumrr.de1984.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * What one bounded process run produced.
 *
 * [stdout] and [stderr] are kept apart because the callers disagree about which one they want, and
 * merging them at the source is how a `settings get` ends up returning an error string as if it
 * were a value. Use [merged] where that merge is what you actually mean.
 *
 * [exitCode] is `-1` whenever the run produced no code of its own - it never started, it threw, or
 * it was abandoned - which is the failure value every caller in this app already reads.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    /**
     * stdout when it holds anything but whitespace, otherwise stderr. Both trimmed.
     *
     * Blankness is judged AFTER trimming on purpose: a command that prints a stray newline to
     * stdout and its real complaint to stderr would otherwise report the newline and drop the
     * complaint, which is exactly the message a failing command needs to hand back.
     */
    val merged: String
        get() {
            val out = stdout.trim()
            return if (out.isNotEmpty()) out else stderr.trim()
        }
}

/**
 * Runs one external process with a ceiling that can actually fire, and returns its output.
 *
 * The single place in the app that starts a [Process] ITSELF - nine call sites that had each copied
 * the same three bugs. It is NOT the only way the app reaches a shell: RootManager and
 * HiddenApiHelper go through libsu (`Shell.cmd`, `Shell.getCachedShell`), which keeps one long-lived
 * root shell and drains it on its own threads. That route has none of the three bugs below and none
 * of the protections either - it has no ceiling at all.
 *
 * The distinction matters when reading a bug report. IptablesFirewallBackend and
 * BootProtectionManager both test root FIRST, so on a ROOTED device the firewall never comes through
 * here. Everything below applies to the Shizuku path and to the raw `su` fallbacks.
 *
 * ## 1. A timeout wrapped round blocking code does nothing
 *
 * Nothing on this path checks for coroutine cancellation: not `Runtime.exec`, not the stream reads,
 * not `Process.waitFor()`, and not `Binder.transact` underneath Shizuku's remote process. A
 * `withTimeoutOrNull` placed straight round them registers a cancel that nothing ever acts on. It
 * does not even flag the overrun: when the block never suspends it runs to completion and its value
 * is returned, so the `?: -1` fallbacks written next to those guards were unreachable code.
 * Measured: a 500ms ceiling round a 3000ms blocking call returned the value after 3027ms.
 *
 * So the blocking half runs on [shellScope], which is NOT a child of the caller. Structured
 * concurrency would put the bug straight back, because a scope waits for children before it gives
 * up and these children cannot be cancelled. The caller waits on `await()` instead - a real
 * suspension point a timeout can cut - and on expiry walks away and destroys the process. Closing
 * the remote ends is the only thing that frees a read already blocked on a pipe.
 *
 * ## 2. Draining one pipe at a time deadlocks
 *
 * stdout and stderr are separate pipes with their own ~64KB kernel buffer. Reading stdout to EOF
 * first hangs forever on any command that fills stderr: the child blocks writing stderr, therefore
 * never closes stdout, therefore the stdout read never ends. Measured: still hung after 4 seconds
 * on 200KB of stderr, and freed only by `destroy()`. Both pipes are drained together here.
 *
 * ## 3. Nothing is destroyed on the abandoned path
 *
 * `destroy()` is what releases the process and its pipes. Shizuku's `ShizukuRemoteProcess` also
 * holds itself in a static CACHE until its binderDied fires. See issue #93.
 */
object ShellRunner {

    private const val TAG = "ShellRunner"

    /**
     * The floor, and the ceiling for any ordinary one-line command.
     *
     * Thirty seconds because a `su` command has to outlast the root manager's grant dialog, which is
     * the same reason De1984Application already gives libsu thirty. The two now agree.
     */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * What one script line is allowed to cost before the whole script is called hung.
     *
     * ~20ms per iptables invocation was measured on a real device. A rewrite pays that twice per
     * line - once to add the new rule, once for the trailing pass that deletes the matching old one
     * - so ~40ms is the honest figure, and this is roughly four times that to survive a slower
     * phone. It is head-room, not a target.
     */
    private const val PER_LINE_BUDGET_MS = 150L

    /** Absolute backstop, so an enormous command cannot buy itself an unbounded ceiling. */
    private const val MAX_TIMEOUT_MS = 10 * 60_000L

    /** How much of a label reaches the log. See [shortLabel]. */
    private const val LABEL_MAX_CHARS = 120

    /**
     * The ceiling for a read somebody is waiting on.
     *
     * Deliberately far tighter than [DEFAULT_TIMEOUT_MS]. These are `pm list packages` and `pm dump`
     * calls that run SYNCHRONOUSLY on paths that reach the main thread - HiddenApiHelper says so in
     * its own note - and every one already has a "could not answer" branch to fall into. They
     * measured under 100ms on a real device, so five seconds is sixty times the honest cost and
     * still well inside what a person would call frozen.
     */
    const val READ_TIMEOUT_MS = 5_000L

    /**
     * A ceiling for [command], scaled to how much work it actually asks for.
     *
     * One flat number cannot serve both `settings get global x` and IptablesFirewallBackend's chain
     * rewrite, which sends the ENTIRE rule set as a single command. That rewrite costs up to seven
     * iptables invocations per blocked app - two for the internet DROPs, five for the LAN ranges,
     * and "Block All Networks" turns LAN blocking on for every app - plus a trailing pass that
     * deletes one old rule at a time. A flat thirty seconds runs out near a hundred blocked apps.
     *
     * Cutting that script is not a small failure. The rewrite adds every new rule BEFORE deleting
     * any old one, deliberately, so that a broken script fails closed. Kill it mid-delete and the
     * chain holds both sets; the next attempt then has MORE rules to delete than this one, so every
     * retry is slower than the one before and the rewrite can diverge instead of settling.
     *
     * So the ceiling is per line, with a floor and a hard cap. It still bounds a wedged shell. It
     * simply stops calling a large job a hung one.
     */
    fun ceilingFor(command: String): Long {
        val lines = command.count { it == '\n' } + 1
        return (lines * PER_LINE_BUDGET_MS).coerceIn(DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /**
     * Process-lifetime, and detached from every caller. See the note on this class.
     *
     * Nothing cancels it: an abandoned read is released by `destroy()`, not by cancellation, and
     * cancelling the scope would only orphan the thread it was meant to free.
     */
    private val shellScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * How one bounded run ended.
     *
     * Private on purpose - callers get a [ShellResult] or a plain nullable value. What is shared is
     * the RULE for deciding between those three endings, and for letting a cancelled CALLER through
     * untouched. That rule lived in nine places and was wrong in all nine; it now lives in
     * [attempt] and nowhere else.
     */
    private sealed interface Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>
        data class Failed(val cause: Throwable) : Outcome<Nothing>
        data object TimedOut : Outcome<Nothing>
    }

    /**
     * Runs [work] detached from the caller and gives up on it after [timeoutMs].
     *
     * [onAbandon] is the caller's lever for freeing whatever is stuck - `Process.destroy()` for a
     * process we started, `Shell.close()` for a wedged libsu shell. It runs on [shellScope], never
     * on the caller: it can itself block, and a wedged thing must not hold us up twice on the way
     * out.
     */
    private suspend fun <T : Any> attempt(
        label: String,
        timeoutMs: Long,
        onAbandon: () -> Unit,
        abandonOnCancel: Boolean = true,
        work: suspend CoroutineScope.() -> T
    ): Outcome<T> {
        val job = shellScope.async { work() }
        val startedAt = System.currentTimeMillis()

        val value = try {
            withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (e: CancellationException) {
            // The CALLER was cancelled, not the timeout. Let the cancel through - swallowing it
            // would break structured concurrency for everything upstream.
            if (abandonOnCancel) {
                abandon(onAbandon)
            } else {
                // Still bounded: the work may be wedged, and nobody else frees it before its ceiling.
                shellScope.launch {
                    val left = (timeoutMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
                    if (withTimeoutOrNull(left) { job.join() } == null) {
                        report(warn = false, "${shortLabel(label)} timed out after ${timeoutMs}ms, its caller gone - abandoning it")
                        runCatching { onAbandon() }
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            // WARN, not ERROR. The common case is `su` on a phone that has no root: the package
            // fallbacks probe it on every refresh and treat the failure as routine. An ERROR line
            // for a normal condition rotates away the log the user switched on to capture
            // something else.
            report(warn = true, "${shortLabel(label)} could not run: ${describe(e)}")
            return Outcome.Failed(e)
        }

        if (value != null) {
            return Outcome.Done(value)
        }

        // ERROR, because unlike the case above this one should not happen: something took longer
        // than the work it was asked to do could justify.
        report(warn = false, "${shortLabel(label)} timed out after ${timeoutMs}ms - abandoning it")
        abandon(onAbandon)
        return Outcome.TimedOut
    }

    private fun abandon(onAbandon: () -> Unit) {
        shellScope.launch { runCatching { onAbandon() } }
    }

    /**
     * Runs [spawn] and reads it to completion, or gives up after [timeoutMs].
     *
     * Never throws for a process failure - a failed start, a thrown read and an abandoned run all
     * come back as [ShellResult] with `exitCode == -1`. Cancellation of the CALLER is passed
     * through untouched.
     *
     * [label] names the work in the log when something goes wrong. It may be long - callers pass
     * whole scripts - so it is shortened before it is written anywhere. See [shortLabel].
     */
    suspend fun run(
        label: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        spawn: () -> Process
    ): ShellResult {
        val processRef = AtomicReference<Process?>(null)

        val outcome = attempt(
            label = label,
            timeoutMs = timeoutMs,
            // Closing the remote ends is the only thing that frees a read already blocked on a
            // pipe; without it that thread never comes back.
            onAbandon = { processRef.get()?.destroy() }
        ) {
            val process = spawn().also { processRef.set(it) }
            try {
                // Both pipes are drained at the same time, and that is not a style preference.
                // getErrorStream() is a second real pipe over its own descriptor, not a copy of
                // stdout, so draining stdout to EOF first deadlocks any command that fills the
                // 64KB stderr buffer: the child blocks writing stderr, therefore never closes
                // stdout, therefore the stdout read never ends. `dumpsys netpolicy` already
                // returns thousands of lines.
                val output = async { drain(process.inputStream) }
                val error = async { drain(process.errorStream) }

                val stdout = output.await()
                val stderr = error.await()

                // Safe here and only here: both pipes are at EOF, so the child has stopped writing
                // and this returns at once.
                ShellResult(process.waitFor(), stdout, stderr)
            } finally {
                // Destroy on every path. It is the only thing that releases the process and its
                // pipes on the far side, and Shizuku's ShizukuRemoteProcess also holds itself in a
                // static CACHE until its binderDied fires - see issue #93. Do not touch
                // outputStream: getOutputStream() is lazy, so asking for it would open an fd over
                // binder that no caller ever wanted. Nothing writes stdin.
                runCatching { process.destroy() }
            }
        }

        return when (outcome) {
            is Outcome.Done -> outcome.value
            is Outcome.Failed -> ShellResult(-1, "", describe(outcome.cause))
            Outcome.TimedOut -> ShellResult(-1, "", "Timed out after ${timeoutMs}ms", timedOut = true)
        }
    }

    /**
     * Bounds a blocking call this class did NOT start, and returns null if it did not finish.
     *
     * This is for libsu. `Shell.cmd(...).exec()` and `shell.newJob()...exec()` block the calling
     * thread with no ceiling of any kind, and libsu serialises every job onto ONE long-lived root
     * shell - so a single wedged command does not hang one caller, it hangs every caller after it,
     * for the life of the process. That is the path a ROOTED device actually takes for the
     * firewall, for boot protection and for reading other user profiles.
     *
     * [onAbandon] should close the wedged shell. `ShellImpl.close()` is not synchronized while
     * `exec0` is, so it can run while a job is stuck and drop the streams the stuck job is reading
     * - the same lever `destroy()` gives us for a process. Verified in libsu 6.0.0's bytecode.
     *
     * Best effort, and honestly so: if libsu will not let go, later commands time out rather than
     * hang. Bounded and failing beats unbounded and silent.
     *
     * A cancelled CALLER does not trigger [onAbandon] at once: the shell is shared, and closing it
     * would cut whatever job is on it. [onAbandon] runs only if the work outlives [timeoutMs].
     */
    suspend fun <T : Any> bounded(
        label: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onAbandon: () -> Unit = {},
        work: () -> T
    ): T? = (attempt(label, timeoutMs, onAbandon, abandonOnCancel = false) { work() } as? Outcome.Done)?.value

    /**
     * Logs off the caller's thread.
     *
     * AppLogger is not free when the user has file logging on: it appends to the log AND re-counts
     * every line of it, up to a megabyte, on whatever thread calls it. run() can be awaited from
     * the main thread - SettingsFragmentViews does exactly that - so logging inline would put file
     * IO on the UI thread on the one path that reports a failure.
     */
    private fun report(warn: Boolean, message: String) {
        shellScope.launch {
            if (warn) AppLogger.w(TAG, message) else AppLogger.e(TAG, message)
        }
    }

    /**
     * A label short enough to log.
     *
     * Callers name the work with the command itself, and IptablesFirewallBackend's command is the
     * whole chain rewrite - thousands of lines. Dumping that into a rotating one-megabyte log
     * destroys the very history someone turned logging on to read.
     */
    private fun shortLabel(label: String): String {
        val firstLine = label.substringBefore('\n')
        val head = if (firstLine.length > LABEL_MAX_CHARS) firstLine.take(LABEL_MAX_CHARS) + "..." else firstLine
        return if (label.length > firstLine.length) "$head [+${label.length - firstLine.length} more chars]" else head
    }

    private fun drain(stream: InputStream): String {
        val text = StringBuilder()
        stream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                text.append(line).append("\n")
            }
        }
        return text.toString()
    }

    /**
     * The root cause in words. Reflection wraps everything in InvocationTargetException, whose own
     * message is null, so the useful part is always one or more levels down.
     */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val name = root.javaClass.simpleName
        val message = root.message
        return if (message.isNullOrBlank()) name else "$name: $message"
    }
}
