package com.benchan.wireless.screencasting.protocol.airplay.api

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 可选绑定：:protocol:airplay 模块存在时由它的 @Binds 提供实现；不存在时 `Optional<AirPlayReceiver>` 为空。
 */
@Module
@InstallIn(SingletonComponent::class)
interface AirPlayApiModule {
    @BindsOptionalOf
    fun airPlayReceiver(): AirPlayReceiver
}
