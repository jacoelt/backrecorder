package com.backrecorder

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * SecurePreferences provides encrypted key-value storage on top of Jetpack DataStore.
 * Tink is used for authenticated encryption (AES256-GCM).
 */
class SecurePreferences(private val context: Context) {

    companion object {
        private const val DATASTORE_NAME = "secure_prefs"
        private const val TINK_KEYSET_NAME = "secure_prefs_keyset"
        private const val TINK_PREF_FILE = "secure_prefs_keys"
        private const val MASTER_KEY_URI = "android-keystore://SecurePrefsMasterKey"

        private val Context.dataStore by preferencesDataStore(DATASTORE_NAME)
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, TINK_KEYSET_NAME, TINK_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /**
     * Saves a String value encrypted.
     */
    fun saveString(key: String, value: String) {
        runBlocking {
            val encrypted = encrypt(value)
            val prefKey = stringPreferencesKey(key)
            context.dataStore.edit { prefs ->
                prefs[prefKey] = encrypted
            }
        }
    }

    /**
     * Reads a String value (decrypted). Returns null if not found.
     */
    fun getString(key: String): String? = runBlocking {
        val prefKey = stringPreferencesKey(key)
        val prefs = context.dataStore.data.first()
        val enc = prefs[prefKey] ?: return@runBlocking null
        return@runBlocking decrypt(enc)
    }

    /**
     * Saves a Long value (not encrypted).
     * (If you want encryption, you can convert to String and use saveString.)
     */
    fun saveLong(key: String, value: Long) {
        runBlocking {
            val prefKey = longPreferencesKey(key)
            context.dataStore.edit { prefs ->
                prefs[prefKey] = value
            }
        }
    }

    /**
     * Reads a Long value. Returns default if not found.
     */
    fun getLong(key: String, default: Long = 0L): Long = runBlocking {
        val prefKey = longPreferencesKey(key)
        val prefs = context.dataStore.data.first()
        return@runBlocking prefs[prefKey] ?: default
    }

    private suspend fun encrypt(plainText: String): String = withContext(Dispatchers.IO) {
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val cipher = aead.encrypt(bytes, null)
        Base64.encodeToString(cipher, Base64.NO_WRAP)
    }

    private suspend fun decrypt(cipherText: String): String = withContext(Dispatchers.IO) {
        val bytes = Base64.decode(cipherText, Base64.NO_WRAP)
        val plain = aead.decrypt(bytes, null)
        plain.toString(Charsets.UTF_8)
    }
}
