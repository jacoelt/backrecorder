package com.backrecorderapp.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.backrecorderapp.data.preferences.CloudUploadMode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transport predicate both the sync loop and the saved-recording upload branch on.
 *
 * What it protects is a promise to the user: someone who chose Wi-Fi-only is told their uploads
 * wait for Wi-Fi, so a path that reads this wrong spends their mobile data.
 */
class CloudNetworkAvailabilityTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var capabilities: NetworkCapabilities

    @Before
    fun setup() {
        context = mockk()
        connectivityManager = mockk()
        capabilities = mockk()
        every { context.getSystemService(ConnectivityManager::class.java) } returns
            connectivityManager
        every { connectivityManager.activeNetwork } returns mockk()
        every { connectivityManager.getNetworkCapabilities(any()) } returns capabilities
    }

    private fun onWifi() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { capabilities.hasCapability(any()) } returns true
    }

    private fun onCellular() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns
            true
    }

    @Test
    fun `OFF never uploads, whatever the device is connected to`() {
        onWifi()
        assertFalse(isCloudNetworkAvailable(context, CloudUploadMode.OFF))
    }

    @Test
    fun `Wi-Fi-only uploads on Wi-Fi`() {
        onWifi()
        assertTrue(isCloudNetworkAvailable(context, CloudUploadMode.WIFI_ONLY))
    }

    @Test
    fun `Wi-Fi-only does not upload over cellular`() {
        onCellular()
        assertFalse(isCloudNetworkAvailable(context, CloudUploadMode.WIFI_ONLY))
    }

    @Test
    fun `always-on uploads over cellular`() {
        onCellular()
        assertTrue(isCloudNetworkAvailable(context, CloudUploadMode.ALWAYS_ON))
    }

    @Test
    fun `always-on does not upload on a network with no internet`() {
        every { capabilities.hasTransport(any()) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns
            false
        assertFalse(isCloudNetworkAvailable(context, CloudUploadMode.ALWAYS_ON))
    }

    @Test
    fun `no active network means no upload`() {
        every { connectivityManager.activeNetwork } returns null
        assertFalse(isCloudNetworkAvailable(context, CloudUploadMode.WIFI_ONLY))
        assertFalse(isCloudNetworkAvailable(context, CloudUploadMode.ALWAYS_ON))
    }

    @Test
    fun `a network whose capabilities cannot be read means no upload`() {
        every { connectivityManager.getNetworkCapabilities(any()) } returns null
        assertFalse(isCloudNetworkAvailable(context, CloudUploadMode.WIFI_ONLY))
    }
}
