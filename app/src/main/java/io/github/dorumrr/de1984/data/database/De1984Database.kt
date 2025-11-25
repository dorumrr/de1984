package io.github.dorumrr.de1984.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.dorumrr.de1984.data.database.dao.FirewallRuleDao
import io.github.dorumrr.de1984.data.database.entity.FirewallRuleEntity

@Database(
    entities = [
        FirewallRuleEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class De1984Database : RoomDatabase() {

    abstract fun firewallRuleDao(): FirewallRuleDao
}
