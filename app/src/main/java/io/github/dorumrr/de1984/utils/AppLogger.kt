package io.github.dorumrr.de1984.utils

import android.content.Context
import android.util.Log
import io.github.dorumrr.de1984.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object AppLogger {
    
    private const val LOG_FILE_NAME = "de1984_logs.txt"
    private const val LOG_FILE_OLD_NAME = "de1984_logs_old.txt"
    private const val MAX_LOG_FILE_SIZE = 1 * 1024 * 1024L
    private const val PREFS_NAME = "de1984_prefs"
    private const val KEY_LOGGING_ENABLED = "app_logging_enabled"
    
    private val writeLock = ReentrantLock()
    
    @Volatile
    private var logFile: File? = null
    
    @Volatile
    private var appContext: Context? = null
    
    private val _logCount = MutableStateFlow(0)
    val logCount: StateFlow<Int> = _logCount.asStateFlow()
    
    private val _logFileSize = MutableStateFlow(0L)
    val logFileSize: StateFlow<Long> = _logFileSize.asStateFlow()
    
    @Volatile
    private var isLoggingEnabled = BuildConfig.DEBUG
    
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }
    
    fun init(context: Context) {
        appContext = context.applicationContext
        logFile = File(context.filesDir, LOG_FILE_NAME)
        
        if (!BuildConfig.DEBUG) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isLoggingEnabled = prefs.getBoolean(KEY_LOGGING_ENABLED, false)
        }
        
        updateStats()
        d("AppLogger", "Logger initialized | debug=${BuildConfig.DEBUG} | enabled=$isLoggingEnabled")
    }
    
    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        isLoggingEnabled = enabled || BuildConfig.DEBUG
        if (!BuildConfig.DEBUG) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
        }
        d("AppLogger", "Logging ${if (enabled) "enabled" else "disabled"}")
    }
    
    fun isEnabled(): Boolean = isLoggingEnabled
    
    fun isEnabledInPrefs(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOGGING_ENABLED, false)
    }
    
    
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
        val fullTag = "De1984/$tag"
        
        if (BuildConfig.DEBUG) {
            when (level) {
                LogLevel.VERBOSE -> if (throwable != null) Log.v(fullTag, message, throwable) else Log.v(fullTag, message)
                LogLevel.DEBUG -> if (throwable != null) Log.d(fullTag, message, throwable) else Log.d(fullTag, message)
                LogLevel.INFO -> if (throwable != null) Log.i(fullTag, message, throwable) else Log.i(fullTag, message)
                LogLevel.WARN -> if (throwable != null) Log.w(fullTag, message, throwable) else Log.w(fullTag, message)
                LogLevel.ERROR -> if (throwable != null) Log.e(fullTag, message, throwable) else Log.e(fullTag, message)
            }
        }
        
        if (isLoggingEnabled) {
            writeToFile(level, tag, message, throwable)
        }
    }
    
    private fun writeToFile(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val file = logFile ?: return
        
        writeLock.withLock {
            try {
                if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
                    rotateLogFile()
                }
                
                val timestamp = logDateFormat.format(Date())
                val levelChar = when (level) {
                    LogLevel.VERBOSE -> "V"
                    LogLevel.DEBUG -> "D"
                    LogLevel.INFO -> "I"
                    LogLevel.WARN -> "W"
                    LogLevel.ERROR -> "E"
                }
                val logLine = "$timestamp $levelChar/$tag: $message"
                
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logLine)
                    if (throwable != null) {
                        PrintWriter(writer).use { pw ->
                            throwable.printStackTrace(pw)
                        }
                    }
                }
                
                updateStats()
                
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("De1984/AppLogger", "Failed to write log", e)
                }
            }
        }
    }
    
    private fun rotateLogFile() {
        val file = logFile ?: return
        val context = appContext ?: return
        
        try {
            val oldFile = File(context.filesDir, LOG_FILE_OLD_NAME)
            oldFile.delete()
            file.renameTo(oldFile)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e("De1984/AppLogger", "Failed to rotate log", e)
            }
        }
    }
    
    private fun updateStats() {
        val file = logFile ?: return
        try {
            if (file.exists()) {
                _logFileSize.value = file.length()
                _logCount.value = file.useLines { it.count() }
            } else {
                _logFileSize.value = 0
                _logCount.value = 0
            }
        } catch (e: Exception) {
        }
    }
    
    fun getLogFile(): File? = logFile?.takeIf { it.exists() }
    
    fun getLogFileSizeBytes(): Long = logFile?.length() ?: 0
    
    fun getFormattedFileSize(): String {
        val bytes = getLogFileSizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
    
    fun getLastLines(count: Int = 50): String {
        val file = logFile ?: return ""
        if (!file.exists()) return ""
        
        return try {
            val lines = file.readLines()
            lines.takeLast(count).joinToString("\n")
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    fun createExportFile(context: Context): File? {
        val sourceFile = logFile ?: return null
        if (!sourceFile.exists()) return null
        
        return try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val filename = "de1984_logs_${dateFormat.format(Date())}.txt"
            val exportDir = File(context.cacheDir, "logs")
            exportDir.mkdirs()
            val exportFile = File(exportDir, filename)
            // Under the write lock, so a rotation cannot move lines between the two reads. The rotated
            // file holds the log before the current one: without it an export can start mid-event.
            val (olderLog, currentLog) = writeLock.withLock {
                val older = File(context.filesDir, LOG_FILE_OLD_NAME)
                (if (older.exists()) older.readText() else "") to sourceFile.readText()
            }

            exportFile.writeText(buildString {
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine("De1984 Debug Logs")
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine("Export Time: ${logDateFormat.format(Date())}")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Debug Build: ${BuildConfig.DEBUG}")
                appendLine("Log File Size: ${getFormattedFileSize()}")
                appendLine("Older Log Included: ${if (olderLog.isEmpty()) "no" else "yes"}")
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine()
                append(olderLog)
                append(currentLog)
            })
            
            exportFile
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e("De1984/AppLogger", "Failed to create export file", e)
            }
            null
        }
    }
    
    fun clearLogs() {
        writeLock.withLock {
            try {
                logFile?.delete()
                appContext?.let { ctx ->
                    File(ctx.filesDir, LOG_FILE_OLD_NAME).delete()
                }
                _logCount.value = 0
                _logFileSize.value = 0
            } catch (e: Exception) {
            }
        }
        d("AppLogger", "Logs cleared")
    }
    
    fun refreshStats() {
        updateStats()
    }
}
