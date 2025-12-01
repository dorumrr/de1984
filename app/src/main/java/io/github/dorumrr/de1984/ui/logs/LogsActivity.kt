package io.github.dorumrr.de1984.ui.logs

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.ActivityLogsBinding
import io.github.dorumrr.de1984.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity to view and share app logs.
 * 
 * Features:
 * - Enable/disable logging toggle
 * - Shows file info (size, line count)
 * - Preview last 100 lines
 * - Share log file directly
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
        observeLogStats()
        updatePreview()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupLoggingToggle() {
        binding.enableLoggingSwitch.isChecked = AppLogger.isEnabledInPrefs(this)
        binding.enableLoggingSwitch.isEnabled = true
        
        if (BuildConfig.DEBUG) {
            binding.debugModeNote.visibility = View.VISIBLE
        } else {
            binding.debugModeNote.visibility = View.GONE
        }
        
        binding.enableLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppLogger.setLoggingEnabled(this, isChecked)
            if (!isChecked) {
                Toast.makeText(this, R.string.logs_disabled_message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupButtons() {
        binding.clearLogsButton.setOnClickListener {
            showClearLogsConfirmation()
        }

        binding.shareLogsButton.setOnClickListener {
            shareLogs()
        }
    }

    private fun showClearLogsConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_clear_confirm_title)
            .setMessage(R.string.logs_clear_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.logs_clear) { _, _ ->
                AppLogger.clearLogs()
                updatePreview()
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun observeLogStats() {
        lifecycleScope.launch {
            AppLogger.logFileSize.collectLatest { _ ->
                val formattedSize = AppLogger.getFormattedFileSize()
                val lineCount = AppLogger.logCount.value
                binding.logCountText.text = getString(R.string.logs_file_info, lineCount, formattedSize)
            }
        }
    }

    private fun updatePreview() {
        lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) {
                AppLogger.getLastLines(100)
            }
            
            if (preview.isEmpty()) {
                binding.logTextView.text = getString(R.string.logs_empty)
            } else {
                binding.logTextView.text = preview
                // Scroll to bottom
                binding.logScrollView.post {
                    binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
            
            // Update stats
            AppLogger.refreshStats()
            val formattedSize = AppLogger.getFormattedFileSize()
            val lineCount = AppLogger.logCount.value
            binding.logCountText.text = getString(R.string.logs_file_info, lineCount, formattedSize)
        }
    }

    private fun shareLogs() {
        lifecycleScope.launch {
            try {
                val exportFile = withContext(Dispatchers.IO) {
                    AppLogger.createExportFile(this@LogsActivity)
                }
                
                if (exportFile == null) {
                    Toast.makeText(this@LogsActivity, R.string.logs_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val uri = FileProvider.getUriForFile(
                    this@LogsActivity,
                    "${applicationContext.packageName}.fileprovider",
                    exportFile
                )
                
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
                Toast.makeText(this@LogsActivity, "Failed to share logs: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePreview()
    }
}
