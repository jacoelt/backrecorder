package com.backrecorderapp.data.cloud

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveCloudStorage @Inject constructor(
    private val driveAuthManager: DriveAuthManager
) {
    companion object {
        private const val ROOT_FOLDER = "BackRecorder"
        private const val STAGING_FOLDER = "staging"

        /** Drive caps `files.list` at 1000 per page; ask for the maximum to keep round-trips low. */
        private const val PAGE_SIZE = 1000
    }

    private val drive: Drive? get() = driveAuthManager.drive

    private val folderMutex = Mutex()

    private var rootFolderId: String? = null
    private var stagingFolderId: String? = null

    val isInitialized: Boolean get() = drive != null

    suspend fun ensureFolderStructure() = withContext(Dispatchers.IO) {
        val drive = drive ?: return@withContext

        folderMutex.withLock {
            if (stagingFolderId != null) return@withLock

            val root = getOrCreateFolder(drive, ROOT_FOLDER, null)
            rootFolderId = root
            stagingFolderId = getOrCreateFolder(drive, STAGING_FOLDER, root)
        }
    }

    /**
     * Drops the cached folder ids. Must be called when the grant changes, otherwise a
     * reconnect (possibly to a different account) would keep writing to folder ids that the
     * new grant cannot see.
     */
    suspend fun reset() = folderMutex.withLock {
        rootFolderId = null
        stagingFolderId = null
    }

    suspend fun uploadSegment(file: File): String? {
        ensureFolderStructure()
        return withContext(Dispatchers.IO) { uploadFile(file, stagingFolderId) }
    }

    private fun uploadFile(file: File, parentFolderId: String?): String? {
        val drive = drive ?: return null

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
        drive?.files()?.delete(fileId)?.execute()
        Unit
    }

    suspend fun moveToRecoveryFolder(fileIds: List<String>, recoveryFolderName: String) =
        withContext(Dispatchers.IO) {
            val drive = drive ?: return@withContext
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

    /**
     * Lists every staging file, oldest first.
     *
     * Paginates deliberately: a full 24h buffer is ~1440 one-minute segments, so a single
     * unpaginated page silently hides everything past the first 1000 and cleanup then never
     * catches up. This regressed once already — keep the loop.
     */
    suspend fun listStagingFiles(): List<DriveFileInfo> = withContext(Dispatchers.IO) {
        val drive = drive ?: return@withContext emptyList()
        val folderId = stagingFolderId ?: return@withContext emptyList()

        val files = mutableListOf<DriveFileInfo>()
        var pageToken: String? = null

        do {
            val result = drive.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setFields("nextPageToken, files(id, createdTime)")
                .setOrderBy("createdTime")
                .setPageSize(PAGE_SIZE)
                .setPageToken(pageToken)
                .execute()

            result.files?.forEach { file ->
                files += DriveFileInfo(
                    id = file.id,
                    createdTime = file.createdTime?.value ?: 0L
                )
            }

            pageToken = result.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)

        files
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
    val createdTime: Long
)
