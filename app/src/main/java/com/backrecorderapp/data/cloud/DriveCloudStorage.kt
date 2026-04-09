package com.backrecorderapp.data.cloud

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveCloudStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val APP_NAME = "BackRecorder"
        private const val ROOT_FOLDER = "BackRecorder"
        private const val STAGING_FOLDER = "staging"
    }

    private var driveService: Drive? = null
    private var rootFolderId: String? = null
    private var stagingFolderId: String? = null

    fun initialize(accountEmail: String) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccountName = accountEmail

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    val isInitialized: Boolean get() = driveService != null

    suspend fun ensureFolderStructure() = withContext(Dispatchers.IO) {
        val drive = driveService ?: return@withContext
        rootFolderId = getOrCreateFolder(drive, ROOT_FOLDER, null)
        stagingFolderId = getOrCreateFolder(drive, STAGING_FOLDER, rootFolderId)
    }

    suspend fun uploadSegment(file: File): String? = withContext(Dispatchers.IO) {
        uploadFile(file, stagingFolderId)
    }

    suspend fun uploadSavedRecording(file: File): String? = withContext(Dispatchers.IO) {
        uploadFile(file, rootFolderId)
    }

    private fun uploadFile(file: File, parentFolderId: String?): String? {
        val drive = driveService ?: return null

        val metadata = com.google.api.services.drive.model.File().apply {
            name = file.name
            if (parentFolderId != null) {
                parents = listOf(parentFolderId)
            }
        }

        val mediaContent = FileContent("audio/mp4", file)
        val uploaded = drive.files().create(metadata, mediaContent)
            .setFields("id")
            .execute()

        return uploaded.id
    }

    suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        driveService?.files()?.delete(fileId)?.execute()
    }

    suspend fun moveToRecoveryFolder(fileIds: List<String>, recoveryFolderName: String) =
        withContext(Dispatchers.IO) {
            val drive = driveService ?: return@withContext
            val recoveryFolderId = getOrCreateFolder(drive, recoveryFolderName, stagingFolderId)

            for (fileId in fileIds) {
                // Get current parents
                val file = drive.files().get(fileId).setFields("parents").execute()
                val previousParents = file.parents?.joinToString(",") ?: continue

                // Move to recovery folder
                drive.files().update(fileId, null)
                    .setAddParents(recoveryFolderId)
                    .setRemoveParents(previousParents)
                    .execute()
            }
        }

    suspend fun listStagingFiles(): List<DriveFileInfo> = withContext(Dispatchers.IO) {
        val drive = driveService ?: return@withContext emptyList()
        val folderId = stagingFolderId ?: return@withContext emptyList()

        val result = drive.files().list()
            .setQ("'$folderId' in parents and trashed = false")
            .setFields("files(id, name, createdTime)")
            .setOrderBy("createdTime")
            .execute()

        result.files?.map { file ->
            DriveFileInfo(
                id = file.id,
                name = file.name,
                createdTime = file.createdTime?.value ?: 0L
            )
        } ?: emptyList()
    }

    private fun getOrCreateFolder(drive: Drive, name: String, parentId: String?): String {
        // Check if folder exists
        val query = buildString {
            append("name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            if (parentId != null) {
                append(" and '$parentId' in parents")
            }
        }

        val existing = drive.files().list()
            .setQ(query)
            .setFields("files(id)")
            .execute()

        if (existing.files?.isNotEmpty() == true) {
            return existing.files[0].id
        }

        // Create folder
        val folderMetadata = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) {
                this.parents = listOf(parentId)
            }
        }

        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()

        return folder.id
    }
}

data class DriveFileInfo(
    val id: String,
    val name: String,
    val createdTime: Long
)
