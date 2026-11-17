package com.backrecorderapp.domain

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #96 turns on a claim about the platform, not about this app's code: that a real provider
 * accepts the `"wt"` mode [SaveRecordingUseCase] now asks for, and that it genuinely shortens a
 * destination that already holds a longer file. Neither half is reachable from a JVM test, where
 * the resolver is a mock and the mode string is just an argument.
 *
 * The stand-in for the picker's destination is a MediaStore entry, since that is what the
 * documents UI hands back for a new file on this platform's own provider and the only real
 * provider an unattended test can create a writable URI on.
 */
@RunWith(AndroidJUnit4::class)
class TruncatingWriteModeTest {

    private val resolver =
        ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    private var destination: Uri? = null

    @After
    fun tearDown() {
        destination?.let { resolver.delete(it, null, null) }
    }

    @Test
    fun truncatingModeShortensAnExistingLongerFile() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val uri = createDestination()
        resolver.openOutputStream(uri, "w")!!.use { it.write(ByteArray(64)) }

        resolver.openOutputStream(uri, "wt")!!.use { it.write(ByteArray(8)) }

        val written = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals("the previous file's tail must not survive the save", 8, written.size)
    }

    private fun createDestination(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "backrecorder_truncate_test_${System.nanoTime()}.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "MediaStore would not create a destination to write to" }
        destination = uri
        return uri
    }
}
