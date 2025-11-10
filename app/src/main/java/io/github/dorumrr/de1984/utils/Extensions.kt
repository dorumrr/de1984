package io.github.dorumrr.de1984.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

fun String.formatPackageName(): String {
    return this.substringAfterLast(".")
}

fun String.isSystemPackage(): Boolean {
    return this.startsWith(Constants.Packages.ANDROID_PACKAGE_PREFIX) ||
           this.startsWith(Constants.Packages.GOOGLE_PACKAGE_PREFIX) ||
           this.startsWith(Constants.Packages.SYSTEM_PACKAGE_PREFIX)
}

fun PackageInfo.getDisplayName(context: Context): String {
    return try {
        val appInfo = this.applicationInfo ?: return this.packageName.formatPackageName()
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        this.packageName.formatPackageName()
    }
}

fun PackageInfo.isSystemApp(): Boolean {
    return this.packageName.isSystemPackage()
}

fun PackageInfo.isEnabled(context: Context): Boolean {
    return try {
        val appInfo = context.packageManager.getApplicationInfo(this.packageName, 0)
        appInfo.enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

fun Boolean.toEnabledString(): String {
    return if (this) Constants.Packages.STATE_ENABLED else Constants.Packages.STATE_DISABLED
}

/**
 * Open Android system settings page for a specific app.
 */
fun Context.openAppSettings(packageName: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
}

/**
 * Copy text to clipboard and show a toast message.
 */
fun Context.copyToClipboard(text: String, label: String = "De1984") {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
