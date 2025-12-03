package io.github.dorumrr.de1984.data.multiuser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import io.github.dorumrr.de1984.utils.AppLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Helper class for accessing hidden Android APIs to support multi-user/work profile functionality.
 * 
 * Uses LSPosed HiddenApiBypass library to access:
 * - UserManager.getUsers() - enumerate all user profiles
 * - PackageManager.getInstalledApplicationsAsUser() - get apps per user
 * 
 * Gracefully falls back to standard APIs if hidden APIs are unavailable.
 */
object HiddenApiHelper {
    private const val TAG = "HiddenApiHelper"
    
    private var initialized = false
    private var hiddenApiAvailable = false
    
    /**
     * Data class representing a user profile
     */
    data class UserProfile(
        val userId: Int,
        val name: String?,
        val isWorkProfile: Boolean,
        val isCloneProfile: Boolean
    ) {
        val displayName: String
            get() = when {
                userId == 0 -> "Personal"
                isWorkProfile -> "Work"
                isCloneProfile -> "Clone"
                else -> name ?: "User $userId"
            }
    }
    
    /**
     * Initialize hidden API bypass. Should be called in Application.onCreate()
     */
    fun initialize() {
        if (initialized) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Exempt all classes from hidden API restrictions
                HiddenApiBypass.addHiddenApiExemptions("L")
                hiddenApiAvailable = true
                AppLogger.i(TAG, "✅ HiddenApiBypass initialized successfully")
            } else {
                // Hidden API restrictions don't exist before Android P
                hiddenApiAvailable = true
                AppLogger.i(TAG, "✅ Pre-Android P, no hidden API bypass needed")
            }
        } catch (e: Exception) {
            hiddenApiAvailable = false
            AppLogger.e(TAG, "❌ Failed to initialize HiddenApiBypass: ${e.message}")
        }
        
        initialized = true
    }
    
    /**
     * Get all user profiles on the device.
     * Returns list with just user 0 if hidden APIs are unavailable.
     */
    fun getUsers(context: Context): List<UserProfile> {
        if (!initialized) initialize()
        
        if (!hiddenApiAvailable) {
            AppLogger.d(TAG, "Hidden APIs unavailable, returning only user 0")
            return listOf(UserProfile(0, "Personal", isWorkProfile = false, isCloneProfile = false))
        }
        
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE)
            val getUsersMethod = userManager.javaClass.getMethod("getUsers")
            
            @Suppress("UNCHECKED_CAST")
            val userInfoList = getUsersMethod.invoke(userManager) as? List<*>
            
            if (userInfoList.isNullOrEmpty()) {
                AppLogger.d(TAG, "getUsers returned empty, falling back to user 0")
                return listOf(UserProfile(0, "Personal", isWorkProfile = false, isCloneProfile = false))
            }
            
            val profiles = userInfoList.mapNotNull { userInfo ->
                try {
                    val idField = userInfo!!.javaClass.getField("id")
                    val nameField = userInfo.javaClass.getField("name")
                    val flagsField = userInfo.javaClass.getField("flags")
                    
                    val id = idField.getInt(userInfo)
                    val name = nameField.get(userInfo) as? String
                    val flags = flagsField.getInt(userInfo)
                    
                    // FLAG_MANAGED_PROFILE = 0x20 (work profile)
                    // FLAG_CLONE_PROFILE = 0x40000000 (clone profile, Android 12+)
                    val isWorkProfile = (flags and 0x20) != 0
                    val isCloneProfile = (flags and 0x40000000) != 0
                    
                    UserProfile(id, name, isWorkProfile, isCloneProfile)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to parse UserInfo: ${e.message}")
                    null
                }
            }
            
            if (profiles.isEmpty()) {
                AppLogger.d(TAG, "No profiles parsed, falling back to user 0")
                listOf(UserProfile(0, "Personal", isWorkProfile = false, isCloneProfile = false))
            } else {
                AppLogger.i(TAG, "Found ${profiles.size} user profiles: ${profiles.map { "${it.userId}:${it.displayName}" }}")
                profiles
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get users via hidden API: ${e.message}")
            listOf(UserProfile(0, "Personal", isWorkProfile = false, isCloneProfile = false))
        }
    }
    
    /**
     * Get installed applications for a specific user.
     * Falls back to standard getInstalledApplications for user 0.
     */
    fun getInstalledApplicationsAsUser(
        context: Context,
        flags: Int,
        userId: Int
    ): List<ApplicationInfo> {
        if (!initialized) initialize()
        
        // For user 0, always use standard API (more reliable)
        if (userId == 0) {
            return context.packageManager.getInstalledApplications(flags)
        }
        
        if (!hiddenApiAvailable) {
            AppLogger.d(TAG, "Hidden APIs unavailable, cannot get apps for user $userId")
            return emptyList()
        }
        
        return try {
            val pm = context.packageManager
            val method = pm.javaClass.getMethod(
                "getInstalledApplicationsAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            
            @Suppress("UNCHECKED_CAST")
            val apps = method.invoke(pm, flags, userId) as? List<ApplicationInfo> ?: emptyList()
            AppLogger.d(TAG, "Found ${apps.size} apps for user $userId")
            apps
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get apps for user $userId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get application info for a specific package and user.
     * Falls back to standard getApplicationInfo for user 0.
     *
     * @param context Application context
     * @param packageName Package name to look up
     * @param flags PackageManager flags
     * @param userId User ID (0 = personal, 10+ = work/clone profiles)
     * @return ApplicationInfo or null if not found
     */
    fun getApplicationInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): ApplicationInfo? {
        if (!initialized) initialize()

        // For user 0, always use standard API (more reliable)
        if (userId == 0) {
            return try {
                context.packageManager.getApplicationInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                AppLogger.d(TAG, "Package $packageName not found for user 0")
                null
            }
        }

        if (!hiddenApiAvailable) {
            AppLogger.d(TAG, "Hidden APIs unavailable, cannot get app info for user $userId")
            return null
        }

        return try {
            val pm = context.packageManager
            val method = pm.javaClass.getMethod(
                "getApplicationInfoAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            method.invoke(pm, packageName, flags, userId) as? ApplicationInfo
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get app info for $packageName (user $userId): ${e.message}")
            null
        }
    }

    /**
     * Get package info for a specific package and user.
     * Falls back to standard getPackageInfo for user 0.
     *
     * @param context Application context
     * @param packageName Package name to look up
     * @param flags PackageManager flags
     * @param userId User ID (0 = personal, 10+ = work/clone profiles)
     * @return PackageInfo or null if not found
     */
    fun getPackageInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        if (!initialized) initialize()

        // For user 0, always use standard API (more reliable)
        if (userId == 0) {
            return try {
                context.packageManager.getPackageInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                AppLogger.d(TAG, "Package $packageName not found for user 0")
                null
            }
        }

        if (!hiddenApiAvailable) {
            AppLogger.d(TAG, "Hidden APIs unavailable, cannot get package info for user $userId")
            return null
        }

        return try {
            val pm = context.packageManager
            val method = pm.javaClass.getMethod(
                "getPackageInfoAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            method.invoke(pm, packageName, flags, userId) as? PackageInfo
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get package info for $packageName (user $userId): ${e.message}")
            null
        }
    }
}

