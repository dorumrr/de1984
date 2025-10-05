package io.github.dorumrr.de1984.data.updater

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.dorumrr.de1984.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks for app updates from GitHub releases.
 * Only active in self-distributed builds (IS_SELF_DISTRIBUTED = true).
 * F-Droid and other builds have this disabled.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
    
    /**
     * Check for available updates from GitHub releases.
     * Returns UpdateResult indicating if update is available.
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            // Only check if self-distributed
            if (!BuildConfig.IS_SELF_DISTRIBUTED) {
                return@withContext UpdateResult.NotApplicable
            }
            
            // Check if URL is configured
            if (BuildConfig.UPDATE_CHECK_URL.isEmpty()) {
                return@withContext UpdateResult.NotApplicable
            }
            
            val request = Request.Builder()
                .url(BuildConfig.UPDATE_CHECK_URL)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext UpdateResult.Error("HTTP ${response.code}")
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext UpdateResult.Error("Empty response")
            }
            
            val json = JSONObject(responseBody)
            val latestVersion = json.getString("tag_name").removePrefix("v")
            val downloadUrl = json.getString("html_url")
            val releaseNotes = json.optString("body", "")
            
            val currentVersion = BuildConfig.VERSION_NAME
            
            val result = if (isNewerVersion(latestVersion, currentVersion)) {
                UpdateResult.Available(
                    version = latestVersion,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes
                )
            } else {
                UpdateResult.UpToDate
            }
            
            saveLastCheckResult(result)
            result
        } catch (e: Exception) {
            val errorResult = UpdateResult.Error(e.message ?: "Unknown error")
            saveLastCheckResult(errorResult)
            errorResult
        }
    }
    
    private fun saveLastCheckResult(result: UpdateResult) {
        val editor = prefs.edit()
        when (result) {
            is UpdateResult.Available -> {
                editor.putString("last_update_check_result", "available")
                editor.putString("last_update_version", result.version)
                editor.putString("last_update_url", result.downloadUrl)
                editor.putString("last_update_notes", result.releaseNotes)
            }
            is UpdateResult.UpToDate -> {
                editor.putString("last_update_check_result", "up_to_date")
                editor.remove("last_update_version")
                editor.remove("last_update_url")
                editor.remove("last_update_notes")
            }
            is UpdateResult.Error -> {
                editor.putString("last_update_check_result", "error")
                editor.putString("last_update_error", result.message)
            }
            is UpdateResult.NotApplicable -> {
                editor.putString("last_update_check_result", "not_applicable")
            }
        }
        editor.apply()
    }
    
    fun getLastCheckResult(): UpdateResult? {
        val resultType = prefs.getString("last_update_check_result", null) ?: return null
        
        return when (resultType) {
            "available" -> {
                val version = prefs.getString("last_update_version", null) ?: return null
                val url = prefs.getString("last_update_url", null) ?: return null
                val notes = prefs.getString("last_update_notes", "") ?: ""
                UpdateResult.Available(version, url, notes)
            }
            "up_to_date" -> UpdateResult.UpToDate
            "error" -> {
                val message = prefs.getString("last_update_error", "Unknown error") ?: "Unknown error"
                UpdateResult.Error(message)
            }
            "not_applicable" -> UpdateResult.NotApplicable
            else -> null
        }
    }
    
    /**
     * Compare two semantic version strings.
     * Returns true if 'latest' is newer than 'current'.
     * 
     * Examples:
     * - isNewerVersion("1.0.1", "1.0.0") = true
     * - isNewerVersion("1.1.0", "1.0.9") = true
     * - isNewerVersion("2.0.0", "1.9.9") = true
     * - isNewerVersion("1.0.0", "1.0.0") = false
     * - isNewerVersion("1.0.0", "1.0.1") = false
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(i) ?: 0
            val currentPart = currentParts.getOrNull(i) ?: 0
            
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        
        return false
    }
}

/**
 * Result of an update check.
 */
sealed class UpdateResult {
    /**
     * Update checking is not applicable (F-Droid or other builds).
     */
    object NotApplicable : UpdateResult()
    
    /**
     * App is up to date.
     */
    object UpToDate : UpdateResult()
    
    /**
     * Update is available.
     */
    data class Available(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    ) : UpdateResult()
    
    /**
     * Error occurred while checking for updates.
     */
    data class Error(val message: String) : UpdateResult()
}

