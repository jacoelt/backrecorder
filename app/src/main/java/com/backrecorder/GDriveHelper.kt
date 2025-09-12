package com.backrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GDriveHelper(
    private val context: Context,
    private val authLauncher: ActivityResultLauncher<Intent>,
    private val signInCallback: (Boolean) -> Unit,
    private val prefs: SecurePreferences
) {
    companion object {
        private const val TAG = "GDriveHelper"
        private const val ANDROID_CLIENT_ID =
            "580864635782-kspun7adi7q2743bsfmun2u40taauu76.apps.googleusercontent.com"
        private const val REDIRECT_URI = "com.backrecorder:/oauth2redirect"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        private const val PREF_ACCESS_TOKEN = "drive_access_token"
        private const val PREF_REFRESH_TOKEN = "drive_refresh_token"
        private const val PREF_EXPIRY = "drive_expiry"
        private const val PREF_FINAL_FOLDER_ID = "final_folder_id"
        private const val PREF_STAGING_FOLDER_ID = "staging_folder_id"

        enum class FolderType { FINAL, STAGING }
    }

    private val httpClient = OkHttpClient()
    private val authService = AuthorizationService(context)

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpiry: Long = 0L

    private var finalFolderId: String? = null
    private var stagingFolderId: String? = null

    init {
        // Load saved data
        accessToken = prefs.getString(PREF_ACCESS_TOKEN)
        refreshToken = prefs.getString(PREF_REFRESH_TOKEN)
        tokenExpiry = prefs.getLong(PREF_EXPIRY, 0L)
        finalFolderId = prefs.getString(PREF_FINAL_FOLDER_ID)
        stagingFolderId = prefs.getString(PREF_STAGING_FOLDER_ID)
    }

    // === AUTH ===
    fun launchSignIn() {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token")
        )

        val request = AuthorizationRequest.Builder(
            serviceConfig,
            ANDROID_CLIENT_ID,
            "code",
            Uri.parse(REDIRECT_URI)
        ).setScopes("openid", "profile", DRIVE_SCOPE).build()

        val intent = authService.getAuthorizationRequestIntent(request)
        authLauncher.launch(intent)
    }

    fun handleAuthResponse(data: Intent?) {
        val resp = AuthorizationResponse.fromIntent(data!!)
        val err = AuthorizationException.fromIntent(data)

        if (resp != null) {
            authService.performTokenRequest(
                resp.createTokenExchangeRequest()
            ) { tokenResponse, ex ->
                if (tokenResponse != null) {
                    accessToken = tokenResponse.accessToken
                    refreshToken = tokenResponse.refreshToken
                    tokenExpiry = System.currentTimeMillis() +
                            (tokenResponse.accessTokenExpirationTime ?: 0L)

                    prefs.saveString(PREF_ACCESS_TOKEN, accessToken!!)
                    refreshToken?.let { prefs.saveString(PREF_REFRESH_TOKEN, it) }
                    prefs.saveLong(PREF_EXPIRY, tokenExpiry)

                    signInCallback(true)
                } else {
                    Log.e(TAG, "Token exchange failed", ex)
                    signInCallback(false)
                }
            }
        } else {
            Log.e(TAG, "Authorization failed", err)
            signInCallback(false)
        }
    }

    // === DRIVE FOLDER MANAGEMENT ===
    fun ensureFolders(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (!isTokenValid()) return@launch

            finalFolderId = finalFolderId ?: findOrCreateFolder("BackRecorder", null)
            stagingFolderId =
                stagingFolderId ?: findOrCreateFolder("staging", finalFolderId)

            finalFolderId?.let { prefs.saveString(PREF_FINAL_FOLDER_ID, it) }
            stagingFolderId?.let { prefs.saveString(PREF_STAGING_FOLDER_ID, it) }
        }
    }

    private fun findOrCreateFolder(name: String, parentId: String?): String? {
        val qParent = parentId?.let { "'$it' in parents and trashed=false" }
            ?: "'root' in parents and trashed=false"
        val query =
            "name='$name' and mimeType='application/vnd.google-apps.folder' and $qParent"

        val url = "https://www.googleapis.com/drive/v3/files?q=${Uri.encode(query)}&fields=files(id,name)"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val files = JSONObject(resp.body!!.string()).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return files.getJSONObject(0).getString("id")
                }
            }
        }

        // Create if not found
        val bodyJson = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            parentId?.let { put("parents", JSONArray().put(it)) }
        }
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(createReq).execute().use { createResp ->
            return if (createResp.isSuccessful) {
                JSONObject(createResp.body!!.string()).getString("id")
            } else null
        }
    }

    // === FILE UPLOAD ===
    fun uploadFile(file: File, type: FolderType, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (!isTokenValid()) return@launch
            val folderId = if (type == FolderType.FINAL) finalFolderId else stagingFolderId
            if (folderId == null) {
                Log.e(TAG, "Folder ID is missing for $type")
                return@launch
            }

            val metadata = JSONObject().apply {
                put("name", file.name)
                put("parents", JSONArray().put(folderId))
            }

            val metaBody = MultipartBody.Part.createFormData(
                "metadata", null,
                metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            val fileBody = MultipartBody.Part.createFormData(
                "file", file.name, file.asRequestBody("application/octet-stream".toMediaType())
            )

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addPart(metaBody)
                    .addPart(fileBody)
                    .build()
                ).build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Upload failed: ${resp.code}")
                }
            }
        }
    }

    // === STAGING CLEANUP ===
    fun deleteOldestStagingFile(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (!isTokenValid() || stagingFolderId == null) return@launch

            val url =
                "https://www.googleapis.com/drive/v3/files?q='${stagingFolderId}' in parents and trashed=false&fields=files(id,name)&orderBy=name"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val arr = JSONObject(resp.body!!.string()).optJSONArray("files") ?: return@use
                if (arr.length() > 0) {
                    val oldestId = arr.getJSONObject(0).getString("id")
                    val delReq = Request.Builder()
                        .url("https://www.googleapis.com/drive/v3/files/$oldestId")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .delete()
                        .build()
                    httpClient.newCall(delReq).execute()
                }
            }
        }
    }

    // === HELPERS ===
    private fun isTokenValid(): Boolean {
        if (accessToken == null) return false
        if (tokenExpiry == 0L) return true // unknown expiry, assume valid
        return System.currentTimeMillis() < tokenExpiry
    }
}
