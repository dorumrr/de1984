package io.github.dorumrr.de1984.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
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
    
    companion object {
        @Volatile
        private var INSTANCE: De1984Database? = null
        
        fun getDatabase(context: Context): De1984Database {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    De1984Database::class.java,
                    "de1984_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
