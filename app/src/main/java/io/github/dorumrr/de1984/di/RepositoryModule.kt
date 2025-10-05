package io.github.dorumrr.de1984.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.dorumrr.de1984.data.repository.FirewallRepositoryImpl
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindFirewallRepository(
        firewallRepositoryImpl: FirewallRepositoryImpl
    ): FirewallRepository

}

