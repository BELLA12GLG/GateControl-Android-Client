package com.gatecontrol.android.data.di

import android.content.Context
import androidx.datastore.core.CorruptionHandler
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gatecontrol_settings",
    corruptionHandler = CorruptionHandler { exception ->
        // 删除损坏的文件并返回空配置，避免崩溃
        Timber.e(exception, "DataStore corrupted, deleting file and returning empty preferences")
        val file = this.dataStoreFile("gatecontrol_settings")
        if (file.exists()) {
            file.delete()
            Timber.i("Deleted corrupted DataStore file")
        }
        emptyPreferences()
    }
)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideEncryptedStorage(@ApplicationContext context: Context): EncryptedStorage =
        EncryptedStorage(context)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideSetupRepository(storage: EncryptedStorage): SetupRepository =
        SetupRepository(storage)

    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository =
        SettingsRepository(dataStore)
}