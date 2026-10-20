package com.victorious.spoof.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.victorious.spoof.BuildConfig
import com.victorious.spoof.audio.AudioEngine
import com.victorious.spoof.auth.SessionStore
import com.victorious.spoof.calling.BackendClient
import com.victorious.spoof.calling.CallProvider
import com.victorious.spoof.calling.HttpBackendClient
import com.victorious.spoof.calling.TwilioCallProvider
import com.victorious.spoof.data.AppDatabase
import com.victorious.spoof.data.VoicePrefs
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
    fun backend(session: SessionStore): BackendClient = HttpBackendClient(BuildConfig.BACKEND_BASE_URL, session)

    @Provides @Singleton
    fun provider(@ApplicationContext ctx: Context, audio: AudioEngine): CallProvider = TwilioCallProvider(ctx, audio)

    @Provides @Singleton
    fun voicePrefs(@ApplicationContext ctx: Context): VoicePrefs =
        VoicePrefs(PreferenceDataStoreFactory.create { ctx.preferencesDataStoreFile("voice_prefs") })
}
