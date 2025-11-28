package com.backrecorderapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.*
import net.openid.appauth.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GDriveHelper(
    private val context: Context,
    private val authLauncher: ActivityResultLauncher<Intent>,
    private val signInCallback: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "GDriveHelper"
        private const val ANDROID_CLIENT_ID =
            "580864635782-kspun7adi7q2743bsfmun2u40taauu76.apps.googleusercontent.com"
        private const val REDIRECT_URI = "com.backrecorderapp:/oauth2redirect"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        private const val PREF_KEY_ACCESS_TOKEN = "drive_access_token"
        private const val PREF_KEY_REFRESH_TOKEN = "drive_refresh_token"
        private const val PREF_KEY_FINAL_FOLDER = "drive_final_folder"
        private const val PREF_KEY_STAGING_FOLDER = "drive_staging_folder"
    }

    enum class FolderType(val value: String) {
        STAGING("staging"), FINAL("final")
    }

    // Internal coroutine scope
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val authService = AuthorizationService(context)
    private val httpClient = OkHttpClient()
    private val securePrefs = SecurePreferences(context)
    private val settingsDataStore = SettingsDataStore.getInstance(context)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    // ===== Utility for safe network operations =====
    private suspend fun <T> safeNetworkCall(operationName: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "No network connection during $operationName", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error during $operationName", e)
            null
        }
    }

    fun launchSignIn() {
        scope.launch {
            val enabled = settingsDataStore.getUseGDrive()
            if (!enabled) {
                Log.d(TAG, "GDrive disabled, skipping Sign-in")
                return@launch
            }

            withContext(Dispatchers.Main) {
                val request = AuthorizationRequest.Builder(
                    serviceConfig,
                    ANDROID_CLIENT_ID,
                    "code",
                    Uri.parse(REDIRECT_URI)
                ).setScopes("openid", "profile", DRIVE_SCOPE).build()

                val authIntent = authService.getAuthorizationRequestIntent(request)
                authLauncher.launch(authIntent)
            }
        }
    }

    fun handleAuthResponse(data: Intent?) {
        val response = AuthorizationResponse.fromIntent(data!!)
        val ex = AuthorizationException.fromIntent(data)

        if (response != null) {
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, exception ->
                if (tokenResponse != null) {
                    securePrefs.saveString(PREF_KEY_ACCESS_TOKEN, tokenResponse.accessToken ?: "")
                    securePrefs.saveString(PREF_KEY_REFRESH_TOKEN, tokenResponse.refreshToken ?: "")
                    Log.d(TAG, "AccessToken stored: ${tokenResponse.accessToken}")
                    signInCallback(true)
                } else {
                    Log.e(TAG, "Token Exchange failed", exception)
                    signInCallback(false)
                }
            }
        } else {
            Log.e(TAG, "Authorization failed", ex)
            signInCallback(false)
        }
    }

    fun setupDrive() {
        scope.launch {
            val enabled = settingsDataStore.getUseGDrive()
            if (!enabled) {
                Log.d(TAG, "GDrive disabled, skipping Drive setup")
                return@launch
            }

            val finalId = getOrCreateFolder("BackRecorder", null)
            checkNotNull(finalId)

            val stagingId = getOrCreateFolder("staging", finalId)
            checkNotNull(stagingId)

            securePrefs.saveString(PREF_KEY_FINAL_FOLDER, finalId ?: "")
            securePrefs.saveString(PREF_KEY_STAGING_FOLDER, stagingId ?: "")

            Log.d(TAG, "Drive setup done. Final=$finalId Staging=$stagingId")
        }
    }

    private suspend fun getOrCreateFolder(name: String, parentId: String?): String? {
        val token = getValidAccessToken() ?: return null

        return safeNetworkCall("getOrCreateFolder") {
            val query = if (parentId == null) {
                "name='$name' and 'root' in parents and trashed=false"
            } else {
                "name='$name' and '$parentId' in parents and trashed=false"
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?q=${Uri.encode(query)}&spaces=drive")
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@safeNetworkCall null
                val files = JSONObject(body).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return@safeNetworkCall files.getJSONObject(0).getString("id")
                }
            }

            val metadata = JSONObject().apply {
                put("name", name)
                put("mimeType", "application/vnd.google-apps.folder")
                if (parentId != null) {
                    put("parents", JSONArray().put(parentId))
                }
            }

            val body = metadata.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val createRequest = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(createRequest).execute().use { response ->
                val respBody = response.body?.string()
                return@safeNetworkCall JSONObject(respBody ?: "").getString("id")
            }
        }
    }

    fun uploadFile(file: File, folderType: FolderType, fileName: String?, callback: ((Boolean) -> Unit)?) {
        scope.launch {
            val enabled = settingsDataStore.getUseGDrive()
            if (!enabled) {
                Log.d(TAG, "GDrive disabled, skipping upload")
                callback?.invoke(true)
                return@launch
            }

            val token = getValidAccessToken() ?: return@launch
            safeNetworkCall("uploadFile") {
                val folderId = when (folderType) {
                    FolderType.FINAL -> securePrefs.getString(PREF_KEY_FINAL_FOLDER)
                    FolderType.STAGING -> securePrefs.getString(PREF_KEY_STAGING_FOLDER)
                } ?: return@safeNetworkCall

                val metadata = JSONObject().apply {
                    put("name", fileName ?: file.name)
                    put("parents", JSONArray().put(folderId))
                }

                val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "metadata", null,
                        metadata.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    )
                    .addFormDataPart(
                        "file", file.name,
                        file.readBytes().toRequestBody("audio/ogg".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "File ${file.name} uploaded successfully")
                    } else {
                        Log.e(TAG, "Upload failed: ${response.code} ${response.message}")
                    }
                    callback?.invoke(response.isSuccessful)
                }
            }
        }
    }

    fun deleteOldestFromStaging(maxFiles: Int = 10) {
        scope.launch {
            val enabled = settingsDataStore.getUseGDrive()
            if (!enabled) {
                Log.d(TAG, "GDrive disabled, skipping cleanup")
                return@launch
            }

            val token = getValidAccessToken() ?: return@launch
            val stagingId = securePrefs.getString(PREF_KEY_STAGING_FOLDER) ?: return@launch

            safeNetworkCall("deleteOldestFromStaging") {
                val request = Request.Builder()
                    .url(
                        "https://www.googleapis.com/drive/v3/files?" +
                                "q='${stagingId}' in parents and trashed=false&orderBy=createdTime"
                    )
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to list staging files: ${response.code}")
                        return@safeNetworkCall
                    }

                    val body = response.body?.string() ?: return@safeNetworkCall
                    val files = JSONObject(body).optJSONArray("files") ?: return@safeNetworkCall
                    if (files.length() <= maxFiles) return@safeNetworkCall

                    val toDeleteCount = files.length() - maxFiles
                    for (i in 0 until toDeleteCount) {
                        val fileId = files.getJSONObject(i).getString("id")
                        val deleteRequest = Request.Builder()
                            .url("https://www.googleapis.com/drive/v3/files/$fileId")
                            .delete()
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                        httpClient.newCall(deleteRequest).execute().close()
                        Log.d(TAG, "Deleted old staging file $fileId")
                    }
                }
            }
        }
    }


    private suspend fun getValidAccessToken(): String? {
        var token = securePrefs.getString(PREF_KEY_ACCESS_TOKEN)
        if (token.isNullOrEmpty()) {
            token = refreshAccessToken()
        }
        return token
    }

    private suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = securePrefs.getString(PREF_KEY_REFRESH_TOKEN) ?: return@withContext null

        return@withContext safeNetworkCall("refreshAccessToken") {
            val body = FormBody.Builder()
                .add("client_id", ANDROID_CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Refresh token failed: ${response.code} ${response.message}")
                    return@safeNetworkCall null
                }
                val respBody = response.body?.string() ?: return@safeNetworkCall null
                val json = JSONObject(respBody)
                val newAccessToken = json.optString("access_token", "")
                if (newAccessToken.isNotEmpty()) {
                    securePrefs.saveString(PREF_KEY_ACCESS_TOKEN, newAccessToken)
                    Log.d(TAG, "Access token refreshed")
                }
                return@safeNetworkCall newAccessToken
            }
        }
    }

    fun clear() {
        job.cancel()
        authService.dispose()
    }
}
