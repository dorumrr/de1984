package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import io.github.dorumrr.de1984.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Android kills a process whose background broadcast is still open after 60 s.
internal const val RECEIVER_BUDGET_MS = 50_000L

/** [BroadcastReceiver.goAsync] for work that can outlast that limit: the broadcast is released in time; the work goes on in [scope] while the process lives. */
internal fun BroadcastReceiver.goAsyncBounded(scope: CoroutineScope, tag: String, block: suspend () -> Unit) {
    val pendingResult = goAsync()
    val work = scope.launch { block() }
    scope.launch {
        if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { work.join() } == null) {
            AppLogger.w(tag, "Still running after ${RECEIVER_BUDGET_MS}ms - releasing the broadcast, the work goes on")
        }
        pendingResult.finish()
    }
}
