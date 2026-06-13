package com.gatecontrol.android.tunnel.di

import android.content.Context
import com.gatecontrol.android.network.DnsResolver
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
    fun provideTunnelManager(
        @ApplicationContext context: Context,
        dnsResolver: DnsResolver,
    ): TunnelManager = TunnelManager(context, dnsResolver)
}
