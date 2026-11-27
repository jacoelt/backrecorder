package com.backrecorderapp.domain

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.backrecorderapp.di.ApplicationScope
import com.backrecorderapp.di.IoDispatcher
import com.backrecorderapp.notification.SaveFailureNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class UpgradeRequired(val reason: BlockReason) : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/** Proof of holding the one save slot; only the holder may start a save or give the slot back. */
class SaveClaim internal constructor()

/**
 * Owns the save's in-flight claim and its lifetime, process-wide (issue #111).
 *
 * The save outlives the screen that started it, so neither its claim nor its outcome can live in
 * a view model: a relaunched screen read a fresh `saveInProgress = false` and started a second
 * save over the first, racing it through a flush rotation and the cache temp files, and a failure
 * after the user left was written into a state nobody collected.
 *
 * The claim is taken **before** the picker opens, not when it returns: the picker creates its
 * 0-byte file the instant it answers, so a refusal after that point leaves the empty recording
 * #23 exists to prevent - and a claim taken at the tap is also what stops a double tap opening two
 * pickers.
 */
@Singleton
class SaveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveRecordingUseCase: SaveRecordingUseCase,
    private val failureNotifier: SaveFailureNotifier,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private companion object {
        const val TAG = "SaveCoordinator"
        const val UNEXPECTED_FAILURE = "Could not save the recording"
    }

    private val lock = Any()
    private var holder: SaveClaim? = null
    private var savingTo: Uri? = null

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Unlimited for the reason `FlushCoordinator` and the uploader's failures are: a channel
     * buffers with no receiver, so an outcome reached while no screen is open is delivered to the
     * next one rather than dropped.
     */
    private val outcomeChannel = Channel<SaveOutcome>(Channel.UNLIMITED)
    private val observers = AtomicInteger(0)

    val outcomes: Flow<SaveOutcome> = outcomeChannel.receiveAsFlow()
        .onStart { observers.incrementAndGet() }
        .onCompletion { observers.decrementAndGet() }

    fun tryClaim(): SaveClaim? = synchronized(lock) {
        if (holder != null) null else SaveClaim().also { holder = it }
    }

    /** Gives back a claim whose save never started; a running save keeps its slot regardless. */
    fun release(claim: SaveClaim) {
        synchronized(lock) {
            if (holder === claim && savingTo == null) holder = null
        }
    }

    /** Returns false, and starts nothing, when [claim] is not the live, unstarted claim. */
    fun save(claim: SaveClaim, startTime: Long, endTime: Long, outputUri: Uri): Boolean {
        synchronized(lock) {
            if (holder !== claim || savingTo != null) return false
            savingTo = outputUri
            _saving.value = true
        }
        applicationScope.launch {
            try {
                publish(runSave(startTime, endTime, outputUri))
            } finally {
                synchronized(lock) {
                    holder = null
                    savingTo = null
                    _saving.value = false
                }
            }
        }
        return true
    }

    /**
     * Deletes the 0-byte file a picker created for a save that cannot run - but never the file the
     * running save is writing: both pickers suggest the same name, and a provider that overwrites
     * rather than uniquifies hands back the same document.
     */
    fun discardPickerResult(outputUri: Uri) {
        val inFlight = synchronized(lock) { savingTo }
        if (outputUri == inFlight) return
        applicationScope.launch { deleteOutputUri(outputUri) }
    }

    private suspend fun runSave(startTime: Long, endTime: Long, outputUri: Uri): SaveOutcome {
        val outcome = try {
            when (val result = saveRecordingUseCase.execute(startTime, endTime, outputUri)) {
                is SaveResult.Success -> SaveOutcome.Saved
                is SaveResult.UpgradeRequired -> SaveOutcome.UpgradeRequired(result.reason)
                is SaveResult.Error -> SaveOutcome.Failed(result.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // `getSegmentsInRange` and `checkSaveAccess` sit outside the use case's own `try`, and
            // the application scope has no handler: an escaping throw would kill the process.
            // Raw SQLite or DataStore text means nothing to the person reading the message.
            Log.e(TAG, "Save failed", e)
            SaveOutcome.Failed(UNEXPECTED_FAILURE)
        }
        if (outcome != SaveOutcome.Saved) deleteOutputUri(outputUri)
        return outcome
    }

    /**
     * Always queued for the screen, and also notified when no started screen is collecting - the
     * case where the in-app message alone would sit unseen until the next launch, or be lost with
     * the process.
     */
    private fun publish(outcome: SaveOutcome) {
        outcomeChannel.trySend(outcome)
        if (outcome !is SaveOutcome.Failed || observers.get() > 0) return
        try {
            failureNotifier.notifySaveFailed()
        } catch (e: Exception) {
            Log.w(TAG, "Could not post the save-failure notification", e)
        }
    }

    // SAF creates the destination as soon as the picker returns a URI; a save that fails must not
    // leave that empty file on the user's storage (issue #23). The delete is a binder call into
    // whatever provider the user picked, possibly a cloud-backed one, so it stays off the main
    // thread (#93).
    private suspend fun deleteOutputUri(uri: Uri) = withContext(ioDispatcher) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            // Best-effort cleanup; ignore if the provider rejects deletion.
        }
    }
}
