package io.github.dorumrr.de1984.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "firewall_rules")
data class FirewallRuleEntity(
    @PrimaryKey
    val packageName: String,

    val uid: Int,
    val appName: String,

    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,

    val blockWhenBackground: Boolean = false,
    val blockWhenRoaming: Boolean = false,

    val enabled: Boolean = true,
    val isSystemApp: Boolean = false,
    val hasInternetPermission: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

