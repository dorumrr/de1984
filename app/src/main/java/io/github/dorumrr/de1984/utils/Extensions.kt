package io.github.dorumrr.de1984.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.github.dorumrr.de1984.R

fun Context.openAppSettings(packageName: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
}

fun Context.copyToClipboard(text: String, label: String = "De1984") {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(this, getString(io.github.dorumrr.de1984.R.string.toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
}

fun View.setOnClickListenerDebounced(debounceTime: Long = 500L, action: (View) -> Unit) {
    var lastClickTime = 0L
    setOnClickListener { view ->
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastClickTime >= debounceTime) {
            lastClickTime = currentTime
            action(view)
        }
    }
}

/**
 * Shows a package count on top of the search field without covering its hint.
 *
 * The counter is drawn OVER the search box, and the box reserves a fixed strip on its right for it.
 * English fits that strip - "83 apps" - but the same count in Romanian reads "466 de aplicații" and
 * runs left across the hint, so the two words collide and neither is readable.
 *
 * When the full text will not fit, the word is dropped and the bare number is shown. **The number is
 * never dropped.** If even the number is too wide it is drawn anyway and allowed to be tight: a
 * counter with no number tells the user nothing at all, which is worse than one that is cramped.
 *
 * The space available is read from the views themselves - what the input reserves on its right, less
 * what this counter keeps for itself - rather than hardcoded, so it stays correct if either padding
 * is ever changed. Both the Firewall and Packages screens use this; they had the same block copied.
 */
fun TextView.setPackageCount(count: Int, blank: Boolean, searchInput: TextView) {
    if (blank) {
        text = ""
        return
    }

    val full = resources.getQuantityString(R.plurals.package_count, count, count)

    fun fit() {
        // Measure the REAL gap, not the strip the layout reserves. That strip (100dp) exists so
        // typed TEXT does not run under the counter; the hint is much shorter than that, so judging
        // against it called English a collision when there was an obvious gap.
        //
        // The gap is: where this counter's text may start, minus where the hint ends.
        val hint = searchInput.hint?.toString().orEmpty()
        val hintEnd = searchInput.left + searchInput.paddingStart + searchInput.paint.measureText(hint)
        val textRight = right - paddingEnd
        val available = textRight - hintEnd - resources.getDimension(R.dimen.search_counter_gap)

        // The number is never dropped. If even the number is wider than the gap it is drawn anyway
        // and allowed to be tight - a counter with no number tells the reader nothing at all.
        text = if (paint.measureText(full) > available) count.toString() else full
    }

    text = full
    if (width == 0 || searchInput.width == 0) {
        // Not laid out yet on the very first pass. Show the full text and correct it once the
        // positions exist; there is nothing to overlap while the list is still empty.
        post { fit() }
    } else {
        fit()
    }
}
