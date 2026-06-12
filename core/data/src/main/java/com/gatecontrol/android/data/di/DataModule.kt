package com.gatecontrol.android.data.di

import android.content.Context
import androidx.datastore.core.CorruptionHandler
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.gatecontrol.android.data.EncryptedStorage
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideEncryptedStorage(@ApplicationContext context: Context): EncryptedStorage =
        EncryptedStorage(context)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            corruptionHandler = CorruptionHandler { exception ->
                Timber.e(exception, "DataStore corrupted, deleting file")
                context.preferencesDataStoreFile("gatecontrol_settings").delete()
                emptyPreferences()
            },
            produceFile = { context.preferencesDataStoreFile("gatecontrol_settings") }
        )
    }

    @Provides
    @Singleton
    fun provideSetupRepository(storage: EncryptedStorage): SetupRepository =
        SetupRepository(storage)

    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository =
        SettingsRepository(dataStore)
}