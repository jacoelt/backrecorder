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
 * Checks on a real provider what no JVM test can, where the resolver is a mock and the mode is
 * just an argument: that the `"wt"` [SaveRecordingUseCase] now asks for is accepted, and that it
 * genuinely shortens a destination already holding a longer file (#96).
 *
 * The stand-in for the picker's destination is a MediaStore entry - the only real provider an
 * unattended test can mint a writable URI on.
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
