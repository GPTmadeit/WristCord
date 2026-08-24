package com.wristcord.mobile.link

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.IconVariant
import com.wristcord.shared.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone owns the icon choice for both devices.
 *
 * Both APKs ship the same four aliases, so "wear this one" is a single string on the wire.
 * The phone applies it locally straight away and tells the watch; if the watch is out of range
 * the change is still stored locally on the phone and the watch picks up its own copy the next
 * time this runs, so the two can only disagree until the next time you open the app.
 */
object IconSync {

    private const val TAG = "WristCord/Icon"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun choose(ctx: Context, variant: IconVariant) {
        val app = ctx.applicationContext
        IconVariant.apply(app, variant)
        push(app, variant)
    }

    /** Re-sends the current choice - cheap, and repairs a watch that was away when it changed. */
    fun resend(ctx: Context) {
        push(ctx.applicationContext, IconVariant.current(ctx))
    }

    private fun push(app: Context, variant: IconVariant) {
        scope.launch {
            val sent = runCatching {
                withTimeoutOrNull(TIMEOUT_MS) {
                    val nodes = Wearable.getCapabilityClient(app)
                        .getCapability(Wire.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                        .await()
                        .nodes
                    if (nodes.isEmpty()) return@withTimeoutOrNull false
                    nodes.forEach { node ->
                        Wearable.getMessageClient(app)
                            .sendMessage(node.id, Wire.PATH_ICON, variant.id.toByteArray(Charsets.UTF_8))
                            .await()
                    }
                    true
                }
            }.getOrNull() ?: false

            if (sent) {
                EventLog.i(TAG, "icon \"${variant.label}\" applied on phone and watch")
            } else {
                EventLog.w(TAG, "icon \"${variant.label}\" applied on phone; watch not reachable")
            }
        }
    }

    private const val TIMEOUT_MS = 8_000L
}
