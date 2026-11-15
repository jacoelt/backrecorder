package com.backrecorderapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.backrecorderapp.di.ApplicationScope
import com.backrecorderapp.domain.ComplimentaryCodeValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 */
@Singleton
class ComplimentaryAccessStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
    private val validator: ComplimentaryCodeValidator
) {

    private val grantedFlow: Flow<Boolean> = context.complimentaryAccessDataStore.data
        .map { it[KEY_GRANTED] ?: false }

    /**
     * For rendering and for the resolve gate. Starts `false` and flips once DataStore answers,
     * which is why [isGranted] exists: a decision taken in that window must not read the seed.
     */
    val granted: StateFlow<Boolean> =
        grantedFlow.stateIn(scope, SharingStarted.Eagerly, false)

    /** The authoritative read, awaiting DataStore rather than answering from [granted]'s seed. */
    suspend fun isGranted(): Boolean = grantedFlow.first()

    /** True when [code] was accepted and the grant is stored. */
    suspend fun redeem(code: String): Boolean {
        if (!validator.accepts(code)) return false
        context.complimentaryAccessDataStore.edit { it[KEY_GRANTED] = true }
        return true
    }

    private companion object {
        val KEY_GRANTED = booleanPreferencesKey("complimentary_granted")
    }
}
