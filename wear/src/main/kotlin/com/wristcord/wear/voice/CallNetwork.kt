package com.wristcord.wear.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A watch usually reaches the internet through the phone's Bluetooth proxy, which is fine for
 * chat but is the wrong pipe for a live call's UDP stream. For the length of a call the watch
 * asks Wear OS for Wi-Fi or LTE, the documented way to get a high-bandwidth network, and binds
 * the media socket to it. If neither comes up in a few seconds the call uses whatever is there.
 *
 * It also holds a low-latency Wi-Fi lock for the call. In power save, the access point holds
 * a sleeping watch's packets and hands them over in bunches, which a live call hears as gaps.
 */
class CallNetwork(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        ctx.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WristCord:call")
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    @Volatile var network: Network? = null
        private set

    suspend fun acquire(timeoutMs: Long = 4_000): Network? {
        runCatching { wifiLock?.acquire() }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val got = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) {
                        network = n
                        if (cont.isActive) cont.resume(n)
                    }

                    override fun onLost(n: Network) {
                        if (network == n) network = null
                    }
                }
                callback = cb
                runCatching { cm.requestNetwork(request, cb) }.onFailure {
                    Log.w(TAG, "high-bandwidth network request refused", it)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (got == null) Log.i(TAG, "no Wi-Fi/LTE for the call; using the default network")
        return got
    }

    fun release() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    private companion object {
        const val TAG = "WristCord/CallNet"
    }
}
