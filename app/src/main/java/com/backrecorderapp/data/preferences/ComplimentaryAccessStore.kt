package com.backrecorderapp.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.backrecorderapp.di.ApplicationScope
import com.backrecorderapp.domain.ComplimentaryCodeValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.complimentaryAccessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "complimentary_access"
)

/**
 * Whether this install has been granted complimentary premium access by redeeming a code (#98).
 *
 * Entitlement, never a billing status: spoofing `SubscriptionStatus.ACTIVE` would send
 * `updateSubscriptionStatus` hunting for a purchase token to acknowledge that does not exist, and
 * put these users on the same acknowledge-or-be-refunded path as real subscribers. The two terms
 * stay apart and are OR-ed in [com.backrecorderapp.domain.SubscriptionGate].
 *
 * What is stored is the **digest of the code that was redeemed**, not a bare flag, and every read
 * re-validates it. That is what lets a retired code actually revoke access on the installs that
 * already redeemed it - which is the only scenario the one-code-per-person split exists for.
 */
@Singleton
class ComplimentaryAccessStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
    private val validator: ComplimentaryCodeValidator
) {

    /**
     * A read failure degrades to "not granted" rather than propagating. DataStore emits an
     * IOException to its collectors on a corrupt or unreadable file, and this flow is both
     * collected on the never-cancelled application scope - which carries a `SupervisorJob` but no
     * exception handler, so an uncaught throw there kills the process on every launch - and
     * awaited by `SubscriptionGate.isPremium()`, which sits on the save path *outside*
     * `SaveRecordingUseCase.execute`'s error funnel. Catching here keeps the gate non-throwing,
     * as it was before entitlement had anything on disk behind it.
     */
    private val grantedFlow: Flow<Boolean> = context.complimentaryAccessDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Could not read the complimentary grant", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[KEY_REDEEMED_DIGEST]?.let(validator::acceptsDigest) ?: false }

    /**
     * For rendering and for the resolve gate. Starts `false` and flips once DataStore answers,
     * which is why [isGranted] exists: a decision taken in that window must not read the seed.
     */
    val granted: StateFlow<Boolean> =
        grantedFlow.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun isGranted(): Boolean = grantedFlow.first()

    /** True when [code] was accepted and the grant is stored. */
    suspend fun redeem(code: String): Boolean {
        if (!validator.accepts(code)) return false
        val digest = ComplimentaryCodeValidator.digestOf(code)
        return try {
            context.complimentaryAccessDataStore.edit { it[KEY_REDEEMED_DIGEST] = digest }
            true
        } catch (e: IOException) {
            // Reported as a refusal rather than thrown: this runs in a bare `viewModelScope`
            // launch, and the dialog already has a way to say the code did not take.
            Log.e(TAG, "Could not store the complimentary grant", e)
            false
        }
    }

    private companion object {
        const val TAG = "ComplimentaryAccess"
        val KEY_REDEEMED_DIGEST = stringPreferencesKey("complimentary_code_digest")
    }
}
