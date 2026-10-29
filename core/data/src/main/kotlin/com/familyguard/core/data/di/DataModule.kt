package com.familyguard.core.data.di

import android.content.Context
import androidx.room.Room
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.mode.ChildDeviceStore
import com.familyguard.core.data.repo.AccountRepositoryImpl
import com.familyguard.core.data.repo.FamilyRepositoryImpl
import com.familyguard.core.data.repo.LocationRepositoryImpl
import com.familyguard.core.data.repo.PairingRepositoryImpl
import com.familyguard.core.data.repo.RulesRepositoryImpl
import com.familyguard.core.data.repo.TimeRequestRepositoryImpl
import com.familyguard.core.data.repo.UsageRepositoryImpl
import com.familyguard.core.domain.repo.AccountRepository
import com.familyguard.core.domain.repo.FamilyRepository
import com.familyguard.core.domain.repo.LocationRepository
import com.familyguard.core.domain.repo.PairingRepository
import com.familyguard.core.domain.repo.RulesRepository
import com.familyguard.core.domain.repo.TimeRequestRepository
import com.familyguard.core.domain.repo.UsageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FamilyGuardDatabase =
        Room.databaseBuilder(context, FamilyGuardDatabase::class.java, FamilyGuardDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideChildDeviceStore(@ApplicationContext context: Context): ChildDeviceStore =
        ChildDeviceStore(context)

    @Provides
    fun provideFamilyRepository(impl: FamilyRepositoryImpl): FamilyRepository = impl

    @Provides
    fun provideRulesRepository(impl: RulesRepositoryImpl): RulesRepository = impl

    @Provides
    fun provideTimeRequestRepository(impl: TimeRequestRepositoryImpl): TimeRequestRepository = impl

    @Provides
    fun provideUsageRepository(impl: UsageRepositoryImpl): UsageRepository = impl

    @Provides
    fun provideLocationRepository(impl: LocationRepositoryImpl): LocationRepository = impl

    @Provides
    fun providePairingRepository(impl: PairingRepositoryImpl): PairingRepository = impl

    @Provides
    fun provideAccountRepository(impl: AccountRepositoryImpl): AccountRepository = impl
}
