package com.wristcord.wear.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.wristcord.wear.WristCordApp
import kotlinx.coroutines.launch

/** Reply and mark-as-read, which work without opening the app, and swiping one away. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = WristCordApp.of(context)
        val channelId = intent.getLongExtra(EXTRA_CHANNEL, 0L).takeIf { it != 0L } ?: return
        val guildId = intent.getLongExtra(EXTRA_GUILD, 0L).takeIf { it != 0L }
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, 0L)
        Log.i(TAG, "action ${intent.action}")
        when (intent.action) {
            ACTION_DISMISSED -> app.notifier.forget(channelId)
            ACTION_MARK_READ -> {
                app.notifier.clearChannel(channelId)
                if (messageId == 0L || !app.account.ensureToken()) return
                val pending = goAsync()
                app.scope.launch {
                    try {
                        app.discord.markReadDirect(channelId, messageId, guildId)
                    } catch (t: Exception) {
                        Log.w(TAG, "mark as read failed: ${t.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val pending = goAsync()
                app.scope.launch {
                    try {
                        if (!app.account.ensureToken()) throw IllegalStateException("signed out")
                        app.discord.replyDirect(channelId, guildId, text)
                        app.notifier.clearChannel(channelId)
                    } catch (t: Exception) {
                        Log.w(TAG, "reply failed: ${t.javaClass.simpleName}")
                        app.notifier.replyFailed(channelId, text)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "WristCord/Notify"
        const val ACTION_REPLY = "com.wristcord.notify.REPLY"
        const val ACTION_MARK_READ = "com.wristcord.notify.MARK_READ"
        const val ACTION_DISMISSED = "com.wristcord.notify.DISMISSED"
        const val KEY_TEXT = "text"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_GUILD = "guild"
        const val EXTRA_MESSAGE = "message"
    }
}
