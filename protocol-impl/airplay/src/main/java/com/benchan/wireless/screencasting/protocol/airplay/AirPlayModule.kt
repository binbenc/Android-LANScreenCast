package com.benchan.wireless.screencasting.protocol.airplay

import com.benchan.wireless.screencasting.protocol.airplay.api.AirPlayReceiver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 只有 -Pwsc.airplay=true 时 :app 才依赖本模块，此绑定才会进入依赖图（技术方案 3.8）。 */
@Module
@InstallIn(SingletonComponent::class)
interface AirPlayModule {
    @Binds
    fun bindAirPlayReceiver(impl: UxPlayAirPlayReceiver): AirPlayReceiver
}
