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
        private val KEY_DRIVE_DISCONNECTED = booleanPreferencesKey("drive_disconnected")
        private val KEY_PENDING_RECOVERY_FOLDER = stringPreferencesKey("pending_recovery_folder")
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

    /**
     * Set when the user explicitly disconnects Drive. The OAuth grant outlives sign-out, so
     * without this a silent restoreSession() would reconnect the account on the next launch or
     * the next visit to Settings.
     */
    val driveDisconnected: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DRIVE_DISCONNECTED] ?: false }

    /**
     * The recovery folder an in-progress move is filling. Survives process death so a resumed
     * move lands in the same folder instead of scattering one session across several.
     */
    val pendingRecoveryFolder: Flow<String?> = context.dataStore.data
        .map { it[KEY_PENDING_RECOVERY_FOLDER] }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.dataStore.edit { it[KEY_DISCLAIMER_ACCEPTED] = accepted }
    }

    suspend fun setCloudUploadMode(mode: CloudUploadMode) {
        context.dataStore.edit { it[KEY_CLOUD_UPLOAD_MODE] = mode.name }
    }

    suspend fun setWasRecordingBeforeShutdown(wasRecording: Boolean) {
        context.dataStore.edit { it[KEY_WAS_RECORDING_BEFORE_SHUTDOWN] = wasRecording }
    }

    suspend fun setDriveDisconnected(disconnected: Boolean) {
        context.dataStore.edit { it[KEY_DRIVE_DISCONNECTED] = disconnected }
    }

    suspend fun setPendingRecoveryFolder(name: String?) {
        context.dataStore.edit {
            if (name == null) {
                it.remove(KEY_PENDING_RECOVERY_FOLDER)
            } else {
                it[KEY_PENDING_RECOVERY_FOLDER] = name
            }
        }
    }
}
