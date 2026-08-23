package com.wristcord.mobile.discord

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.wristcord.mobile.link.StatePublisher
import com.wristcord.mobile.util.EventLog

/**
 * The only component allowed to call [getActiveNotifications]. Everything else reads Discord's
 * notifications through [snapshot].
 */
class DiscordNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        EventLog.i(TAG, "Notification listener connected")
        StatePublisher.get(applicationContext).refresh("listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        EventLog.w(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && Discord.isDiscord(sbn.packageName)) {
            // Discord re-posts the call notification on every mute/deafen change, which makes
            // this the cheapest possible state-change signal.
            StatePublisher.get(applicationContext).refresh("notification posted")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && Discord.isDiscord(sbn.packageName)) {
            StatePublisher.get(applicationContext).refresh("notification removed")
        }
    }

    companion object {
        private const val TAG = "WristCord/NLS"

        @Volatile
        private var instance: DiscordNotificationListener? = null

        fun connected(): DiscordNotificationListener? = instance

        /** Every live Discord notification, or null when we are not bound. */
        fun snapshot(): List<StatusBarNotification>? {
            val svc = instance ?: return null
            return try {
                svc.activeNotifications.orEmpty().filter { Discord.isDiscord(it.packageName) }
            } catch (t: Throwable) {
                Log.w(TAG, "getActiveNotifications failed", t)
                null
            }
        }

        /**
         * Discord's ongoing voice notification, chosen by how many of our known call actions it
         * carries rather than by channel id (which is not stable across releases).
         */
        fun voiceNotification(ctx: Context): VoiceNotification? {
            val all = snapshot() ?: return null
            return all.asSequence()
                .map { VoiceNotification.of(ctx, it) }
                .filter { it.score > 0 }
                .sortedWith(compareByDescending<VoiceNotification> { it.ongoing }.thenByDescending { it.score })
                .firstOrNull()
        }

        fun isGranted(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(ctx, DiscordNotificationListener::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** A Discord notification pre-parsed into the bits the strategies care about. */
class VoiceNotification(
    val sbn: StatusBarNotification,
    val channelId: String,
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val actions: List<Notification.Action>,
    val contentIntent: PendingIntent?,
    val score: Int,
) {
    fun findAction(pattern: Regex): Notification.Action? =
        actions.firstOrNull { pattern.containsMatchIn(it.title?.toString().orEmpty()) }

    fun hasAction(pattern: Regex): Boolean = findAction(pattern) != null

    companion object {
        fun of(ctx: Context, sbn: StatusBarNotification): VoiceNotification {
            val n = sbn.notification
            val actions = n.actions?.toList().orEmpty()
            val mute = DiscordSelectors.pattern(ctx, DiscordSelectors.Slot.NOTIF_MUTE)
            val disconnect = DiscordSelectors.pattern(ctx, DiscordSelectors.Slot.NOTIF_DISCONNECT)
            val deafen = DiscordSelectors.pattern(ctx, DiscordSelectors.Slot.NOTIF_DEAFEN)
            var score = 0
            for (a in actions) {
                val t = a.title?.toString().orEmpty()
                if (mute.containsMatchIn(t)) score += 2
                if (disconnect.containsMatchIn(t)) score += 2
                if (deafen.containsMatchIn(t)) score += 1
            }
            return VoiceNotification(
                sbn = sbn,
                channelId = n.channelId.orEmpty(),
                title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                actions = actions,
                contentIntent = n.contentIntent,
                score = score,
            )
        }
    }
}
