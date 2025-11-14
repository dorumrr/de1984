package io.github.dorumrr.de1984.domain.model

data class ReinstallBatchResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>> // packageName to error message
)

