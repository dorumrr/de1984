package io.github.dorumrr.de1984.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

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

