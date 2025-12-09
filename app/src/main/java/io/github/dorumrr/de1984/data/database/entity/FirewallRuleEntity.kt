package io.github.dorumrr.de1984.data.database.entity

import androidx.room.Entity

/**
 * Database entity for firewall rules.
 *
 * Uses composite primary key (packageName, userId) to support multi-user/work profile environments.
 * The userId represents the Android user profile (0 = personal, 10+ = work/clone profiles).
 *
 * The uid field stores the absolute UID calculated as: userId * 100000 + appId
 */
@Entity(
    tableName = "firewall_rules",
    primaryKeys = ["packageName", "userId"]
)
data class FirewallRuleEntity(
    val packageName: String,

    /** Android user profile ID (0 = personal, 10+ = work/clone profiles) */
    val userId: Int = 0,

    /** Absolute UID: userId * 100000 + appId */
    val uid: Int,
    val appName: String,

    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,

    val blockWhenBackground: Boolean = false,
    val blockWhenRoaming: Boolean = false,
    val lanBlocked: Boolean = false,

    val enabled: Boolean = true,
    val isSystemApp: Boolean = false,
    val hasInternetPermission: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

