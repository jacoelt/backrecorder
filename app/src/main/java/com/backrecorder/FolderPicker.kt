package com.backrecorder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class FolderPicker(private val context: ComponentActivity) {

    companion object {
        private const val TAG = "FolderPicker"
        private const val FOLDER_PREFS = "folder-prefs"
        private const val PREF_STAGING_URI = "staging_uri"
        private const val PREF_FINAL_URI = "final_uri"

        enum class FolderType(val value: String) {
            STAGING("staging"), FINAL("final")
        }
    }

    private val pickFolderLauncher =
        context.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                val key = when (selectedFolderType) {
                    FolderType.STAGING -> PREF_STAGING_URI
                    FolderType.FINAL -> PREF_FINAL_URI
                    else -> null
                }

                key?.let {
                    prefs.edit().putString(it, uri.toString()).apply()
                }
                selectedFolderType = null
            } else {
                Log.d(TAG, "No folder selected")
            }
        }

    private val prefs = context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE)
    private var selectedFolderType: FolderType? = null


    fun pickFolder(folderType: FolderType) {
        selectedFolderType = folderType
        try {
            pickFolderLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.resources.getString(R.string.error_folder_picker_open),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun getSavedFolderUri(folderType: FolderType): Uri? {
        val key = when (folderType) {
            FolderType.STAGING -> PREF_STAGING_URI
            FolderType.FINAL -> PREF_FINAL_URI
        }
        return prefs.getString(key, null)?.let { Uri.parse(it) }
    }
}