package com.benchan.wireless.screencasting.core.security.di

import android.content.Context
import com.benchan.wireless.screencasting.core.security.identity.AndroidKeystoreKeyWrapper
import com.benchan.wireless.screencasting.core.security.identity.IdentityStore
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun identityStore(@ApplicationContext context: Context): IdentityStore =
        IdentityStore(File(context.noBackupFilesDir, "identity/device_identity.json"), AndroidKeystoreKeyWrapper())

    @Provides
    @Singleton
    fun trustedDevices(@ApplicationContext context: Context): TrustedDeviceStore =
        TrustedDeviceStore(File(context.noBackupFilesDir, "trusted_devices.json"))
}
