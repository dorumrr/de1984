package io.github.dorumrr.de1984.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.ActivityLogsBinding
import io.github.dorumrr.de1984.utils.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity to view and share app logs.
 * 
 * Features:
 * - Enable/disable logging toggle (only for release builds)
 * - View last 1000 log entries
 * - Copy logs to clipboard
 * - Share logs via intent
 * - Clear logs
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupLoggingToggle()
        setupButtons()
        observeLogs()
        
        // Initial log display
        updateLogDisplay()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupLoggingToggle() {
        // In debug builds, logging is always enabled
        if (BuildConfig.DEBUG) {
            binding.enableLoggingSwitch.isChecked = true
            binding.enableLoggingSwitch.isEnabled = false
            binding.debugModeNote.visibility = View.VISIBLE
        } else {
            binding.enableLoggingSwitch.isChecked = AppLogger.isEnabledInPrefs(this)
            binding.enableLoggingSwitch.isEnabled = true
            binding.debugModeNote.visibility = View.GONE
            
            binding.enableLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
                AppLogger.setLoggingEnabled(this, isChecked)
                updateLogDisplay()
            }
        }
    }

    private fun setupButtons() {
        binding.clearLogsButton.setOnClickListener {
            AppLogger.clearLogs()
            updateLogDisplay()
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.copyLogsButton.setOnClickListener {
            copyLogsToClipboard()
        }

        binding.shareLogsButton.setOnClickListener {
            shareLogs()
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            AppLogger.logCount.collectLatest { count ->
                binding.logCountText.text = getString(R.string.logs_count, count)
            }
        }
    }

    private fun updateLogDisplay() {
        val logs = AppLogger.getLogs()
        if (logs.isEmpty()) {
            binding.logTextView.text = getString(R.string.logs_empty)
        } else {
            binding.logTextView.text = logs.joinToString("\n") { it.formatForDisplay() }
            // Scroll to bottom
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
        binding.logCountText.text = getString(R.string.logs_count, logs.size)
    }

    private fun copyLogsToClipboard() {
        val logsText = AppLogger.getLogsForExport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("De1984 Logs", logsText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        try {
            // Create a temp file with the logs
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val filename = "de1984_logs_${dateFormat.format(Date())}.txt"
            val logsDir = File(cacheDir, "logs")
            logsDir.mkdirs()
            val logFile = File(logsDir, filename)
            
            logFile.writeText(AppLogger.getLogsForExport())
            
            // Get URI using FileProvider
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                logFile
            )
            
            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "De1984 Debug Logs")
                putExtra(Intent.EXTRA_TEXT, "De1984 debug logs attached. Please include these when reporting issues.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, getString(R.string.logs_share_title)))
        } catch (e: Exception) {
            AppLogger.e("LogsActivity", "Failed to share logs", e)
            Toast.makeText(this, "Failed to share logs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLogDisplay()
    }
}
