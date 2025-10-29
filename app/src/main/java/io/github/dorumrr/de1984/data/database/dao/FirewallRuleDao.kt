package io.github.dorumrr.de1984.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.dorumrr.de1984.data.database.entity.FirewallRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallRuleDao {
    
    @Query("SELECT * FROM firewall_rules ORDER BY appName ASC")
    fun getAllRules(): Flow<List<FirewallRuleEntity>>
    
    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName")
    fun getRuleByPackage(packageName: String): Flow<FirewallRuleEntity?>
    
    @Query("SELECT * FROM firewall_rules WHERE (wifiBlocked = 1 OR mobileBlocked = 1) AND enabled = 1 ORDER BY appName ASC")
    fun getBlockedRules(): Flow<List<FirewallRuleEntity>>
    
    @Query("SELECT * FROM firewall_rules WHERE wifiBlocked = 0 AND mobileBlocked = 0 AND enabled = 1 ORDER BY appName ASC")
    fun getAllowedRules(): Flow<List<FirewallRuleEntity>>
    
    @Query("SELECT * FROM firewall_rules WHERE isSystemApp = 0 ORDER BY appName ASC")
    fun getUserAppRules(): Flow<List<FirewallRuleEntity>>
    
    @Query("SELECT * FROM firewall_rules WHERE isSystemApp = 1 ORDER BY appName ASC")
    fun getSystemAppRules(): Flow<List<FirewallRuleEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FirewallRuleEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<FirewallRuleEntity>)
    
    @Update
    suspend fun updateRule(rule: FirewallRuleEntity)
    
    @Query("DELETE FROM firewall_rules WHERE packageName = :packageName")
    suspend fun deleteRule(packageName: String)
    
    @Query("DELETE FROM firewall_rules")
    suspend fun deleteAllRules()
    
    @Query("UPDATE firewall_rules SET wifiBlocked = 1, mobileBlocked = 1, updatedAt = :timestamp WHERE enabled = 1")
    suspend fun blockAllApps(timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET wifiBlocked = 0, mobileBlocked = 0, updatedAt = :timestamp WHERE enabled = 1")
    suspend fun allowAllApps(timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM firewall_rules WHERE (wifiBlocked = 1 OR mobileBlocked = 1) AND enabled = 1")
    fun getBlockedCount(): Flow<Int>

    // Atomic field updates to prevent race conditions
    @Query("UPDATE firewall_rules SET wifiBlocked = :blocked, updatedAt = :timestamp WHERE packageName = :packageName")
    suspend fun updateWifiBlocking(packageName: String, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET mobileBlocked = :blocked, updatedAt = :timestamp WHERE packageName = :packageName")
    suspend fun updateMobileBlocking(packageName: String, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET blockWhenRoaming = :blocked, updatedAt = :timestamp WHERE packageName = :packageName")
    suspend fun updateRoamingBlocking(packageName: String, blocked: Boolean, timestamp: Long = System.currentTimeMillis())
}

