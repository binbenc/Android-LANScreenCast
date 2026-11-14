package com.benchan.wireless.screencasting.core.data.di

import com.benchan.wireless.screencasting.core.common.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.System
}
