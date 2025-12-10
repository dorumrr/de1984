package io.github.dorumrr.de1984.utils

import android.content.Context
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Utility for loading and accessing package safety data from JSON file.
 * Data is cached in memory for fast lookup.
 */
object PackageSafetyLoader {
    private const val TAG = "PackageSafetyLoader"
    private const val SAFETY_DATA_FILE = "package_safety_levels.json"

    // @Volatile ensures visibility across threads for the double-check idiom
    @Volatile
    private var cachedData: PackageSafetyData? = null
    private val loadMutex = Mutex()
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Load safety data from assets. Data is cached after first load.
     * Thread-safe with mutex to prevent race conditions during parallel loading.
     */
    suspend fun loadSafetyData(context: Context): PackageSafetyData {
        // Fast path: return cached data if available
        cachedData?.let { return it }
        
        // Slow path: load with mutex to prevent duplicate loads
        return loadMutex.withLock {
            // Double-check after acquiring lock
            cachedData?.let { return@withLock it }
            
            try {
                AppLogger.d(TAG, "Loading package safety data from assets...")
                
                val jsonString = context.assets.open(SAFETY_DATA_FILE).bufferedReader().use { 
                    it.readText() 
                }
                
                val data = json.decodeFromString<PackageSafetyData>(jsonString)
                cachedData = data
                
                AppLogger.d(TAG, "Loaded safety data for ${data.packages.size} packages")
                AppLogger.d(TAG, "Data version: ${data.version}, last updated: ${data.lastUpdated}")

                data
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load safety data", e)
                // Return empty data on error
                val emptyData = PackageSafetyData(
                    version = 0,
                    lastUpdated = "",
                    packages = emptyMap()
                )
                cachedData = emptyData
                emptyData
            }
        }
    }
    
    /**
     * Get safety info for a specific package.
     * Returns null if package not found in safety data.
     */
    suspend fun getSafetyInfo(context: Context, packageName: String): PackageSafetyInfo? {
        val data = loadSafetyData(context)
        return data.packages[packageName]
    }
    
    /**
     * Get criticality level for a package.
     * Returns UNKNOWN if package not found in safety data.
     */
    suspend fun getCriticality(context: Context, packageName: String): PackageCriticality {
        val info = getSafetyInfo(context, packageName) ?: return PackageCriticality.UNKNOWN
        
        return when (info.criticality) {
            "essential" -> PackageCriticality.ESSENTIAL
            "important" -> PackageCriticality.IMPORTANT
            "optional" -> PackageCriticality.OPTIONAL
            "bloatware" -> PackageCriticality.BLOATWARE
            else -> PackageCriticality.UNKNOWN
        }
    }
    
    /**
     * Get category for a package.
     * Returns null if package not found in safety data.
     */
    suspend fun getCategory(context: Context, packageName: String): String? {
        return getSafetyInfo(context, packageName)?.category
    }
    
    /**
     * Get affects list for a package.
     * Returns empty list if package not found in safety data.
     */
    suspend fun getAffects(context: Context, packageName: String): List<String> {
        return getSafetyInfo(context, packageName)?.affects ?: emptyList()
    }
    
    /**
     * Check if a package is critical (Essential or Important).
     * Used to determine if uninstall should be restricted.
     */
    suspend fun isCriticalPackage(context: Context, packageName: String): Boolean {
        val criticality = getCriticality(context, packageName)
        return criticality == PackageCriticality.ESSENTIAL || 
               criticality == PackageCriticality.IMPORTANT
    }
    
    /**
     * Clear cached data. Useful for testing or if data needs to be reloaded.
     */
    fun clearCache() {
        cachedData = null
        AppLogger.d(TAG, "Safety data cache cleared")
    }
}

/**
 * Root data structure for package safety JSON file.
 *
 * Package safety data is user-contributed. To contribute:
 * - Submit package classifications via GitHub issues
 * - Include package name, criticality level, category, and affected functionality
 * - Help make De1984 safer for everyone!
 */
@Serializable
data class PackageSafetyData(
    val version: Int,
    val lastUpdated: String,
    val packages: Map<String, PackageSafetyInfo>
)

/**
 * Safety information for a single package.
 */
@Serializable
data class PackageSafetyInfo(
    val criticality: String,
    val category: String,
    val affects: List<String>
)

