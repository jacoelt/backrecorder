package com.backrecorderapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.backrecorderapp.domain.ComplimentaryCodeValidator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The store on a real file-backed DataStore, because everything else in the suite mocks it - and
 * what it does is the part of #98 that is easy to get wrong silently: the stored digest is
 * re-validated on every read, so retiring a code revokes the installs that already redeemed it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplimentaryAccessStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val issued = "TEST-AAAA-BBBB"
    private val alsoIssued = "TEST-CCCC-DDDD"

    private val acceptingBoth = ComplimentaryCodeValidator(
        accepted = setOf(
            ComplimentaryCodeValidator.digestOf(issued),
            ComplimentaryCodeValidator.digestOf(alsoIssued)
        ),
        blocked = emptySet()
    )

    @Before
    fun setup() {
        // The store logs its failure paths, and android.util.Log is not on the JVM classpath.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    private fun scope() = CoroutineScope(UnconfinedTestDispatcher())

    private fun fileStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope()) {
            // Not newFile(): DataStore reads an existing zero-byte file as corrupt.
            File(tempFolder.root, "$name.preferences_pb")
        }

    private fun store(
        dataStore: DataStore<Preferences>,
        validator: ComplimentaryCodeValidator = acceptingBoth
    ) = ComplimentaryAccessStore(dataStore, scope(), validator)

    @Test
    fun `redeeming an issued code grants access`() = runTest {
        val subject = store(fileStore("grant"))

        assertFalse(subject.isGranted())
        assertEquals(RedeemResult.ACCEPTED, subject.redeem(issued))
        assertTrue(subject.isGranted())
    }

    @Test
    fun `an unknown code is refused and stores nothing`() = runTest {
        val subject = store(fileStore("refused"))

        assertEquals(RedeemResult.REJECTED, subject.redeem("TEST-EEEE-FFFF"))
        assertFalse(subject.isGranted())
    }

    @Test
    fun `a new store over the same DataStore sees the stored grant`() = runTest {
        val dataStore = fileStore("persist")
        assertEquals(RedeemResult.ACCEPTED, store(dataStore).redeem(issued))

        assertTrue(store(dataStore).isGranted())
    }

    /**
     * The whole reason the digest is stored rather than a flag. A boolean would leave this install
     * premium forever, and the release retiring the code would change nothing for it.
     */
    @Test
    fun `retiring the redeemed code revokes a grant that was already stored`() = runTest {
        val dataStore = fileStore("revoke")
        assertEquals(RedeemResult.ACCEPTED, store(dataStore).redeem(issued))

        val afterRetirement = ComplimentaryCodeValidator(
            accepted = setOf(
                ComplimentaryCodeValidator.digestOf(issued),
                ComplimentaryCodeValidator.digestOf(alsoIssued)
            ),
            blocked = setOf(ComplimentaryCodeValidator.digestOf(issued))
        )

        assertFalse(store(dataStore, afterRetirement).isGranted())
    }

    @Test
    fun `retiring someone else's code leaves this grant alone`() = runTest {
        val dataStore = fileStore("revoke-other")
        assertEquals(RedeemResult.ACCEPTED, store(dataStore).redeem(issued))

        val afterRetirement = ComplimentaryCodeValidator(
            accepted = setOf(
                ComplimentaryCodeValidator.digestOf(issued),
                ComplimentaryCodeValidator.digestOf(alsoIssued)
            ),
            blocked = setOf(ComplimentaryCodeValidator.digestOf(alsoIssued))
        )

        assertTrue(store(dataStore, afterRetirement).isGranted())
    }

    /**
     * Entitlement must stay non-throwing: this flow is collected on the never-cancelled
     * application scope, and awaited by `isPremium()` on the save path.
     */
    @Test
    fun `an unreadable store reports not granted instead of throwing`() = runTest {
        val broken = mockk<DataStore<Preferences>>()
        every { broken.data } returns flow { throw IOException("corrupt") }

        assertFalse(store(broken).isGranted())
    }

    /** A throw here would escape `viewModelScope` in `redeemCode` and take the app down. */
    @Test
    fun `a failed write is reported as a refused redemption`() = runTest {
        val readOnly = mockk<DataStore<Preferences>>()
        every { readOnly.data } returns flow { throw IOException("read-only") }
        coEvery { readOnly.updateData(any()) } throws IOException("read-only")

        assertEquals(RedeemResult.STORAGE_FAILED, store(readOnly).redeem(issued))
    }
}
