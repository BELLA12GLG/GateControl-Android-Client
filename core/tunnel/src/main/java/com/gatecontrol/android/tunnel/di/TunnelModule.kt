package com.gatecontrol.android.tunnel.di

import android.content.Context
import com.gatecontrol.android.tunnel.TunnelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TunnelModule {

    @Provides
    @Singleton
    fun provideTunnelManager(@ApplicationContext context: Context): TunnelManager =
        TunnelManager(context)

    // TunnelMonitor 由 VpnForegroundService 按需创建（每次连接独立实例），
    // 不在 DI 中提供单例，避免状态混乱。
}
