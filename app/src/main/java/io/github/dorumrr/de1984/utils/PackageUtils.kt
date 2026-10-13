package io.github.dorumrr.de1984.utils

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

object PackageUtils {
    
    fun getPackageIcon(context: Context, packageName: String, userId: Int = 0): Drawable? {
        return try {
            val packageManager = context.packageManager
            val appInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                context, packageName, 0, userId
            )
            if (appInfo != null) {
                packageManager.getApplicationIcon(appInfo)
            } else {
                ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            }
        } catch (e: Exception) {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        }
    }
    
    /** Time sorts put the newest first and an unknown time last; ties fall back to name. */
    fun <T> sortPackages(
        packages: List<T>,
        sortBy: SortCriteria,
        name: (T) -> String,
        installTime: (T) -> Long?,
        updateTime: (T) -> Long?,
        configured: (T) -> Boolean = { false },
    ): List<T> {
        val byName = compareBy<T> { name(it).lowercase() }
        fun newestFirst(time: (T) -> Long?) =
            compareBy<T> { time(it) == null }.thenByDescending { time(it) }.then(byName)
        return when (sortBy) {
            SortCriteria.NAME -> packages.sortedWith(byName)
            SortCriteria.INSTALL_TIME -> packages.sortedWith(newestFirst(installTime))
            SortCriteria.UPDATE_TIME -> packages.sortedWith(newestFirst(updateTime))
            SortCriteria.CONFIGURED_FIRST -> packages.sortedWith(compareByDescending<T> { configured(it) }.then(byName))
        }
    }

    enum class SortCriteria {
        NAME,
        INSTALL_TIME,
        UPDATE_TIME,
        CONFIGURED_FIRST
    }
}
