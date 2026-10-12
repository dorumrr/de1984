package io.github.dorumrr.de1984.data.multiuser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.utils.Constants
import android.os.Build
import android.os.UserHandle
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.ShellRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    @Volatile
    private var shizukuManager: ShizukuManager? = null

    /**
     * Set the ShizukuManager reference for Shizuku shell fallback.
     * Must be called after dependencies are initialized in De1984Application.
     */
    fun setShizukuManager(manager: ShizukuManager) {
        shizukuManager = manager
        AppLogger.d(TAG, "ShizukuManager reference set for work profile shell fallback")
    }

    /** Moved on by [clearUserCache]: a user list, app list or sweep begun before it is returned but never cached. */
    private val cacheGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Moved on by [clearInstalledAppsCache]: an app list read before it is never written back to the app cache. */
    private val appsGeneration = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var cachedUsers: List<UserProfile>? = null
    @Volatile
    private var usersCacheTime: Long = 0
    private const val USERS_CACHE_TTL = 30_000L

    /**
     * Guards [installedAppsCache].
     *
     * @Volatile alone was not enough: it publishes the map REFERENCE, not the map's contents, and
     * three receivers plus PackageMonitoringService's poll all reach `.clear()` from their own
     * threads while a sweep is writing entries.
     *
     * ONLY ever taken on its own, never while holding [networkPackagesLock]. The reverse nesting is
     * real - sweepPackagesWithNetworkPermissions holds that lock and calls
     * getInstalledApplicationsAsUser underneath it - so taking them the other way round here would
     * be a deadlock.
     */
    private val installedAppsLock = Any()

    /** Keyed by user and flags: a list read with MATCH_UNINSTALLED_PACKAGES must never answer a plain read. */
    /** Each entry keeps its own read time: a shared one let one user's fresh read keep another's old list alive. */
    private var installedAppsCache: MutableMap<Pair<Int, Int>, Pair<Long, List<ApplicationInfo>>> = mutableMapOf()
    // Deliberately short, and NOT raised despite the cost of what it guards.
    //
    // The receivers below it invalidate on package add, change and removal, but only for the user
    // De1984 is installed in. Verified on hardware: installing a package into the work profile
    // (user 10) while De1984 is installed only in user 0 fires no receiver at all, so a work-profile
    // install is caught by this TTL and nothing else. Raising it to 60 s made that blind spot twelve
    // times longer - a newly installed work-profile app would go unblocked for a minute.
    //
    // It buys nothing anyway: AndroidPackageDataSource.loadPackagesInternal clears these caches on
    // every package-list load, and a rule toggle triggers one, so the cache is wiped microseconds
    // before the firewall needs it regardless of the TTL. Fixing that properly needs work-profile
    // aware invalidation, not a longer window.
    private const val INSTALLED_APPS_CACHE_TTL = 5_000L

    /**
     * Its own window, deliberately longer than [INSTALLED_APPS_CACHE_TTL].
     *
     * Measured on hardware 2026-08-25: at a cold start the UI sweep begins at T and the firewall's
     * begins at T+6.58s, so a 5-second window expired before the second sweep could reuse anything -
     * `0 hit / 731 miss`. During a rule change the same two sweeps are 0.4s apart. One constant
     * cannot serve both, so this one is sized for the wider gap.
     *
     * Longer is safe here in a way it is NOT for the installed-apps list, because this cache is keyed
     * by package name. A NEWLY installed package is a new key and therefore always a miss and always
     * fetched fresh - it can never be served stale. Only a package whose permissions or services
     * change IN PLACE can go stale, which means an app update, and that fires PackageChangedReceiver
     * -> clearInstalledAppsCache() -> this map is dropped. The known gap is the work profile, whose
     * package events reach neither receiver; there an in-place permission change is invisible for at
     * most one window.
     */
    private const val PACKAGE_INFO_CACHE_TTL = 30_000L

    /** Guards [packageInfoCache]. Never taken while holding [networkPackagesLock]. */
    private val packageInfoLock = Any()

    /** "userId:flags:packageName" -> PackageInfo, valid for one [PACKAGE_INFO_CACHE_TTL] window. */
    private val packageInfoCache = mutableMapOf<String, PackageInfo?>()

    @Volatile
    private var packageInfoCacheTime: Long = 0

    /**
     * Hit/miss counters for [packageInfoCache], reported once per network-permission sweep.
     *
     * A cache whose hit rate is invisible is a cache nobody can tell is broken. This one exists to
     * be shared between two sweeps that may or may not fall inside the same TTL window, and whether
     * they do depends on timing that varies between a cold start and a rule change - so the hit rate
     * is the only honest way to know it is working.
     */
    private var packageInfoHits = 0

    private var packageInfoMisses = 0

    // Which packages request a network permission. Shares the installed-apps TTL and invalidation,
    // because the answer changes only when a package is installed or removed.
    @Volatile
    private var networkPackagesCache: List<ApplicationInfo>? = null
    @Volatile
    private var networkPackagesCacheTime: Long = 0
    @Volatile
    private var networkPackagesGeneration: Int = -1
    private val networkPackagesLock = Any()
    
    data class UserProfile(
        val userId: Int,
        val name: String?,
        val isWorkProfile: Boolean,
        val isCloneProfile: Boolean,
        /** A separate user (Android multi-user, MIUI second space), not a profile of De1984's user. */
        val isFullUser: Boolean = false
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
     * A reflection failure in words that are actually useful.
     *
     * `InvocationTargetException.message` is **null** - the reason lives in `cause`. Logging
     * `e.message` therefore printed a bare "null" for every hidden-API failure on this device, which
     * is how `getInstalledApplicationsAsUser` came to be described as "flaky" for months: it was
     * failing on every single call and nothing said why.
     */
    private fun describeReflectionFailure(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val name = root.javaClass.simpleName
        val message = root.message
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    private const val PERM_INTERACT_ACROSS_USERS = "android.permission.INTERACT_ACROSS_USERS"

    /** Once true, stays true - a granted permission is not taken away behind our back. */
    @Volatile
    private var crossUserGranted = false

    /** Guards against piling up grant attempts while one is already running. */
    @Volatile
    private var grantInFlight = false

    /** When the last attempt started, so a failure retries later instead of latching forever. */
    @Volatile
    private var lastGrantAttempt = 0L

    /**
     * Long enough that a hot loop over hundreds of packages costs one attempt, short enough that a
     * user who enables root or Shizuku mid-session gets the benefit within the same sitting.
     */
    private const val GRANT_RETRY_INTERVAL_MS = 30_000L

    /** Its own scope: the grant must never run on whatever thread happened to ask. */
    private val grantScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Give ourselves permission to ask Android about other profiles.
     *
     * `getInstalledApplicationsAsUser` is rejected without it - verified on hardware 2026-08-28,
     * `ComputerEngine.enforceCrossUserPermission`, on every single call. The shell fallback that
     * covers for it returns names and uids only, so everything else about a work-profile app is
     * copied from the personal profile's copy of that app.
     *
     * `INTERACT_ACROSS_USERS` is `signature|privileged|development`, and the **development** flag is
     * what makes this possible: a shell running as root, or Shizuku, may grant it. A normal install
     * cannot, and does not need to - every caller already falls back.
     *
     * **Returns immediately, always.** The grant runs on [grantScope], never on the caller's thread.
     * This is called from `getInstalledApplicationsAsUser`, and `PackageMonitoringService.
     * startMonitoring` reaches that synchronously from `onStartCommand` - which Android runs on the
     * MAIN thread. A blocking `pm grant` there is an ANR on first launch.
     *
     * **A failure is not latched.** The first version set an "attempted" flag before trying, and
     * `Shell.getCachedShell()` is null until RootManager has actually opened a root shell - so at
     * boot the one attempt was skipped and the process never tried again. It now retries every
     * [GRANT_RETRY_INTERVAL_MS], which also covers root being re-enabled in Magisk or Shizuku being
     * started while the app is running. MainActivity already re-checks for exactly those.
     *
     * The caller does not wait for the result and does not need to: this pass falls back exactly as
     * before, and the next one benefits.
     */
    private fun ensureCrossUserPermission(context: Context) {
        if (hasCrossUserPermission(context)) return

        if (grantInFlight) return
        val now = System.currentTimeMillis()
        if (now - lastGrantAttempt < GRANT_RETRY_INTERVAL_MS) return

        lastGrantAttempt = now
        grantInFlight = true

        grantScope.launch {
            try {
                grantMutex.withLock { if (!hasCrossUserPermission(context)) attemptCrossUserGrant(context) }
            } finally {
                grantInFlight = false
            }
        }
    }

    /** One grant attempt, same as [ensureCrossUserPermission], but the caller waits for it. Never on the main thread. */
    fun awaitCrossUserPermission(context: Context) {
        if (hasCrossUserPermission(context)) return
        runBlocking { grantMutex.withLock { if (!hasCrossUserPermission(context)) attemptCrossUserGrant(context) } }
    }

    private val grantMutex = Mutex()

    private fun hasCrossUserPermission(context: Context): Boolean {
        if (crossUserGranted) return true
        if (context.checkSelfPermission(PERM_INTERACT_ACROSS_USERS) == PackageManager.PERMISSION_GRANTED) {
            crossUserGranted = true
            return true
        }
        return false
    }

    private suspend fun attemptCrossUserGrant(context: Context) {
        // --user matters: pm grant otherwise targets the shell's own user, and De1984 is not
        // always installed in user 0.
        val myUserId = Constants.Firewall.ownUserId()
        val command =
            "pm grant --user $myUserId ${context.packageName} $PERM_INTERACT_ACROSS_USERS"

        val cachedShell = Shell.getCachedShell()
        if (cachedShell != null && cachedShell.isRoot) {
            // Bounded, and no catch: ShellRunner reports every libsu failure as null and
            // logs the root cause. The `catch (e: Exception) {}` this replaces also
            // swallowed the cancellation of grantScope.
            val result = ShellRunner.bounded(
                label = "root grant: $command",
                timeoutMs = ShellRunner.READ_TIMEOUT_MS,
                onAbandon = RootManager::closeRootShellIfIdle
            ) {
                cachedShell.newJob().add(command).exec()
            }
            if (result != null && result.isSuccess) {
                crossUserGranted = true
                AppLogger.i(TAG, "✅ Cross-user permission granted via root")
                return
            }
            AppLogger.d(TAG, "Root pm grant failed: exit ${result?.code ?: "did not finish"}")
        }

        val manager = shizukuManager
        if (manager != null && manager.hasShizukuPermission) {
            try {
                val (exitCode, _) = manager.executeShellCommand(command)
                if (exitCode == 0) {
                    crossUserGranted = true
                    AppLogger.i(TAG, "✅ Cross-user permission granted via Shizuku")
                    return
                }
                AppLogger.d(TAG, "Shizuku pm grant failed: exit $exitCode")
            } catch (e: Exception) {
                AppLogger.d(TAG, "Shizuku pm grant threw: ${e.message}")
            }
        }

        AppLogger.d(TAG, "Cross-user permission not granted - using the shell fallback, will retry")
    }

    fun initialize() {
        if (initialized) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
    
    fun getUsers(context: Context): List<UserProfile> {
        if (!initialized) initialize()
        val generation = cacheGeneration.get()

        cachedUsers?.let { cached ->
            if (System.currentTimeMillis() - usersCacheTime < USERS_CACHE_TTL) {
                AppLogger.d(TAG, "🔍 MULTI-USER: Returning cached ${cached.size} user profiles")
                return cached
            }
        }

        AppLogger.i(TAG, "🔍 MULTI-USER: Starting user profile detection...")

        try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            val profiles = userManager.userProfiles
            AppLogger.d(TAG, "🔍 MULTI-USER: UserManager.getUserProfiles() returned ${profiles.size} handles")

            if (profiles.isNotEmpty()) {
                val userProfiles = profiles.mapNotNull { userHandle ->
                    try {
                        val getIdentifierMethod = userHandle.javaClass.getMethod("getIdentifier")
                        val userId = getIdentifierMethod.invoke(userHandle) as Int

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
                    return withFullUsers(context, userProfiles, profilesRead = true, generation)
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "getUserProfiles() failed: ${e.message}")
        }

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
                        return withFullUsers(context, profiles, profilesRead = true, generation)
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Hidden API getUsers() failed: ${describeReflectionFailure(e)}")
            }
        }

        val ownUserId = Constants.Firewall.ownUserId()
        AppLogger.d(TAG, "All user enumeration methods failed, returning only De1984's own user $ownUserId")
        return withFullUsers(context, listOf(UserProfile(ownUserId, null, isWorkProfile = false, isCloneProfile = false)), profilesRead = false, generation)
    }

    /** False after [getUsers] fell back to De1984's own user: a rule for any other user then keeps its stored uid. */
    @Volatile
    var profilesRead: Boolean = true
        private set

    /** Called with the ids of profiles or full users that are gone, or re-created under the same id. */
    @Volatile
    var onUsersRemoved: ((Set<Int>) -> Unit)? = null

    private val fullUsersLock = Any()

    private fun withFullUsers(context: Context, profiles: List<UserProfile>, profilesRead: Boolean, generation: Int): List<UserProfile> {
        this.profilesRead = profilesRead
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        if (profilesRead) rememberProfiles(context, prefs, profiles)
        if (!prefs.getBoolean(Constants.Settings.KEY_INCLUDE_OTHER_USERS, Constants.Settings.DEFAULT_INCLUDE_OTHER_USERS)) {
            return cacheAndReturn(profiles, generation)
        }
        // The shell must never run on the main thread; there the last good list stands in, uncached.
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val listed = if (onMain) null else listFullUsersViaShell()?.filter { user -> profiles.none { it.userId == user.userId } }
        val full = listed?.also { rememberFullUsers(prefs, it, profiles) } ?: knownFullUsers(prefs)
            .filter { user -> profiles.none { it.userId == user.userId } }
        if (full.isNotEmpty()) AppLogger.i(TAG, "MULTI-USER: other full users ${if (listed != null) "listed" else "remembered"}: ${full.map { it.userId }}")
        val users = profiles + full
        return if (onMain && listed == null) users else cacheAndReturn(users, generation)
    }

    /** Full users from `pm list users` through root or Shizuku, or null when neither answered. */
    private fun listFullUsersViaShell(): List<UserProfile>? {
        val all = listUsersViaShell() ?: return null
        return all
            // Android 10 and older do not mark full users with FLAG_FULL; there, not being a profile is enough.
            .filter { (_, _, flags) ->
                flags and (FLAG_PROFILE or FLAG_MANAGED_PROFILE) == 0 &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || flags and FLAG_FULL != 0)
            }
            .map { (id, name, _) -> UserProfile(id, name, isWorkProfile = false, isCloneProfile = false, isFullUser = true) }
    }

    /** Every user and profile as (id, name, flags) from `pm list users`, or null when the read failed. */
    private fun listUsersViaShell(): List<Triple<Int, String, Int>>? {
        val lines = rootShellLines("pm list users") ?: shizukuShellLines("pm list users") ?: return null
        val pattern = Regex("""UserInfo\{(\d+):(.*):([0-9a-fA-F]+)\}""")
        val all = lines.mapNotNull { line ->
            val match = pattern.find(line) ?: return@mapNotNull null
            val (id, name, flags) = match.destructured
            Triple(id.toInt(), name, flags.toLong(16).toInt())
        }
        // Every device lists at least one user, so none parsed is a failed read, not an empty device.
        return all.ifEmpty { null }
    }

    private fun knownFullUsers(prefs: android.content.SharedPreferences): List<UserProfile> =
        prefs.getStringSet(Constants.Settings.KEY_KNOWN_FULL_USERS, emptySet()).orEmpty().mapNotNull { entry ->
            val id = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            UserProfile(id, entry.substringAfter(':', ""), isWorkProfile = false, isCloneProfile = false, isFullUser = true)
        }.sortedBy { it.userId }

    private fun rememberFullUsers(prefs: android.content.SharedPreferences, listed: List<UserProfile>, profiles: List<UserProfile>) {
        val removed = synchronized(fullUsersLock) {
            val before = knownFullUsers(prefs).map { it.userId }.toSet()
            prefs.edit().putStringSet(
                Constants.Settings.KEY_KNOWN_FULL_USERS,
                listed.map { "${it.userId}:${it.name.orEmpty()}" }.toSet()
            ).commit()
            // A user of De1984's own profile group is never "gone": its rules are the ones in force here.
            before - listed.map { it.userId }.toSet() - profiles.map { it.userId }.toSet()
        }
        if (removed.isNotEmpty()) {
            AppLogger.i(TAG, "Full users gone from a successful listing: $removed")
            onUsersRemoved?.invoke(removed)
        }
    }

    private fun rememberProfiles(context: Context, prefs: android.content.SharedPreferences, profiles: List<UserProfile>) {
        // "id:creationTime": a profile deleted and re-created under the same id has a new creation time.
        val now = profiles.associate { it.userId to profileCreationTime(context, it.userId) }
        val before = prefs.getStringSet(Constants.Settings.KEY_KNOWN_PROFILES, null)?.mapNotNull { entry ->
            entry.substringBefore(':').toIntOrNull()?.let { it to entry.substringAfter(':', "").toLongOrNull() }
        }?.toMap()
        if (before == now) return
        val replaced = before.orEmpty().filter { (id, created) -> created != null && id in now && now[id] != null && now[id] != created }.keys
        val missing = before.orEmpty().keys - now.keys
        // getUserProfiles hides a locked private space, so only a user the full shell list also lacks is gone.
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val allUsers = if (missing.isEmpty() || onMain) null else listUsersViaShell()?.map { it.first }?.toSet()
        val gone = if (allUsers == null) emptySet() else missing - allUsers
        val kept = before.orEmpty().filterKeys { it in missing - gone }
        synchronized(fullUsersLock) {
            prefs.edit().putStringSet(
                Constants.Settings.KEY_KNOWN_PROFILES,
                (now + kept).map { (id, created) -> "$id:${created ?: ""}" }.toSet()
            ).apply()
        }
        val removed = gone + replaced
        if (removed.isNotEmpty()) {
            AppLogger.i(TAG, "Profiles gone from a successful read: $removed")
            onUsersRemoved?.invoke(removed)
        }
    }

    private fun profileCreationTime(context: Context, userId: Int): Long? = try {
        val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        userManager.getUserCreationTime(UserHandle.getUserHandleForUid(userId * 100000)).takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    private const val FLAG_FULL = 0x400
    private const val FLAG_PROFILE = 0x1000
    private const val FLAG_MANAGED_PROFILE = 0x20

    private fun cacheAndReturn(users: List<UserProfile>, generation: Int): List<UserProfile> {
        synchronized(cacheGeneration) {
            if (generation == cacheGeneration.get()) {
                cachedUsers = users
                usersCacheTime = System.currentTimeMillis()
            }
        }
        return users
    }

    fun clearUserCache() {
        synchronized(cacheGeneration) { cacheGeneration.incrementAndGet() }
        cachedUsers = null
        usersCacheTime = 0
        AppLogger.d(TAG, "User profile cache cleared")
    }

    private fun android.os.UserManager.isManagedProfile(userId: Int): Boolean {
        return try {
            val method = this.javaClass.getMethod("isManagedProfile", Int::class.javaPrimitiveType)
            method.invoke(this, userId) as? Boolean ?: false
        } catch (e: Exception) {
            try {
                val myUserHandle = android.os.Process.myUserHandle()
                val getIdentifierMethod = myUserHandle.javaClass.getMethod("getIdentifier")
                val myUserId = getIdentifierMethod.invoke(myUserHandle) as Int
                
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
    
    fun getInstalledApplicationsAsUser(
        context: Context,
        flags: Int,
        userId: Int
    ): List<ApplicationInfo> {
        if (!initialized) initialize()

        // The public call answers for De1984's own profile, which is not always user 0.
        if (userId == Constants.Firewall.ownUserId()) {
            return context.packageManager.getInstalledApplications(flags)
        }

        // Only for OTHER profiles: De1984's own never needed permission, and this is the first place
        // that does. Once per process, and cheap after that.
        ensureCrossUserPermission(context)

        val generation = cacheGeneration.get()
        val appsRead = appsGeneration.get()
        val now = System.currentTimeMillis()
        synchronized(installedAppsLock) {
            installedAppsCache[userId to flags]?.takeIf { (readAt, _) -> now - readAt < INSTALLED_APPS_CACHE_TTL }?.let { (_, cached) ->
                AppLogger.d(TAG, "📦 Returning cached ${cached.size} apps for user $userId")
                return cached
            }
        }

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
                    cacheInstalledApps(userId, flags, apps, generation, appsRead)
                    return apps
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Hidden API getInstalledApplicationsAsUser failed for user $userId: ${describeReflectionFailure(e)}")
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
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via root shell (synthetic)")
                    cacheInstalledApps(userId, flags, apps, generation, appsRead)
                    return apps
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Root shell method failed for user $userId: ${e.message}")
        }

        // Strategy 3: Use Shizuku shell if available (Issue #68 - work apps not showing with Shizuku)
        // This fallback enables work profile support when user has Shizuku but not root
        try {
            val packageNames = getPackageListViaShizuku(userId)
            if (packageNames.isNotEmpty()) {
                val apps = packageNames.mapNotNull { packageName ->
                    createSyntheticApplicationInfo(context, packageName, userId)
                }
                if (apps.isNotEmpty()) {
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via Shizuku shell (synthetic)")
                    cacheInstalledApps(userId, flags, apps, generation, appsRead)
                    return apps
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shizuku shell method failed for user $userId: ${e.message}")
        }

        AppLogger.w(TAG, "⚠️ Could not get apps for user $userId - all methods failed")
        return emptyList()
    }

    private fun cacheInstalledApps(userId: Int, flags: Int, apps: List<ApplicationInfo>, generation: Int, appsRead: Int) {
        synchronized(installedAppsLock) {
            if (generation != cacheGeneration.get() || appsRead != appsGeneration.get()) return
            installedAppsCache[userId to flags] = System.currentTimeMillis() to apps
        }
    }

    fun clearInstalledAppsCache() {
        // Before the clear, so a read in flight cannot write its list back after it.
        appsGeneration.incrementAndGet()
        synchronized(this) { packageUidsCache.clear() }
        synchronized(packageInfoLock) {
            packageInfoCache.clear()
            packageInfoCacheTime = 0
        }
        synchronized(installedAppsLock) {
            installedAppsCache.clear()
        }

        // Not networkPackagesLock (held for seconds; receivers call this). A sweep in flight may republish its
        // pre-clear list for one TTL; only a user-set change (clearUserCache) makes it discard the result.
        networkPackagesCache = null
        networkPackagesCacheTime = 0
        AppLogger.d(TAG, "Cleared installed apps cache")
    }

    /**
     * Per profile, every app still on the device for that user and its current uid, hidden and per-user-uninstalled
     * included; an unreadable profile maps to empty, and the whole map is empty when the profiles went unread.
     */
    fun getInstalledUids(context: Context): Map<Int, Map<String, Int>> {
        if (!initialized) initialize()
        val users = getUsers(context)
        if (!profilesRead) return emptyMap()
        val ownUserId = Constants.Firewall.ownUserId()
        // Another profile's list needs the cross-user permission; read before it lands, that profile would fall back.
        if (users.any { it.userId != ownUserId }) awaitCrossUserPermission(context)
        return users.mapNotNull { profile ->
            val apps = try {
                if (profile.userId == ownUserId) {
                    context.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                } else if (hiddenApiAvailable) {
                    ensureCrossUserPermission(context)
                    val method = context.packageManager.javaClass.getMethod(
                        "getInstalledApplicationsAsUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    )
                    @Suppress("UNCHECKED_CAST")
                    method.invoke(context.packageManager, PackageManager.MATCH_UNINSTALLED_PACKAGES, profile.userId)
                        as? List<ApplicationInfo>
                } else {
                    null
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Installed uids unreadable for user ${profile.userId}: ${e.message}")
                null
            }
            profile.userId to apps.orEmpty().associate { it.packageName to it.uid }
        }.toMap()
    }

    /**
     * Every installed app, across every user profile, that requests a network permission. An app whose
     * details cannot be read is included, so Block All blocks it instead of leaving it out.
     *
     * Empty means a profile could not be read, even after one retry on fresh caches: no device has zero
     * such apps, so a caller must treat it as a failed read, never as "nothing to block".
     */
    fun getPackagesWithNetworkPermissions(context: Context): List<ApplicationInfo> {
        // Another profile's app details need the cross-user permission; a read before it lands finds them unreadable.
        if (getUsers(context).any { it.userId != Constants.Firewall.ownUserId() }) awaitCrossUserPermission(context)
        sweepPackagesWithNetworkPermissions(context).let { if (it.isNotEmpty()) return it }
        AppLogger.w(TAG, "📦 Network package read failed - dropping the caches and reading once more")
        clearInstalledAppsCache()
        return sweepPackagesWithNetworkPermissions(context)
    }

    /**
     * All four firewall backends ran this identical filter inline, and it is the dominant cost of
     * applying rules: one `getPackageInfoAsUser` binder call per package, with no caching. Measured
     * on a two-profile device with 466 packages at roughly **8 seconds per rule application** - far
     * more than the policy writes it feeds.
     *
     * The result changes only when a package is installed or removed, so it shares
     * [INSTALLED_APPS_CACHE_TTL] and is dropped by [clearInstalledAppsCache].
     */
    private fun sweepPackagesWithNetworkPermissions(context: Context): List<ApplicationInfo> {
        val entryTime = System.currentTimeMillis()
        networkPackagesCache?.let { cached ->
            if (entryTime - networkPackagesCacheTime < INSTALLED_APPS_CACHE_TTL && networkPackagesGeneration == cacheGeneration.get()) {
                AppLogger.d(TAG, "📦 Returning cached ${cached.size} packages with network permissions")
                return cached
            }
        }

        // One computation at a time: it takes seconds (9,499 ms for 466 packages) and two backends ask at
        // startup. A waiter takes the winner's result when it is newer than the wait and of this generation.
        synchronized(networkPackagesLock) {
            networkPackagesCache?.let { cached ->
                if (networkPackagesCacheTime >= entryTime && networkPackagesGeneration == cacheGeneration.get()) {
                    AppLogger.d(TAG, "📦 Reusing ${cached.size} packages computed while waiting")
                    return cached
                }
            }

        val startTime = System.currentTimeMillis()
        val generation = cacheGeneration.get()
        val appsByUser = getUsers(context).map { profile ->
            profile.userId to getInstalledApplicationsAsUser(context, APP_LIST_FLAGS, profile.userId).filter(::isInstalledForUser)
        }
        // A profile always has installed system apps, so empty is a failed read. Dropping that profile would
        // leave its apps unblocked, so the whole answer is refused and not cached.
        appsByUser.firstOrNull { (_, apps) -> apps.isEmpty() }?.let { (userId, _) ->
            AppLogger.w(TAG, "📦 Could not read apps for user $userId - refusing a partial network package list")
            return emptyList()
        }
        val packages = appsByUser.flatMap { (userId, apps) ->
            apps.map { appInfo -> appInfo to userId }
        }.filter { (appInfo, userId) ->
            try {
                // GET_SERVICES is not read here. It is requested so this shares a cache entry
                // with AndroidPackageDataSource.getPackageMetadataBatch, which asks for both and
                // sweeps the same packages moments earlier or later. Different flags would mean
                // different cache keys and both sweeps would pay the full binder cost again.
                val packageInfo = getPackageInfoAsUser(
                    context,
                    appInfo.packageName,
                    PACKAGE_DETAIL_FLAGS,
                    userId
                ) ?: return@filter true // Unreadable is not "no network permission": kept, or the app would stay unblocked.
                packageInfo.requestedPermissions?.any { permission ->
                    Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
                } ?: false
            } catch (e: Exception) {
                true
            }
        }.map { (appInfo, _) -> appInfo }

            if (generation == cacheGeneration.get()) {
                networkPackagesCache = packages
                networkPackagesCacheTime = System.currentTimeMillis()
                networkPackagesGeneration = generation
            }
            val hits = synchronized(packageInfoLock) {
                val h = packageInfoHits
                val m = packageInfoMisses
                packageInfoHits = 0
                packageInfoMisses = 0
                "$h hit / $m miss"
            }
            AppLogger.d(TAG, "📦 Found ${packages.size} packages with network permissions " +
                    "in ${System.currentTimeMillis() - startTime}ms (getPackageInfo cache: $hits)")
            return packages
        }
    }

    private fun createSyntheticApplicationInfo(
        context: Context,
        packageName: String,
        userId: Int
    ): ApplicationInfo? {
        return try {
            // Query the enabled state for this specific user (not from personal profile!)
            val isEnabled = isPackageEnabledForUser(packageName, userId)

            val personalInfo = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            if (personalInfo != null) {
                ApplicationInfo(personalInfo).apply {
                    val appId = personalInfo.uid % 100000
                    this.uid = userId * 100000 + appId
                    this.enabled = isEnabled
                    // Listed for this user, so installed there, whatever the personal copy says.
                    this.flags = this.flags or ApplicationInfo.FLAG_INSTALLED
                }
            } else {
                // App exists only in this profile, so there is no personal-profile uid to derive
                // from. Ask the shell for the real one; it answers for the whole user in a single
                // call and the result is cached.
                //
                // The fallback is deliberately NOT a plausible app uid. This used to be
                // `userId * 100000 + 10000 + packageName.hashCode().and(0xFFFF)`, which looks like a
                // real uid, lands anywhere in 10000..75535, and was handed to the firewall backends
                // as if it were one - so a rule could be written against a uid belonging to some
                // other app, or to nothing at all. UID_UNKNOWN cannot be mistaken for an app: it
                // fails Constants.Firewall.isFirewallableAppUid, so no privileged backend acts on
                // it, and iptables rejects it rather than matching something real.
                val realUid = getPackageUidsForUser(userId)[packageName]
                if (realUid == null) {
                    AppLogger.w(TAG, "No uid available for $packageName in user $userId - " +
                            "marking it UID_UNKNOWN; the firewall will not act on it")
                }
                ApplicationInfo().apply {
                    this.packageName = packageName
                    this.uid = realUid ?: UID_UNKNOWN
                    this.flags = ApplicationInfo.FLAG_INSTALLED
                    this.enabled = isEnabled
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Failed to create synthetic ApplicationInfo for $packageName: ${e.message}")
            null
        }
    }

    /**
     * A uid that is not, and cannot be mistaken for, an installed app.
     *
     * Used when the real uid cannot be determined. It fails
     * [io.github.dorumrr.de1984.utils.Constants.Firewall.isFirewallableAppUid], so the privileged
     * backends skip it instead of writing a rule against a number that belongs to someone else.
     */
    const val UID_UNKNOWN = -1

    /**
     * Every package-detail read uses these, so they share one cache entry. MATCH_UNINSTALLED_PACKAGES so a
     * hidden app is read too; an app uninstalled for the user is left out by [isInstalledForUser], not here.
     */
    const val PACKAGE_DETAIL_FLAGS =
        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.MATCH_UNINSTALLED_PACKAGES

    /** App-list reads that must include hidden apps, filtered with [isInstalledForUser]. */
    const val APP_LIST_FLAGS = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES

    /** False for an app removed for this user (pm uninstall --user, or -k); true for a hidden or disabled one. */
    fun isInstalledForUser(app: ApplicationInfo): Boolean = app.flags and ApplicationInfo.FLAG_INSTALLED != 0

    private val packageUidsCache = mutableMapOf<Int, Map<String, Int>>()

    /**
     * Record packageName -> uid from a `pm list packages -U` output.
     *
     * Filled as a side effect of the package listing that already runs, so resolving a work-only
     * app's uid costs no extra shell call. That matters: getApplicationInfoAsUser is reached on the
     * main thread on some paths, and a second blocking command there is what this avoids.
     */
    @Synchronized
    private fun recordPackageUids(userId: Int, lines: List<String>) {
        val map = lines.mapNotNull { line ->
            if (!line.startsWith("package:")) return@mapNotNull null
            val body = line.removePrefix("package:").trim()
            val uid = body.substringAfterLast("uid:", "").trim().toIntOrNull() ?: return@mapNotNull null
            val name = body.substringBefore(" uid:").trim()
            if (name.isEmpty()) null else name to uid
        }.toMap()

        if (map.isNotEmpty()) {
            packageUidsCache[userId] = map
        }
    }

    /** packageName -> uid for one user, or empty when no listing has been read yet. */
    @Synchronized
    private fun getPackageUidsForUser(userId: Int): Map<String, Int> = packageUidsCache[userId] ?: emptyMap()

    private fun getPackageListViaShell(userId: Int): List<String> =
        rootShellLines("pm list packages -U --user $userId")?.let { packageNamesFrom(userId, it) } ?: emptyList()

    private fun getPackageListViaShizuku(userId: Int): List<String> =
        shizukuShellLines("pm list packages -U --user $userId")?.let { packageNamesFrom(userId, it) } ?: emptyList()

    /** -U output, so this one call yields the uids too - see recordPackageUids. */
    private fun packageNamesFrom(userId: Int, lines: List<String>): List<String> {
        recordPackageUids(userId, lines)
        return lines
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").substringBefore(" uid:").trim() }
            .filter { it.isNotEmpty() }
    }

    /** One command through the cached root shell: its output, or null when there is none or it failed. */
    private fun rootShellLines(command: String): List<String>? {
        return try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                AppLogger.d(TAG, "No cached root shell available for: $command")
                return null
            }

            val outputList = mutableListOf<String>()
            // Bounded: this path demonstrably runs on the main thread, so a wedged shell was an ANR.
            val result = runBlocking {
                ShellRunner.bounded(
                    label = "root shell: $command",
                    timeoutMs = ShellRunner.READ_TIMEOUT_MS,
                    onAbandon = RootManager::closeRootShellIfIdle
                ) {
                    cachedShell.newJob().add(command).to(outputList).exec()
                }
            }

            when {
                result == null -> { AppLogger.d(TAG, "Root shell did not finish: $command"); null }
                !result.isSuccess -> { AppLogger.d(TAG, "Root shell failed (exit ${result.code}): $command"); null }
                else -> outputList
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Root shell failed: $command: ${e.message}")
            null
        }
    }

    /** One command through Shizuku: its output, or null when Shizuku is not usable or the command failed. */
    private fun shizukuShellLines(command: String): List<String>? {
        val manager = shizukuManager
        if (manager == null || !manager.hasShizukuPermission) {
            AppLogger.d(TAG, "Shizuku not usable for: $command")
            return null
        }

        return try {
            val (exitCode, output) = runBlocking { manager.executeShellCommand(command) }
            if (exitCode != 0) {
                AppLogger.d(TAG, "Shizuku shell failed (exit $exitCode): $command")
                null
            } else {
                output.lines()
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shizuku shell failed: $command: ${e.message}")
            null
        }
    }

    /**
     * Guards [disabledPackagesCache]. It used to have none, and the only reader was the UI sweep.
     * PackageMonitoringService now polls it from its own IO thread to notice an enable or disable
     * made in another profile, so two threads reach this map.
     */
    private val disabledPackagesLock = Any()

    private val disabledPackagesCache = mutableMapOf<Int, Set<String>>()

    /**
     * The disabled set for one profile, straight from the shell, with no caching either way.
     *
     * Returns **null when the query failed**, which is deliberately not the same value as an empty
     * set. [getDisabledPackagesForUser] collapses the two because a caller asking "is this package
     * enabled" has to answer something. [readDisabledPackagesFresh] must NOT collapse them: a failed
     * read that looked like "nothing is disabled" would be reported as every disabled app having
     * just been enabled.
     */
    private fun queryDisabledPackages(userId: Int): Set<String>? {
        return try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                // Fall back to Shizuku instead of giving up. Root was the ONLY route here, so on a
                // Shizuku-only device this returned an empty set - indistinguishable from "nothing is
                // disabled". Every work-profile app then showed as Enabled, and the detail sheet
                // offered "Disable" for apps that were already disabled.
                //
                // The same shell already lists packages for other profiles a few lines above, so the
                // capability was there and simply unused for this one query.
                AppLogger.d(TAG, "No cached root shell for disabled packages (user $userId) - trying Shizuku")
                val viaShizuku = getDisabledPackagesViaShizuku(userId)
                if (viaShizuku != null) {
                    AppLogger.d(TAG, "Found ${viaShizuku.size} disabled packages for user $userId via Shizuku")
                    return viaShizuku
                }
                AppLogger.d(TAG, "Could not determine disabled packages for user $userId")
                return null
            }

            val outputList = mutableListOf<String>()
            val result = runBlocking {
                ShellRunner.bounded(
                    label = "root shell: pm list packages -d --user $userId",
                    timeoutMs = ShellRunner.READ_TIMEOUT_MS,
                    onAbandon = RootManager::closeRootShellIfIdle
                ) {
                    cachedShell.newJob()
                        .add("pm list packages -d --user $userId")
                        .to(outputList)
                        .exec()
                }
            }

            // null is NOT an empty set here, and the difference matters: this function's contract is
            // that null means "could not read", which readDisabledPackagesFresh must not confuse
            // with "nothing is disabled".
            if (result == null) {
                AppLogger.d(TAG, "Root shell pm list packages -d did not finish for user $userId")
                return null
            }

            if (!result.isSuccess) {
                AppLogger.d(TAG, "Shell pm list packages -d failed for user $userId: exit code ${result.code}")
                return null
            }

            val disabledSet = outputList
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            AppLogger.d(TAG, "Found ${disabledSet.size} disabled packages for user $userId")
            disabledSet
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell pm list packages -d failed: ${e.message}")
            null
        }
    }

    /**
     * The disabled set for one profile, cached until [clearDisabledPackagesCache].
     *
     * A failed query is cached as an empty set on purpose. This is called once per PACKAGE from
     * createSyntheticApplicationInfo (~466 of them on the test device); without caching the negative
     * every one of them would retry the shell, each with its own timeout, turning a cheap path into
     * a very expensive one.
     */
    private fun getDisabledPackagesForUser(userId: Int): Set<String> {
        synchronized(disabledPackagesLock) { disabledPackagesCache[userId]?.let { return it } }

        val result = queryDisabledPackages(userId) ?: emptySet()
        synchronized(disabledPackagesLock) { disabledPackagesCache[userId] = result }
        return result
    }

    /**
     * Re-reads one profile's disabled set, ignoring whatever is cached, and refreshes the cache with
     * what it finds. Null means the read failed and the caller must not treat that as a change.
     *
     * This exists for PackageMonitoringService. ACTION_PACKAGE_CHANGED only ever reaches De1984's own profile, so
     * an app enabled or disabled in a work profile by some other app is invisible to De1984 until
     * something unrelated forces a refresh (issue #61). One `pm list packages -d --user N` per
     * profile is cheap enough to poll; asking per package would not be.
     */
    fun readDisabledPackagesFresh(userId: Int): Set<String>? {
        val fresh = queryDisabledPackages(userId) ?: return null
        synchronized(disabledPackagesLock) { disabledPackagesCache[userId] = fresh }
        return fresh
    }

    private fun getDisabledPackagesViaShizuku(userId: Int): Set<String>? {
        val manager = shizukuManager ?: return null
        if (!manager.hasShizukuPermission) return null

        return try {
            val (exitCode, output) = runBlocking {
                manager.executeShellCommand("pm list packages -d --user $userId")
            }
            if (exitCode != 0) {
                AppLogger.d(TAG, "Shizuku pm list packages -d failed for user $userId: exit $exitCode")
                return null
            }
            output.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shizuku pm list packages -d threw for user $userId: ${e.message}")
            null
        }
    }

    private fun isPackageEnabledForUser(packageName: String, userId: Int): Boolean {
        val disabledPackages = getDisabledPackagesForUser(userId)
        return !disabledPackages.contains(packageName)
    }

    fun clearDisabledPackagesCache() {
        synchronized(disabledPackagesLock) { disabledPackagesCache.clear() }
        AppLogger.d(TAG, "Cleared disabled packages cache")
    }

    fun getApplicationInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): ApplicationInfo? {
        if (!initialized) initialize()

        if (userId == Constants.Firewall.ownUserId()) {
            return try {
                context.packageManager.getApplicationInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        // Same permission, same reason as getInstalledApplicationsAsUser. Cheap after the first
        // call - a volatile read once granted - and it never blocks, so it is safe on a hot path.
        ensureCrossUserPermission(context)

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
            }
        }

        return getApplicationInfoViaShell(context, packageName, userId)
    }

    private fun getApplicationInfoViaShell(
        context: Context,
        packageName: String,
        userId: Int
    ): ApplicationInfo? {
        return try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                return null
            }

            val outputList = mutableListOf<String>()
            val result = runBlocking {
                ShellRunner.bounded(
                    label = "root shell: pm dump $packageName --user $userId",
                    timeoutMs = ShellRunner.READ_TIMEOUT_MS,
                    onAbandon = RootManager::closeRootShellIfIdle
                ) {
                    cachedShell.newJob()
                        .add("pm dump $packageName --user $userId")
                        .to(outputList)
                        .exec()
                }
            } ?: return null

            val output = outputList.joinToString("\n")

            if (!result.isSuccess || output.contains("Unable to find package") || output.isBlank()) {
                return null
            }

            val uidMatch = Regex("""userId=(\d+)""").find(output)
            val codePath = Regex("""codePath=([^\s]+)""").find(output)?.groupValues?.get(1)
            val flagsMatch = Regex("""pkgFlags=\[\s*([^\]]*)\s*\]""").find(output)

            val appId = uidMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val absoluteUid = userId * 100000 + appId

            val isSystem = flagsMatch?.groupValues?.get(1)?.contains("SYSTEM") == true

            val isEnabled = isPackageEnabledForUser(packageName, userId)

            ApplicationInfo().apply {
                this.packageName = packageName
                this.uid = absoluteUid
                this.sourceDir = codePath ?: "/data/app/$packageName"
                this.flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
                this.enabled = isEnabled

                try {
                    val personalInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    this.labelRes = personalInfo.labelRes
                    this.nonLocalizedLabel = personalInfo.nonLocalizedLabel
                    this.icon = personalInfo.icon
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell pm dump failed for $packageName user $userId: ${e.message}")
            null
        }
    }

    /**
     * One `getPackageInfoAsUser` per (user, package, flags) per cache window, not per caller.
     *
     * This is the single most expensive call in the app and it had no cache at all. Two independent
     * sweeps make it for every installed package moments apart: AndroidPackageDataSource's
     * getPackageMetadataBatch when the list loads, and getPackagesWithNetworkPermissions when rules
     * are applied. Measured on hardware 2026-08-25: ~2,350 ms and ~1,062 ms, 466 calls each, for a
     * device with two profiles.
     *
     * Caching HERE rather than sharing a package list between the two callers is deliberate. The two
     * lists are not the same set and must not become one: the UI sweep filters out De1984's own
     * package (`Constants.App.isOwnApp`) and the firewall's does not. Caching the binder call leaves
     * every caller's filtering, exclusions and permission list exactly as they were - only the
     * round trip is shared.
     *
     * Dropped by [clearInstalledAppsCache] with the other package caches, so a package install or
     * removal invalidates it through the same path, but it keeps its own [PACKAGE_INFO_CACHE_TTL] -
     * see that constant for why one window cannot serve both. Entries are held for at most one
     * window, which bounds the memory: the whole map is dropped the first time it is read after
     * going stale.
     *
     * `null` results are cached too. A package that is genuinely not installed for a user is a
     * stable answer for the window, and re-asking would cost the same binder call every time.
     */
    fun getPackageInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        if (userId != Constants.Firewall.ownUserId()) {
            // Same permission, same reason as getInstalledApplicationsAsUser. Cheap after the first
            // call - a volatile read once granted - and it never blocks, so it is safe on a hot path.
            ensureCrossUserPermission(context)
        }

        val key = "$userId:$flags:$packageName"

        synchronized(packageInfoLock) {
            if (System.currentTimeMillis() - packageInfoCacheTime >= PACKAGE_INFO_CACHE_TTL) {
                if (packageInfoCache.isNotEmpty()) {
                    packageInfoCache.clear()
                }
                packageInfoCacheTime = System.currentTimeMillis()
            } else if (packageInfoCache.containsKey(key)) {
                packageInfoHits++
                return packageInfoCache[key]
            }
            packageInfoMisses++
        }

        val result = fetchPackageInfoAsUser(context, packageName, flags, userId)

        synchronized(packageInfoLock) {
            packageInfoCache[key] = result
        }
        return result
    }

    private fun fetchPackageInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        if (!initialized) initialize()

        if (userId == Constants.Firewall.ownUserId()) {
            return try {
                context.packageManager.getPackageInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                AppLogger.d(TAG, "Package $packageName not found for user $userId")
                null
            }
        }

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
            }
        }

        // Strategy 2: Create synthetic PackageInfo based on personal profile data
        // Work profile apps are typically clones of personal profile apps with same permissions
        return createSyntheticPackageInfo(context, packageName, flags, userId)
    }

    private fun createSyntheticPackageInfo(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        return try {
            val personalInfo = context.packageManager.getPackageInfo(packageName, flags)

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

                this.applicationInfo = personalInfo.applicationInfo?.let { appInfo ->
                    ApplicationInfo(appInfo).apply {
                        val appId = appInfo.uid % 100000
                        this.uid = userId * 100000 + appId
                    }
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}

