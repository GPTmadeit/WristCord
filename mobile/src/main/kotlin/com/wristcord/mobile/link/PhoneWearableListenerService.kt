package com.wristcord.mobile.link

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.Wearable
import com.wristcord.mobile.discord.DiscordController
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.ActionResult
import com.wristcord.shared.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Entry point for everything the watch asks for. Google Play services starts this process even
 * when nothing of ours is running, which is what makes screen-off mute work.
 *
 * onMessageReceived already runs off the main thread, so blocking here is safe; the timeout is
 * what stops a wedged accessibility walk from holding the binder.
 */
class PhoneWearableListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path !in Wire.ALL_ACTION_PATHS) {
            EventLog.w(TAG, "ignoring unknown path ${event.path}")
            return
        }
        EventLog.i(TAG, "watch -> ${event.path}")

        val result = runBlocking {
            withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                DiscordController(applicationContext).handle(event.path, event.data)
            }
        } ?: ActionResult(
            path = event.path,
            ok = false,
            message = "Phone timed out after ${ACTION_TIMEOUT_MS / 1000}s",
            strategy = "timeout",
        )

        // The watch gets an answer for every single command - success or failure.
        runBlocking {
            runCatching {
                withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                    Wearable.getMessageClient(applicationContext)
                        .sendMessage(event.sourceNodeId, Wire.PATH_RESULT, result.toBytes())
                        .await()
                }
            }.onFailure { EventLog.e(TAG, "could not reply to watch: ${it.message}") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        StatePublisher.get(applicationContext).refresh("listener service created")
    }

    private companion object {
        const val TAG = "WristCord/Link"
        const val ACTION_TIMEOUT_MS = 12_000L
        const val REPLY_TIMEOUT_MS = 5_000L
    }
}
