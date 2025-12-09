package io.github.dorumrr.de1984.data.multiuser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import com.topjohnwu.superuser.Shell
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
    
    // User profile caching to avoid repeated expensive reflection calls
    @Volatile
    private var cachedUsers: List<UserProfile>? = null
    @Volatile
    private var usersCacheTime: Long = 0
    private const val USERS_CACHE_TTL = 30_000L // 30 seconds
    
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
     *
     * Uses multiple strategies:
     * 1. UserManager.getUserProfiles() - public API that returns profiles for the calling user
     * 2. UserManager.getUsers() - hidden API requiring MANAGE_USERS permission
     * 3. Fallback to user 0 only
     *
     * Results are cached for 30 seconds to avoid repeated expensive reflection calls.
     * Returns list with just user 0 if all methods fail.
     */
    fun getUsers(context: Context): List<UserProfile> {
        if (!initialized) initialize()

        // Check cache first to avoid expensive reflection calls
        cachedUsers?.let { cached ->
            if (System.currentTimeMillis() - usersCacheTime < USERS_CACHE_TTL) {
                AppLogger.d(TAG, "🔍 MULTI-USER: Returning cached ${cached.size} user profiles")
                return cached
            }
        }

        AppLogger.i(TAG, "🔍 MULTI-USER: Starting user profile detection...")

        // Strategy 1: Use public API UserManager.getUserProfiles() (Android 5.0+)
        // This returns profiles associated with the current user without special permissions
        try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            val profiles = userManager.userProfiles
            AppLogger.d(TAG, "🔍 MULTI-USER: UserManager.getUserProfiles() returned ${profiles.size} handles")

            if (profiles.isNotEmpty()) {
                val userProfiles = profiles.mapNotNull { userHandle ->
                    try {
                        // Get the user ID from UserHandle via reflection
                        val getIdentifierMethod = userHandle.javaClass.getMethod("getIdentifier")
                        val userId = getIdentifierMethod.invoke(userHandle) as Int

                        // Determine profile type based on userId
                        // userId 0 is always the primary user
                        // Work profiles are detected via isManagedProfile()
                        val isWorkProfile = userId > 0 && userManager.isManagedProfile(userId)

                        // Clone profiles only exist on Android 12+ (API 31)
                        // IMPORTANT: getUserProfiles() doesn't provide flags, so we can't 
                        // reliably detect clone profiles here. Strategy 2 (getUsers with flags)
                        // should be used for accurate clone detection. We conservatively
                        // set this to false to avoid misclassifying secondary users or
                        // Shelter/Island profiles as clones.
                        val isCloneProfile = false

                        val name = when {
                            userId == 0 -> "Personal"
                            isWorkProfile -> "Work"
                            // Note: Clone detection happens via Strategy 2 if available
                            else -> "User $userId"
                        }

                        val profile = UserProfile(userId, name, isWorkProfile, isCloneProfile)
                        AppLogger.d(TAG, "🔍 MULTI-USER: Detected profile: userId=$userId, name=$name, isWork=$isWorkProfile, isClone=$isCloneProfile")
                        profile
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to parse UserHandle: ${e.message}")
                        null
                    }
                }

                if (userProfiles.isNotEmpty()) {
                    AppLogger.i(TAG, "✅ MULTI-USER: Found ${userProfiles.size} user profiles via getUserProfiles(): ${userProfiles.map { "${it.userId}:${it.displayName}(work=${it.isWorkProfile},clone=${it.isCloneProfile})" }}")
                    return cacheAndReturn(userProfiles)
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "getUserProfiles() failed: ${e.message}")
        }

        // Strategy 2: Try hidden API UserManager.getUsers() (requires MANAGE_USERS permission)
        if (hiddenApiAvailable) {
            try {
                val userManager = context.getSystemService(Context.USER_SERVICE)
                val getUsersMethod = userManager!!.javaClass.getMethod("getUsers")

                @Suppress("UNCHECKED_CAST")
                val userInfoList = getUsersMethod.invoke(userManager) as? List<*>

                if (!userInfoList.isNullOrEmpty()) {
                    val profiles = userInfoList.mapNotNull { userInfo ->
                        try {
                            val idField = userInfo!!.javaClass.getField("id")
                            val nameField = userInfo.javaClass.getField("name")
                            val flagsField = userInfo.javaClass.getField("flags")

                            val id = idField.getInt(userInfo)
                            val name = nameField.get(userInfo) as? String
                            val flags = flagsField.getInt(userInfo)

                            // FLAG_MANAGED_PROFILE = 0x20 (work profile, available since Android 5.0)
                            // FLAG_CLONE_PROFILE = 0x40000000 (clone profile, Android 12+ / API 31+)
                            // Note: Before Android 12, clone profiles don't exist, so the flag will never be set
                            val isWorkProfile = (flags and 0x20) != 0
                            val isCloneProfile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                                                 (flags and 0x40000000) != 0

                            UserProfile(id, name, isWorkProfile, isCloneProfile)
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to parse UserInfo: ${e.message}")
                            null
                        }
                    }

                    if (profiles.isNotEmpty()) {
                        AppLogger.i(TAG, "✅ Found ${profiles.size} user profiles via getUsers(): ${profiles.map { "${it.userId}:${it.displayName}" }}")
                        return cacheAndReturn(profiles)
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Hidden API getUsers() failed: ${e.message}")
            }
        }

        // Strategy 3: Fallback to user 0 only
        AppLogger.d(TAG, "All user enumeration methods failed, returning only user 0")
        return cacheAndReturn(listOf(UserProfile(0, "Personal", isWorkProfile = false, isCloneProfile = false)))
    }

    /**
     * Cache the user profiles and return them.
     */
    private fun cacheAndReturn(users: List<UserProfile>): List<UserProfile> {
        cachedUsers = users
        usersCacheTime = System.currentTimeMillis()
        return users
    }

    /**
     * Clear the user profile cache. Call this when user profiles may have changed
     * (e.g., work profile added/removed).
     */
    fun clearUserCache() {
        cachedUsers = null
        usersCacheTime = 0
        AppLogger.d(TAG, "User profile cache cleared")
    }

    /**
     * Check if a user ID represents a managed profile (work profile).
     * Uses reflection to call UserManager.isManagedProfile(userId).
     */
    private fun android.os.UserManager.isManagedProfile(userId: Int): Boolean {
        return try {
            // Try the hidden isManagedProfile(int) method
            val method = this.javaClass.getMethod("isManagedProfile", Int::class.javaPrimitiveType)
            method.invoke(this, userId) as? Boolean ?: false
        } catch (e: Exception) {
            // Fallback: check if the default isManagedProfile() is true for the current user
            try {
                // Get the current user's ID via reflection (same pattern used in Strategy 1)
                val myUserHandle = android.os.Process.myUserHandle()
                val getIdentifierMethod = myUserHandle.javaClass.getMethod("getIdentifier")
                val myUserId = getIdentifierMethod.invoke(myUserHandle) as Int
                
                // For the current user, use the public isManagedProfile() API (API 30+)
                if (myUserId == userId) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        this.isManagedProfile
                    } else {
                        // Before API 30, we can't reliably determine this
                        // Return false to avoid false positives
                        false
                    }
                } else {
                    // For other users, we can't determine without hidden API access
                    // Return false to avoid misclassifying users as work profiles
                    false
                }
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    /**
     * Get installed applications for a specific user.
     *
     * Uses multiple strategies:
     * 1. Standard API for user 0
     * 2. Hidden API getInstalledApplicationsAsUser()
     * 3. Root shell: pm list packages --user X (fast, creates synthetic ApplicationInfo)
     *
     * Falls back to empty list if all methods fail.
     */
    fun getInstalledApplicationsAsUser(
        context: Context,
        flags: Int,
        userId: Int
    ): List<ApplicationInfo> {
        if (!initialized) initialize()

        // For user 0, always use standard API (most reliable)
        if (userId == 0) {
            return context.packageManager.getInstalledApplications(flags)
        }

        // Strategy 1: Try hidden API first
        if (hiddenApiAvailable) {
            try {
                val pm = context.packageManager
                val method = pm.javaClass.getMethod(
                    "getInstalledApplicationsAsUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                @Suppress("UNCHECKED_CAST")
                val apps = method.invoke(pm, flags, userId) as? List<ApplicationInfo>
                if (!apps.isNullOrEmpty()) {
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via hidden API")
                    return apps
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Hidden API getInstalledApplicationsAsUser failed for user $userId: ${e.message}")
            }
        }

        // Strategy 2: Use root shell to get package list
        // Create synthetic ApplicationInfo objects based on personal profile info
        // This is MUCH faster than calling pm dump for each package
        try {
            val packageNames = getPackageListViaShell(userId)
            if (packageNames.isNotEmpty()) {
                val apps = packageNames.mapNotNull { packageName ->
                    createSyntheticApplicationInfo(context, packageName, userId)
                }
                if (apps.isNotEmpty()) {
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via shell (synthetic)")
                    return apps
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell method failed for user $userId: ${e.message}")
        }

        AppLogger.w(TAG, "⚠️ Could not get apps for user $userId - all methods failed")
        return emptyList()
    }

    /**
     * Create a synthetic ApplicationInfo for a work profile app.
     * Uses the personal profile's ApplicationInfo as a template and adjusts the UID.
     * This is much faster than calling pm dump for each package.
     */
    private fun createSyntheticApplicationInfo(
        context: Context,
        packageName: String,
        userId: Int
    ): ApplicationInfo? {
        return try {
            // Try to get info from personal profile first (most apps are clones)
            val personalInfo = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            if (personalInfo != null) {
                // Clone the personal profile info and adjust the UID
                ApplicationInfo(personalInfo).apply {
                    // Calculate the absolute UID for this user
                    // UID = userId * 100000 + appId
                    val appId = personalInfo.uid % 100000
                    this.uid = userId * 100000 + appId
                }
            } else {
                // App only exists in work profile, create minimal info
                // Use a reasonable default UID (we'll get the real one from iptables if needed)
                ApplicationInfo().apply {
                    this.packageName = packageName
                    // Estimate UID - this may not be accurate but is good enough for display
                    this.uid = userId * 100000 + 10000 + packageName.hashCode().and(0xFFFF)
                    this.flags = 0
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Failed to create synthetic ApplicationInfo for $packageName: ${e.message}")
            null
        }
    }

    /**
     * Get package list for a user via shell command.
     * Uses libsu's cached shell to avoid spawning multiple su processes.
     */
    private fun getPackageListViaShell(userId: Int): List<String> {
        return try {
            // Use libsu's cached shell (no toast spam)
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                AppLogger.d(TAG, "No cached root shell available for user $userId")
                return emptyList()
            }

            val outputList = mutableListOf<String>()
            val result = cachedShell.newJob()
                .add("pm list packages --user $userId")
                .to(outputList)
                .exec()

            if (!result.isSuccess) {
                AppLogger.d(TAG, "Shell pm list packages failed for user $userId: exit code ${result.code}")
                return emptyList()
            }

            // Parse output: "package:com.example.app" -> "com.example.app"
            outputList
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell pm list packages failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get application info for a specific package and user.
     *
     * Uses multiple strategies:
     * 1. Standard API for user 0
     * 2. Hidden API getApplicationInfoAsUser()
     * 3. Root shell: pm dump to get basic info
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

        // For user 0, always use standard API (most reliable)
        if (userId == 0) {
            return try {
                context.packageManager.getApplicationInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        // Strategy 1: Try hidden API first
        if (hiddenApiAvailable) {
            try {
                val pm = context.packageManager
                val method = pm.javaClass.getMethod(
                    "getApplicationInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                val result = method.invoke(pm, packageName, flags, userId) as? ApplicationInfo
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                // Fall through to shell method
            }
        }

        // Strategy 2: Use root shell to get app info via pm dump
        return getApplicationInfoViaShell(context, packageName, userId)
    }

    /**
     * Get ApplicationInfo via shell command.
     * Uses libsu's cached shell to avoid spawning multiple su processes.
     * Parses pm dump output to construct ApplicationInfo.
     */
    private fun getApplicationInfoViaShell(
        context: Context,
        packageName: String,
        userId: Int
    ): ApplicationInfo? {
        return try {
            // Use libsu's cached shell (no toast spam)
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                return null
            }

            val outputList = mutableListOf<String>()
            val result = cachedShell.newJob()
                .add("pm dump $packageName --user $userId")
                .to(outputList)
                .exec()

            val output = outputList.joinToString("\n")

            // Check if package exists for this user
            if (!result.isSuccess || output.contains("Unable to find package") || output.isBlank()) {
                return null
            }

            // Parse basic info from dump output
            // We need: packageName, uid, flags, sourceDir
            val uidMatch = Regex("""userId=(\d+)""").find(output)
            val codePath = Regex("""codePath=([^\s]+)""").find(output)?.groupValues?.get(1)
            val flagsMatch = Regex("""pkgFlags=\[\s*([^\]]*)\s*\]""").find(output)

            // Calculate the absolute UID for this user
            // UID = userId * 100000 + appId
            val appId = uidMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val absoluteUid = userId * 100000 + appId

            // Determine if it's a system app
            val isSystem = flagsMatch?.groupValues?.get(1)?.contains("SYSTEM") == true

            // Create ApplicationInfo
            ApplicationInfo().apply {
                this.packageName = packageName
                this.uid = absoluteUid
                this.sourceDir = codePath ?: "/data/app/$packageName"
                this.flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0

                // Try to get the label from the personal profile version
                try {
                    val personalInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    this.labelRes = personalInfo.labelRes
                    this.nonLocalizedLabel = personalInfo.nonLocalizedLabel
                    this.icon = personalInfo.icon
                } catch (e: Exception) {
                    // Package might not exist in personal profile
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell pm dump failed for $packageName user $userId: ${e.message}")
            null
        }
    }

    /**
     * Get package info for a specific package and user.
     *
     * Uses multiple strategies:
     * 1. Standard API for user 0
     * 2. Hidden API getPackageInfoAsUser()
     * 3. Synthetic PackageInfo based on personal profile data
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

        // Strategy 1: Try hidden API first
        if (hiddenApiAvailable) {
            try {
                val pm = context.packageManager
                val method = pm.javaClass.getMethod(
                    "getPackageInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                val result = method.invoke(pm, packageName, flags, userId) as? PackageInfo
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                // Fall through to synthetic method
            }
        }

        // Strategy 2: Create synthetic PackageInfo based on personal profile data
        // Work profile apps are typically clones of personal profile apps with same permissions
        return createSyntheticPackageInfo(context, packageName, flags, userId)
    }

    /**
     * Create a synthetic PackageInfo for work profile apps.
     *
     * Work profile apps are typically clones of personal profile apps,
     * so we can use the personal profile's PackageInfo as a base and
     * adjust the UID for the work profile.
     */
    private fun createSyntheticPackageInfo(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        return try {
            // Get the personal profile's PackageInfo as a base
            val personalInfo = context.packageManager.getPackageInfo(packageName, flags)

            // Create a copy with adjusted UID for work profile
            PackageInfo().apply {
                this.packageName = personalInfo.packageName
                this.versionName = personalInfo.versionName
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    this.longVersionCode = personalInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    this.versionCode = personalInfo.versionCode
                }
                this.firstInstallTime = personalInfo.firstInstallTime
                this.lastUpdateTime = personalInfo.lastUpdateTime
                this.requestedPermissions = personalInfo.requestedPermissions
                this.requestedPermissionsFlags = personalInfo.requestedPermissionsFlags
                this.services = personalInfo.services
                this.activities = personalInfo.activities
                this.receivers = personalInfo.receivers
                this.providers = personalInfo.providers
                this.permissions = personalInfo.permissions

                // Create synthetic ApplicationInfo with correct UID
                this.applicationInfo = personalInfo.applicationInfo?.let { appInfo ->
                    ApplicationInfo(appInfo).apply {
                        val appId = appInfo.uid % 100000
                        this.uid = userId * 100000 + appId
                    }
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // Package doesn't exist in personal profile - might be work-only app
            null
        } catch (e: Exception) {
            null
        }
    }
}

