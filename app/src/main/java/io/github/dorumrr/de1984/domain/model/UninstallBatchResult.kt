package io.github.dorumrr.de1984.domain.model

/**
 * Result of a batch uninstall operation.
 * 
 * @property succeeded List of package names that were successfully uninstalled
 * @property failed List of pairs containing package name and error message for failed uninstalls
 */
data class UninstallBatchResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>> // packageName to error message
)

