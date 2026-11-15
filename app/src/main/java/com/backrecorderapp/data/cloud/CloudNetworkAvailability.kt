package com.backrecorderapp.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.backrecorderapp.data.preferences.CloudUploadMode

/**
 * Whether the current transport satisfies [mode].
 *
 * Shared by the sync loop and the saved-recording upload rather than duplicated: a Wi-Fi-only
 * user who is told their segments wait for Wi-Fi would not expect a saved recording to go out
 * over cellular, and two copies of this predicate would eventually disagree about that.
 */
internal fun isCloudNetworkAvailable(context: Context, mode: CloudUploadMode): Boolean {
    if (mode == CloudUploadMode.OFF) return false

    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    return when (mode) {
        CloudUploadMode.WIFI_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        CloudUploadMode.ALWAYS_ON ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        CloudUploadMode.OFF -> false
    }
}
