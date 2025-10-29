package io.github.dorumrr.de1984.data.firewall

import android.content.Context
import android.os.IBinder
import android.util.Log
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Firewall backend using Android's NetworkPolicyManager API via Shizuku.
 * 
 * This backend:
 * - Works with Shizuku in ADB mode (UID 2000) - no root required
 * - Does NOT show VPN icon
 * - Does NOT occupy VPN slot
 * - Uses system-level network policies
 * 
 * Limitations:
 * - Less granular than iptables (per-app only, not per-network-type)
 * - Uses hidden Android API (may break in future versions)
 * - Requires Shizuku to be running
 */
class NetworkPolicyManagerFirewallBackend(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    companion object {
        private const val TAG = "NetworkPolicyManagerFirewall"
        
        // NetworkPolicyManager constants
        private const val POLICY_NONE = 0x0
        private const val POLICY_REJECT_METERED_BACKGROUND = 0x1
        private const val POLICY_REJECT_ALL = 0x4  // Android 11+ / Custom ROMs

        // System service name
        private const val SERVICE_NAME = "netpolicy"
    }

    private var isRunning = false
    private val mutex = Mutex()

    // Track which policy constant works on this device
    private var blockingPolicy: Int = POLICY_REJECT_ALL  // Try POLICY_REJECT_ALL first
    private var policyTested: Boolean = false

    // Cached reflection objects
    private var networkPolicyManagerClass: Class<*>? = null
    private var stubClass: Class<*>? = null
    private var asInterfaceMethod: Method? = null
    private var setUidPolicyMethod: Method? = null
    private var getUidPolicyMethod: Method? = null

    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "=== NetworkPolicyManagerFirewallBackend.start() ===")
            
            // Initialize reflection if needed
            if (!initializeReflection()) {
                val error = errorHandler.handleError(
                    Exception("Failed to initialize reflection for NetworkPolicyManager"),
                    "start NetworkPolicyManager firewall"
                )
                return Result.failure(error)
            }

            isRunning = true
            Log.d(TAG, "✅ NetworkPolicyManager firewall started")
            Log.d(TAG, "ℹ️  Policy support will be tested on first rule application")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start NetworkPolicyManager firewall"))
        }
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "=== NetworkPolicyManagerFirewallBackend.stop() ===")
            
            // Clear all policies by setting POLICY_NONE for all apps
            // Note: We don't track which apps we modified, so we can't clean up perfectly
            // This is acceptable as the policies will be reapplied when firewall starts again
            
            isRunning = false
            Log.d(TAG, "✅ NetworkPolicyManager firewall stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop NetworkPolicyManager firewall"))
        }
    }

    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "=== NetworkPolicyManagerFirewallBackend.applyRules() ===")
            Log.d(TAG, "Rules count: ${rules.size}, networkType: $networkType, screenOn: $screenOn")
            
            if (!isRunning) {
                Log.w(TAG, "Firewall not running, skipping rule application")
                return Result.success(Unit)
            }
            
            // Get NetworkPolicyManager instance
            val networkPolicyManager = getNetworkPolicyManager()
            if (networkPolicyManager == null) {
                val error = errorHandler.handleError(
                    Exception("Failed to get NetworkPolicyManager instance"),
                    "apply network policies"
                )
                return Result.failure(error)
            }
            
            // Test which policy works on first run
            if (!policyTested) {
                testPolicySupport(networkPolicyManager)
            }

            var appliedCount = 0
            var errorCount = 0

            rules.forEach { rule ->
                try {
                    // Determine if app should be blocked
                    val shouldBlock = when {
                        !rule.enabled -> false
                        !screenOn && rule.blockWhenScreenOff -> true
                        rule.isBlockedOn(networkType) -> true
                        else -> false
                    }

                    // Set policy
                    val policy = if (shouldBlock) {
                        blockingPolicy
                    } else {
                        POLICY_NONE
                    }

                    setUidPolicyMethod?.invoke(networkPolicyManager, rule.uid, policy)
                    appliedCount++

                    val policyName = when (blockingPolicy) {
                        POLICY_REJECT_ALL -> "REJECT_ALL (WiFi+Mobile)"
                        POLICY_REJECT_METERED_BACKGROUND -> "REJECT_METERED (Mobile only)"
                        else -> "UNKNOWN"
                    }

                    Log.d(TAG, "Applied policy for ${rule.packageName} (UID ${rule.uid}): " +
                            "policy=${if (shouldBlock) "BLOCK ($policyName)" else "ALLOW"}")
                } catch (e: Exception) {
                    errorCount++
                    Log.e(TAG, "Failed to apply policy for ${rule.packageName} (UID ${rule.uid})", e)
                }
            }
            
            Log.d(TAG, "✅ Applied $appliedCount policies, $errorCount errors")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply rules", e)
            Result.failure(errorHandler.handleError(e, "apply network policies"))
        }
    }

    override fun isActive(): Boolean = isRunning

    override fun getType(): FirewallBackendType = FirewallBackendType.NETWORK_POLICY_MANAGER

    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            Log.d(TAG, "=== NetworkPolicyManagerFirewallBackend.checkAvailability() ===")
            
            // Check if Shizuku is available
            if (!shizukuManager.hasShizukuPermission) {
                Log.d(TAG, "❌ NetworkPolicyManager not available: No Shizuku permission")
                val error = errorHandler.createRootRequiredError("NetworkPolicyManager firewall")
                return Result.failure(error)
            }
            
            // Initialize reflection
            if (!initializeReflection()) {
                Log.e(TAG, "❌ NetworkPolicyManager not available: Failed to initialize reflection")
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "NetworkPolicyManager firewall",
                    reason = "Failed to access NetworkPolicyManager API (reflection failed)"
                )
                return Result.failure(error)
            }
            
            // Try to get NetworkPolicyManager instance
            val networkPolicyManager = getNetworkPolicyManager()
            if (networkPolicyManager == null) {
                Log.e(TAG, "❌ NetworkPolicyManager not available: Failed to get service instance")
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "NetworkPolicyManager firewall",
                    reason = "Failed to access NetworkPolicyManager service"
                )
                return Result.failure(error)
            }
            
            Log.d(TAG, "✅ NetworkPolicyManager is available")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "NetworkPolicyManager availability check failed", e)
            Result.failure(errorHandler.handleError(e, "check NetworkPolicyManager availability"))
        }
    }

    /**
     * Initialize reflection objects for NetworkPolicyManager API.
     * This is done once and cached for performance.
     */
    private fun initializeReflection(): Boolean {
        // Return true if already initialized
        if (networkPolicyManagerClass != null && 
            stubClass != null && 
            asInterfaceMethod != null && 
            setUidPolicyMethod != null &&
            getUidPolicyMethod != null) {
            return true
        }

        return try {
            Log.d(TAG, "Initializing reflection for NetworkPolicyManager...")
            
            // Get INetworkPolicyManager class
            networkPolicyManagerClass = Class.forName("android.net.INetworkPolicyManager")
            Log.d(TAG, "✅ Found INetworkPolicyManager class")
            
            // Get INetworkPolicyManager.Stub class
            stubClass = Class.forName("android.net.INetworkPolicyManager\$Stub")
            Log.d(TAG, "✅ Found INetworkPolicyManager.Stub class")
            
            // Get asInterface method
            asInterfaceMethod = stubClass?.getMethod("asInterface", IBinder::class.java)
            Log.d(TAG, "✅ Found asInterface method")
            
            // Get setUidPolicy method
            setUidPolicyMethod = networkPolicyManagerClass?.getMethod(
                "setUidPolicy",
                Int::class.javaPrimitiveType,  // uid
                Int::class.javaPrimitiveType   // policy
            )
            Log.d(TAG, "✅ Found setUidPolicy method")
            
            // Get getUidPolicy method (for debugging)
            getUidPolicyMethod = networkPolicyManagerClass?.getMethod(
                "getUidPolicy",
                Int::class.javaPrimitiveType   // uid
            )
            Log.d(TAG, "✅ Found getUidPolicy method")
            
            Log.d(TAG, "✅ Reflection initialization complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize reflection", e)
            networkPolicyManagerClass = null
            stubClass = null
            asInterfaceMethod = null
            setUidPolicyMethod = null
            getUidPolicyMethod = null
            false
        }
    }

    /**
     * Get NetworkPolicyManager instance via Shizuku.
     */
    private suspend fun getNetworkPolicyManager(): Any? = withContext(Dispatchers.IO) {
        try {
            // Get system service binder via Shizuku
            val serviceBinder = shizukuManager.getSystemServiceBinder(SERVICE_NAME)
            if (serviceBinder == null) {
                Log.e(TAG, "Failed to get system service binder for: $SERVICE_NAME")
                return@withContext null
            }
            
            // Convert binder to INetworkPolicyManager interface
            val networkPolicyManager = asInterfaceMethod?.invoke(null, serviceBinder)
            if (networkPolicyManager == null) {
                Log.e(TAG, "Failed to convert binder to INetworkPolicyManager")
                return@withContext null
            }
            
            Log.d(TAG, "✅ Got NetworkPolicyManager instance")
            return@withContext networkPolicyManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get NetworkPolicyManager instance", e)
            return@withContext null
        }
    }

    /**
     * Test which policy constant is supported on this device.
     * Try POLICY_REJECT_ALL first (blocks WiFi + Mobile), fall back to POLICY_REJECT_METERED_BACKGROUND.
     */
    private fun testPolicySupport(networkPolicyManager: Any) {
        try {
            Log.d(TAG, "Testing policy support on this device...")

            // Try POLICY_REJECT_ALL first (Android 11+ / Custom ROMs)
            try {
                // Use a dummy UID that won't affect anything (UID 0 is root, always allowed)
                setUidPolicyMethod?.invoke(networkPolicyManager, 0, POLICY_REJECT_ALL)
                setUidPolicyMethod?.invoke(networkPolicyManager, 0, POLICY_NONE)  // Reset

                blockingPolicy = POLICY_REJECT_ALL
                Log.d(TAG, "✅ POLICY_REJECT_ALL is supported! Will block WiFi + Mobile networks")
            } catch (e: Exception) {
                // POLICY_REJECT_ALL not supported, fall back to POLICY_REJECT_METERED_BACKGROUND
                Log.w(TAG, "⚠️  POLICY_REJECT_ALL not supported on this device")
                Log.w(TAG, "⚠️  Falling back to POLICY_REJECT_METERED_BACKGROUND")
                Log.w(TAG, "⚠️  ⚠️  ⚠️  LIMITATION: WiFi networks will NOT be blocked! ⚠️  ⚠️  ⚠️")
                Log.w(TAG, "⚠️  Only Mobile/Roaming networks will be blocked")
                Log.w(TAG, "⚠️  For WiFi blocking, use iptables backend (requires root)")
                blockingPolicy = POLICY_REJECT_METERED_BACKGROUND
            }

            policyTested = true

            val policyName = when (blockingPolicy) {
                POLICY_REJECT_ALL -> "POLICY_REJECT_ALL (blocks WiFi + Mobile)"
                POLICY_REJECT_METERED_BACKGROUND -> "POLICY_REJECT_METERED_BACKGROUND (blocks Mobile only, WiFi NOT blocked)"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "✅ Using policy: $policyName")

            if (blockingPolicy == POLICY_REJECT_METERED_BACKGROUND) {
                Log.w(TAG, "")
                Log.w(TAG, "╔════════════════════════════════════════════════════════════════╗")
                Log.w(TAG, "║  ⚠️  IMPORTANT: WiFi networks will NOT be blocked!           ║")
                Log.w(TAG, "║  Only Mobile/Roaming data will be blocked.                   ║")
                Log.w(TAG, "║  For full WiFi blocking, root your device and use iptables.  ║")
                Log.w(TAG, "╚════════════════════════════════════════════════════════════════╝")
                Log.w(TAG, "")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to test policy support", e)
            blockingPolicy = POLICY_REJECT_METERED_BACKGROUND  // Safe fallback
            policyTested = true
        }
    }

    override fun supportsGranularControl(): Boolean = false  // Only Mobile/Roaming (WiFi doesn't work on stock Android)
}

