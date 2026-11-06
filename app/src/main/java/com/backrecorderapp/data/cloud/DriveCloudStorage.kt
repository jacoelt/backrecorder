package com.backrecorderapp.data.cloud

import android.util.Log
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The folders this app owns on Drive, resolved together so they cannot disagree. */
data class DriveFolders(val rootId: String, val stagingId: String)

@Singleton
class DriveCloudStorage @Inject constructor(
    private val driveAuthManager: DriveAuthManager
) {
    companion object {
        private const val TAG = "DriveCloudStorage"
        private const val ROOT_FOLDER = "BackRecorder"
        private const val STAGING_FOLDER = "staging"

        /** Drive's own mime type for folders. Staging listings must exclude them. */
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        /** Drive caps `files.list` at 1000 per page; ask for the maximum to keep round-trips low. */
        private const val PAGE_SIZE = 1000

        /**
         * A healthy staging folder holds ~1440 one-minute segments, so twenty pages is far more
         * headroom than any real backlog needs. The cap stops a pathologically large folder from
         * holding every id in memory and spending the whole API quota inside one sync tick.
         */
        private const val MAX_PAGES = 20

        /** Recovery moves are two write requests per file; pace them so Drive does not throttle. */
        private const val RECOVERY_BATCH_SIZE = 50
        private const val RECOVERY_BATCH_PAUSE_MS = 1_000L
    }

    private val drive: Drive? get() = driveAuthManager.drive

    private val folderMutex = Mutex()

    private var cachedFolders: DriveFolders? = null
    private var cachedGrantGeneration = -1

    val isInitialized: Boolean get() = drive != null

    /**
     * Resolves (creating if needed) `BackRecorder/staging`, or null when Drive is unauthorized.
     *
     * Callers must use the returned ids and never re-read a cached field afterwards. The cache is
     * keyed to [DriveAuthManager.grantGeneration], so a reconnect - possibly to a different
     * account - can invalidate it between this call and the use. Writing to a stale id would
     * silently target the previous account's folder; writing to a null one drops the file into
     * the root of the user's Drive, where nothing here can ever find or clean it up again.
     */
    suspend fun ensureFolderStructure(): DriveFolders? = withContext(Dispatchers.IO) {
        val drive = drive ?: return@withContext null

        folderMutex.withLock {
            val generation = driveAuthManager.grantGeneration
            if (generation != cachedGrantGeneration) {
                cachedFolders = null
                cachedGrantGeneration = generation
            }

            cachedFolders?.let { return@withLock it }

            val rootId = getOrCreateFolder(drive, ROOT_FOLDER, null)
            val stagingId = getOrCreateFolder(drive, STAGING_FOLDER, rootId)
            DriveFolders(rootId, stagingId).also { cachedFolders = it }
        }
    }

    suspend fun uploadSegment(file: File): String? {
        val folders = ensureFolderStructure() ?: return null
        return withContext(Dispatchers.IO) { uploadFile(file, folders.stagingId) }
    }

    private fun uploadFile(file: File, parentFolderId: String): String? {
        val drive = drive ?: return null

        val metadata = com.google.api.services.drive.model.File().apply {
            name = file.name
            parents = listOf(parentFolderId)
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

    /**
     * Moves [fileIds] out of staging into a recovery folder, returning how many no longer need
     * moving - those relocated, plus any that had no parent to move out of - so the caller can
     * tell a partial run from a complete one and retry the remainder.
     *
     * The recovery folder is created under the root, not inside staging: anything inside staging
     * is fair game for [listStagingFiles] and the buffer cleanup, which would delete the folder -
     * and every recording the user was just told had been preserved in it - once it aged past the
     * buffer window.
     */
    suspend fun moveToRecoveryFolder(
        fileIds: List<String>,
        recoveryFolderName: String
    ): Int = withContext(Dispatchers.IO) {
        val folders = ensureFolderStructure() ?: return@withContext 0
        val drive = drive ?: return@withContext 0

        val recoveryFolderId = getOrCreateFolder(drive, recoveryFolderName, folders.rootId)

        var handled = 0
        val batches = fileIds.chunked(RECOVERY_BATCH_SIZE)
        batches.forEachIndexed { index, batch ->
            for (fileId in batch) {
                try {
                    val file = drive.files().get(fileId).setFields("parents").execute()
                    val previousParents = file.parents?.joinToString(",")
                    if (previousParents.isNullOrBlank()) {
                        // Nothing to move it out of. Counted as handled so one orphan cannot
                        // hold the caller at "incomplete" and re-run this scan forever.
                        Log.w(TAG, "Stale file has no parent to move it out of; skipping")
                        handled++
                        continue
                    }

                    drive.files().update(fileId, null)
                        .setAddParents(recoveryFolderId)
                        .setRemoveParents(previousParents)
                        .execute()
                    handled++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpResponseException) {
                    // 401/403 are not per-file problems - the whole run is doomed until the user
                    // reconnects or the quota clears, so let the sync loop see and classify it.
                    if (e.statusCode == 401 || e.statusCode == 403) throw e
                    Log.e(TAG, "Failed to move a stale file into the recovery folder", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to move a stale file into the recovery folder", e)
                }
            }
            if (index < batches.lastIndex) delay(RECOVERY_BATCH_PAUSE_MS)
        }
        handled
    }

    /**
     * Lists every staging file, oldest first.
     *
     * Paginates deliberately: a full 24h buffer is ~1440 one-minute segments, so a single
     * unpaginated page silently hides everything past the first 1000 and cleanup then never
     * catches up. Keep the loop.
     *
     * Folders are excluded from the query. A recovery folder listed here would be treated as an
     * ordinary aged segment and deleted along with everything inside it.
     */
    suspend fun listStagingFiles(): List<DriveFileInfo> = withContext(Dispatchers.IO) {
        val folders = ensureFolderStructure() ?: return@withContext emptyList()
        val drive = drive ?: return@withContext emptyList()

        val files = mutableListOf<DriveFileInfo>()
        var pageToken: String? = null
        var pages = 0

        do {
            val result = drive.files().list()
                .setQ(
                    "'${folders.stagingId}' in parents and trashed = false " +
                        "and mimeType != '$FOLDER_MIME_TYPE'"
                )
                .setFields("nextPageToken, files(id, createdTime)")
                .setOrderBy("createdTime")
                .setPageSize(PAGE_SIZE)
                .setPageToken(pageToken)
                .execute()

            result.files?.forEach { file ->
                // A missing createdTime read as the epoch would make a freshly uploaded segment
                // look older than the whole buffer window, and cleanup would delete it on the
                // very next pass. Skipping it costs one retained file; defaulting costs the file.
                val createdTime = file.createdTime?.value
                if (createdTime == null) {
                    Log.w(TAG, "Skipping a staging file with no createdTime")
                } else {
                    files += DriveFileInfo(id = file.id, createdTime = createdTime)
                }
            }

            pages++
            pageToken = result.nextPageToken?.takeIf { it.isNotEmpty() }
            if (pageToken != null && pages >= MAX_PAGES) {
                Log.w(TAG, "Stopping staging listing at $MAX_PAGES pages (${files.size} files)")
                pageToken = null
            }
        } while (pageToken != null)

        files
    }

    /**
     * Drive's query language quotes literals with `'`, so a name containing one would break out
     * of the literal and could match - and then write to - the wrong folder.
     */
    private fun escapeQueryLiteral(value: String): String =
        value.replace("""\""", """\\""").replace("'", """\'""")

    private fun getOrCreateFolder(drive: Drive, name: String, parentId: String?): String {
        val escapedName = escapeQueryLiteral(name)
        val query = buildString {
            append("name = '$escapedName' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false")
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

        val folderMetadata = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = FOLDER_MIME_TYPE
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
