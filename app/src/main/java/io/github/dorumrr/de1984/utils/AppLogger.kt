package io.github.dorumrr.de1984.utils

import android.content.Context
import android.util.Log
import io.github.dorumrr.de1984.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Centralized logging utility for De1984.
 * 
 * Behavior:
 * - In DEBUG builds: Logging is always enabled, logs go to Logcat AND in-memory buffer
 * - In RELEASE builds: Logging is disabled by default, can be enabled via Settings
 *   When enabled, logs only go to in-memory buffer (not Logcat for privacy)
 * 
 * Features:
 * - Stores last 1000 log entries in memory for viewing in the app
 * - Thread-safe log storage using ConcurrentLinkedDeque
 * - Provides formatted log export for sharing
 */
object AppLogger {
    
    private const val MAX_LOG_ENTRIES = 1000
    private const val PREFS_NAME = "de1984_prefs"
    private const val KEY_LOGGING_ENABLED = "app_logging_enabled"
    
    // Thread-safe log storage
    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()
    
    // Observable log count for UI updates
    private val _logCount = MutableStateFlow(0)
    val logCount: StateFlow<Int> = _logCount.asStateFlow()
    
    // Whether logging is enabled (checked at runtime for release builds)
    @Volatile
    private var isLoggingEnabled = BuildConfig.DEBUG
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    /**
     * Log entry data class
     */
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        fun formatForDisplay(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val levelChar = when (level) {
                LogLevel.VERBOSE -> "V"
                LogLevel.DEBUG -> "D"
                LogLevel.INFO -> "I"
                LogLevel.WARN -> "W"
                LogLevel.ERROR -> "E"
            }
            val throwableStr = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
            return "$time $levelChar/$tag: $message$throwableStr"
        }
        
        fun formatForExport(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val levelStr = level.name.padEnd(7)
            val throwableStr = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
            return "$time $levelStr $tag: $message$throwableStr"
        }
    }
    
    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * Initialize logger with context to read preferences.
     * Call this from Application.onCreate()
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isLoggingEnabled = prefs.getBoolean(KEY_LOGGING_ENABLED, false)
        }
        d("AppLogger", "Logger initialized (debug=${BuildConfig.DEBUG}, enabled=$isLoggingEnabled)")
    }
    
    /**
     * Enable or disable logging (only affects release builds)
     */
    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        isLoggingEnabled = enabled || BuildConfig.DEBUG
        if (!BuildConfig.DEBUG) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
        }
        d("AppLogger", "Logging ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Check if logging is currently enabled
     */
    fun isEnabled(): Boolean = isLoggingEnabled
    
    /**
     * Check if logging is enabled from preferences
     */
    fun isEnabledInPrefs(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOGGING_ENABLED, false)
    }
    
    // Logging methods
    
    fun v(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.VERBOSE, tag, message, throwable)
    }
    
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }
    
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.INFO, tag, message, throwable)
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, tag, message, throwable)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }
    
    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // Use De1984 prefix for easy logcat filtering
        val fullTag = "De1984/$tag"
        
        // Always log to Logcat in debug builds
        if (BuildConfig.DEBUG) {
            when (level) {
                LogLevel.VERBOSE -> if (throwable != null) Log.v(fullTag, message, throwable) else Log.v(fullTag, message)
                LogLevel.DEBUG -> if (throwable != null) Log.d(fullTag, message, throwable) else Log.d(fullTag, message)
                LogLevel.INFO -> if (throwable != null) Log.i(fullTag, message, throwable) else Log.i(fullTag, message)
                LogLevel.WARN -> if (throwable != null) Log.w(fullTag, message, throwable) else Log.w(fullTag, message)
                LogLevel.ERROR -> if (throwable != null) Log.e(fullTag, message, throwable) else Log.e(fullTag, message)
            }
        }
        
        // Store in buffer if logging is enabled
        if (isLoggingEnabled) {
            val entry = LogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,  // Store original tag in buffer for cleaner display
                message = message,
                throwable = throwable
            )
            
            logBuffer.addLast(entry)
            
            // Trim buffer if too large
            while (logBuffer.size > MAX_LOG_ENTRIES) {
                logBuffer.pollFirst()
            }
            
            _logCount.value = logBuffer.size
        }
    }
    
    /**
     * Get all log entries (newest last)
     */
    fun getLogs(): List<LogEntry> = logBuffer.toList()
    
    /**
     * Get logs formatted for display
     */
    fun getLogsForDisplay(): String {
        return logBuffer.joinToString("\n") { it.formatForDisplay() }
    }
    
    /**
     * Get logs formatted for export/sharing
     */
    fun getLogsForExport(): String {
        val header = buildString {
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("De1984 Debug Logs")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("Export Time: ${exportDateFormat.format(Date())}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Debug Build: ${BuildConfig.DEBUG}")
            appendLine("Log Entries: ${logBuffer.size}")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()
        }
        
        return header + logBuffer.joinToString("\n") { it.formatForExport() }
    }
    
    /**
     * Clear all stored logs
     */
    fun clearLogs() {
        logBuffer.clear()
        _logCount.value = 0
        d("AppLogger", "Logs cleared")
    }
    
    /**
     * Filter logs by tag prefix
     */
    fun getLogsByTagPrefix(prefix: String): List<LogEntry> {
        return logBuffer.filter { it.tag.startsWith(prefix, ignoreCase = true) }
    }
    
    /**
     * Filter logs by level (and above)
     */
    fun getLogsByMinLevel(minLevel: LogLevel): List<LogEntry> {
        return logBuffer.filter { it.level.ordinal >= minLevel.ordinal }
    }
}
