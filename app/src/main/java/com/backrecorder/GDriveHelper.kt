@file:Suppress("DEPRECATION")

package com.backrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.File

class GDriveHelper(
    private val context: Context,
    private val signInLauncher : ActivityResultLauncher<Intent>,
    private val folderPickerLauncher: ActivityResultLauncher<Intent>
) {
    companion object {
        private const val TAG = "GDriveHelper"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    private var accessToken: String? = null

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_SCOPE))
        .build()

    private val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, signInOptions)

    fun launchSignIn() {
//        val account = GoogleSignIn.getLastSignedInAccount(context)
//        if (account) {
//            return account;
//        }
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    fun getAccessToken(account: GoogleSignInAccount) {
        try {
            val scope = "oauth2:$DRIVE_SCOPE"
            accessToken = account.account?.let { GoogleAuthUtil.getToken(context, it, scope) }
        } catch (e: UserRecoverableAuthException) {
            e.intent?.let { signInLauncher.launch(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting token", e)
        }
    }

    fun signOut() {
        googleSignInClient.signOut()
    }

    fun uploadFile(f:File) {}
    fun listFiles(location:String) {}
    fun deleteFile(fileId:Int) {}


    suspend fun pickDriveFolder() {
        if (!accessToken) {
            Log.e("GDriveHelper", "SignIn token not available")
        }

        val pickerPageUrl = Uri.parse(
            "https://back-recorder.onrender.com/drive-picker.html" +
                    "?apiKey=$apiKey" +
                    "&token=$accessToken"
        )

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, pickerPageUrl)
    }
}
