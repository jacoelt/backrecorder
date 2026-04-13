package com.backrecorderapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

enum class CloudUploadMode {
    OFF, WIFI_ONLY, ALWAYS_ON
}

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        private val KEY_CLOUD_UPLOAD_MODE = stringPreferencesKey("cloud_upload_mode")
        private val KEY_WAS_RECORDING_BEFORE_SHUTDOWN = booleanPreferencesKey("was_recording")
    }

    val disclaimerAccepted: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DISCLAIMER_ACCEPTED] ?: false }

    val cloudUploadMode: Flow<CloudUploadMode> = context.dataStore.data
        .map {
            val value = it[KEY_CLOUD_UPLOAD_MODE] ?: CloudUploadMode.OFF.name
            CloudUploadMode.valueOf(value)
        }

    val wasRecordingBeforeShutdown: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_WAS_RECORDING_BEFORE_SHUTDOWN] ?: false }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.dataStore.edit { it[KEY_DISCLAIMER_ACCEPTED] = accepted }
    }

    suspend fun setCloudUploadMode(mode: CloudUploadMode) {
        context.dataStore.edit { it[KEY_CLOUD_UPLOAD_MODE] = mode.name }
    }

    suspend fun setWasRecordingBeforeShutdown(wasRecording: Boolean) {
        context.dataStore.edit { it[KEY_WAS_RECORDING_BEFORE_SHUTDOWN] = wasRecording }
    }
}
