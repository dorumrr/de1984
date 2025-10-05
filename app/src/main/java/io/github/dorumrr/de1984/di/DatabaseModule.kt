package io.github.dorumrr.de1984.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.dorumrr.de1984.data.database.De1984Database
import io.github.dorumrr.de1984.data.database.dao.FirewallRuleDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideDe1984Database(
        @ApplicationContext context: Context
    ): De1984Database {
        return Room.databaseBuilder(
            context.applicationContext,
            De1984Database::class.java,
            "de1984_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideFirewallRuleDao(database: De1984Database): FirewallRuleDao {
        return database.firewallRuleDao()
    }
}
