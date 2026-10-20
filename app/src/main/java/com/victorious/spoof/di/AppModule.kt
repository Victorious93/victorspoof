package com.victorious.spoof.di

import android.content.Context
import androidx.room.Room
import com.victorious.spoof.BuildConfig
import com.victorious.spoof.calling.BackendClient
import com.victorious.spoof.calling.CallProvider
import com.victorious.spoof.calling.HttpBackendClient
import com.victorious.spoof.calling.TwilioCallProvider
import com.victorious.spoof.data.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "victorspoof.db").build()

    @Provides @Singleton
    fun backend(): BackendClient = HttpBackendClient(BuildConfig.BACKEND_BASE_URL)

    @Provides @Singleton
    fun provider(@ApplicationContext ctx: Context): CallProvider = TwilioCallProvider(ctx)
}
