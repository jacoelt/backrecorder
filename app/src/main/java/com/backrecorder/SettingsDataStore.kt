package com.backrecorder

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsDataStore private constructor(private val context: Context) {

    companion object {
        private const val DATASTORE_NAME = "settings"

        private val PREF_USE_GDRIVE = booleanPreferencesKey("use_gdrive")
        private val PREF_DURATION = intPreferencesKey("recording_duration")
        private val PREF_TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")

        // Singleton instance
        @Suppress("StaticFieldLeak") // Not a problem if the context parameter is an application context and not an activity context, checked in getInstance
        @Volatile
        private var INSTANCE: SettingsDataStore? = null

        fun getInstance(context: Context): SettingsDataStore {
            val appContext = context.applicationContext // Making sure the reference is an application context and not an activity context
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        // DataStore extension (scoped to the app context)
        private val Context.dataStore by preferencesDataStore(name = DATASTORE_NAME)
    }

    private val dataStore = context.dataStore

    suspend fun setUseGDrive(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PREF_USE_GDRIVE] = enabled
        }
    }

    fun getUseGDriveFlow(): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[PREF_USE_GDRIVE] ?: false
        }

    suspend fun getUseGDrive(): Boolean {
        return getUseGDriveFlow().first()
    }

    suspend fun setRecordingDuration(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[PREF_DURATION] = minutes
        }
    }

    suspend fun getRecordingDuration(defaultValue: Int): Int {
        return dataStore.data.map { prefs ->
            prefs[PREF_DURATION] ?: defaultValue
        }.first()
    }

    suspend fun saveTermsAccepted(accepted: Boolean) {
        dataStore.edit { prefs ->
            prefs[PREF_TERMS_ACCEPTED] = accepted
        }
    }

    suspend fun isTermsAccepted(): Boolean {
        return dataStore.data.map { prefs ->
            prefs[PREF_TERMS_ACCEPTED] ?: false
        }.first()
    }
}
