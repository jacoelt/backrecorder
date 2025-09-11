@file:Suppress("DEPRECATION")

package com.backrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsIntent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import java.io.File

class GDriveHelper(
    private val context: Context,
    private val authLauncher: ActivityResultLauncher<Intent>,
    private val signInCallback: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "GDriveHelper"
        private const val WEB_CLIENT_ID = "580864635782-hod1dh3beh84b9ckcv0fjdqfg47svu5g.apps.googleusercontent.com"
        private const val ANDROID_CLIENT_ID = "580864635782-kspun7adi7q2743bsfmun2u40taauu76.apps.googleusercontent.com"
        private const val REDIRECT_URI = "com.backrecorder:/oauth2redirect"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        enum class FolderType(val value: String) {
            STAGING("staging"), FINAL("final")
        }
    }

    private var accessToken: String? = null
    private var stagingFolder: String? = null
    private var finalFolder: String? = null


    private val authService: AuthorizationService = AuthorizationService(context)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    fun launchSignIn() {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            ANDROID_CLIENT_ID,
            "code",
            Uri.parse(REDIRECT_URI)
        ).setScopes(
            "openid",
            "profile",
            DRIVE_SCOPE
        ).build()

        val authIntent = authService.getAuthorizationRequestIntent(request)
        authLauncher.launch(authIntent)
    }

    fun handleAuthResponse(data: Intent?) {
        val response = AuthorizationResponse.fromIntent(data!!)
        val ex = AuthorizationException.fromIntent(data)

        if (response != null) {
            authService.performTokenRequest(
                response.createTokenExchangeRequest()
            ) { tokenResponse, exception ->
                if (tokenResponse != null) {
                    accessToken = tokenResponse.accessToken
                    Log.d("GDriveHelper", "AccessToken: $accessToken")
                    signInCallback(true)
                } else {
                    Log.e("GDriveHelper", "Token Exchange failed", exception)
                    signInCallback(false)
                }
            }
        } else {
            Log.e("GDriveHelper", "Authorization failed", ex)
            signInCallback(false)
        }
    }

//    fun handleRedirect(intent: Intent) {
//        val data = intent.data ?: return
//
//        // URL looks like: com.backrecorder:/oauth2redirect#access_token=XYZ&token_type=Bearer&expires_in=3600
//        val fragment = data.fragment ?: return
//        val params = fragment.split("&").associate {
//            val pair = it.split("=")
//            pair[0] to pair.getOrElse(1) { "" }
//        }
//
//        if (params["folderId"] != null) {
//            val folderId = intent.data?.getQueryParameter("folderId")
//            val folderType = intent.data?.getQueryParameter("folderType")
//
//
//            Log.d("DrivePicker", "User selected folderId=$folderId for folder type $folderType")
//
//            if (folderType == FolderType.FINAL.value) {
//                finalFolder = folderId
//            } else {
//                stagingFolder = folderId
//            }
//
//            return
//        }
//
//        accessToken = params["access_token"]
//        if (accessToken != null) {
//            Log.d(TAG, "Drive access token successfully fetched")
//            signinCallback()
//        } else {
//            Log.e(TAG, "Impossible to fetch drive access token")
//        }
//    }

//    suspend fun launchSignIn() {
//        val credentialManager = CredentialManager.create(context)
//
//        // Configure sign-in options
//        val googleIdOption = GetGoogleIdOption.Builder()
//            .setFilterByAuthorizedAccounts(false) // show all accounts
//            .setServerClientId(WEB_CLIENT_ID)
//            .build()
//
//        val request = GetCredentialRequest.Builder()
//            .addCredentialOption(googleIdOption)
//            .build()
//
//        val result = credentialManager.getCredential(context, request)
//
//        val credential = result.credential
//        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
//
//        getGoogleAccessToken(googleIdTokenCredential)
//    }
//
//    suspend fun getGoogleAccessToken(credential: GoogleIdTokenCredential) {
//        val accountName = credential.id
//
//        accessToken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
//            GoogleAuthUtil.getToken(
//                context,
//                android.accounts.Account(accountName, "com.google"),
//                DRIVE_SCOPE
//            )
//        }
//
//        Log.d(TAG, "Google Sign-In successful, got access token.")
//    }

    fun uploadFile(f:File, folderType: FolderType) {}
    fun listFiles(location:String) {}
    fun deleteFile(fileId:Int) {}

    fun pickDriveFolder(folderType: FolderType) {
        if (accessToken == null) {
            Log.e(TAG, "SignIn token not available")
        }

        val pickerPageUrl = Uri.parse(
            "https://back-recorder.onrender.com/drive-picker.html" +
                    "?token=$accessToken&folderType=${folderType.value}"
        )

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, pickerPageUrl)
    }
}
