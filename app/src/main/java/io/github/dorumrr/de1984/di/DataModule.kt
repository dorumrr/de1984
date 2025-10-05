package io.github.dorumrr.de1984.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.dorumrr.de1984.data.datasource.AndroidPackageDataSource
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.repository.PackageRepositoryImpl
import io.github.dorumrr.de1984.data.repository.NetworkPackageRepositoryImpl
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    
    @Binds
    @Singleton
    abstract fun bindPackageRepository(
        packageRepositoryImpl: PackageRepositoryImpl
    ): PackageRepository

    @Binds
    @Singleton
    abstract fun bindNetworkPackageRepository(
        networkPackageRepositoryImpl: NetworkPackageRepositoryImpl
    ): NetworkPackageRepository

    @Binds
    @Singleton
    abstract fun bindPackageDataSource(
        androidPackageDataSource: AndroidPackageDataSource
    ): PackageDataSource
}
