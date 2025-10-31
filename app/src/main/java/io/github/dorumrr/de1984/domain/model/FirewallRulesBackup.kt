package io.github.dorumrr.de1984.domain.model

import kotlinx.serialization.Serializable

/**
 * Wrapper for firewall rules backup/restore.
 * Contains metadata and the list of rules.
 */
@Serializable
data class FirewallRulesBackup(
    val version: Int = 1,
    val exportDate: Long,
    val appVersion: String,
    val rulesCount: Int,
    val rules: List<FirewallRule>
)

