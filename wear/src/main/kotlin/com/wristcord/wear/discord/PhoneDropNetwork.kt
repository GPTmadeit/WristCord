package com.wristcord.wear.discord

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * The address a phone can reach the watch on. A watch next to its phone normally has Wi-Fi off
 * and uses Bluetooth, which no browser can connect to, so Wi-Fi is asked for by name, as
 * [com.wristcord.wear.voice.CallNetwork] does for calls, and held for as long as the screen is open.
 */
class PhoneDropNetwork(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        ctx.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WristCord:phone-drop")
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    suspend fun acquire(timeoutMs: Long = 20_000): Inet4Address? {
        runCatching { wifiLock?.acquire() }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                        val address = link.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
                        if (address != null && cont.isActive) cont.resume(address)
                    }
                }
                callback = cb
                runCatching { cm.requestNetwork(request, cb) }.onFailure {
                    Log.w(TAG, "Wi-Fi request refused", it)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    fun release() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private companion object {
        const val TAG = "WristCord/PhoneDrop"
    }
}
