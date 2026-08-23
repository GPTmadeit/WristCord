package com.wristcord.wear.link

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableStatusCodes
import com.wristcord.shared.Wire
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves the phone through [CapabilityClient] and sends it one command.
 *
 * The watch never assumes the send worked: [Sent] only means Play services accepted the
 * message. The authoritative answer comes back later as a `/wristcord/result` message that
 * [WearListenerService] pushes into [StateStore].
 */
class PhoneLink(private val ctx: Context) {

    sealed interface Result {
        /** Handed to Play services. The phone's verdict arrives asynchronously. */
        data object Sent : Result
        data class Failed(val message: String) : Result
    }

    suspend fun send(path: String, payload: ByteArray = ByteArray(0)): Result {
        val node = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolvePhone() }
            ?: return Result.Failed("Phone not reachable")

        if (node is NodeLookup.Failure) return Result.Failed(node.message)
        val nodeId = (node as NodeLookup.Found).id

        return try {
            withTimeoutOrNull(SEND_TIMEOUT_MS) {
                Wearable.getMessageClient(ctx).sendMessage(nodeId, path, payload).await()
            } ?: return Result.Failed("Phone did not answer in time")
            buzz()
            Result.Sent
        } catch (e: ApiException) {
            Result.Failed(explain(e))
        } catch (t: Throwable) {
            Log.w(TAG, "send failed", t)
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private sealed interface NodeLookup {
        data class Found(val id: String) : NodeLookup
        data class Failure(val message: String) : NodeLookup
    }

    private suspend fun resolvePhone(): NodeLookup = try {
        val info = Wearable.getCapabilityClient(ctx)
            .getCapability(Wire.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
        val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        if (node == null) {
            NodeLookup.Failure("WristCord is not installed on the phone, or the phone is away")
        } else {
            NodeLookup.Found(node.id)
        }
    } catch (e: ApiException) {
        NodeLookup.Failure(explain(e))
    } catch (t: Throwable) {
        NodeLookup.Failure(t.message ?: "Could not find the phone")
    }

    private fun explain(e: ApiException): String = when (e.statusCode) {
        // The one the prompt calls out: the phone is out of Bluetooth range or its
        // Wear connection dropped. Rendered as a distinct error state, not a generic failure.
        WearableStatusCodes.TARGET_NODE_NOT_CONNECTED -> "Phone not connected"
        WearableStatusCodes.INVALID_TARGET_NODE -> "Phone node went away"
        WearableStatusCodes.UNSUPPORTED_BY_TARGET_NODE -> "Phone app is too old"
        CommonStatusCodes.API_NOT_CONNECTED -> "Wear services unavailable on this watch"
        else -> "Wear error ${e.statusCode}"
    }

    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        } ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private companion object {
        const val TAG = "WristCord/Link"
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val SEND_TIMEOUT_MS = 8_000L
        const val HAPTIC_MS = 30L
    }
}
