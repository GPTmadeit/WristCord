package com.wristcord.wear.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import com.wristcord.R
import com.wristcord.discord.Cdn
import com.wristcord.discord.Snowflake
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.Message
import com.wristcord.discord.model.User
import com.wristcord.discord.state.NotificationPolicy
import com.wristcord.discord.state.Snapshot
import com.wristcord.discord.state.StoreEvent
import com.wristcord.wear.MainActivity
import com.wristcord.wear.WristCordApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns Discord events into notifications: one conversation per channel with its recent messages,
 * a reply box and mark-as-read, gone again as soon as it is read anywhere.
 */
class MessageNotifier(private val app: WristCordApp) {
    private val nm = NotificationManagerCompat.from(app)
    private val power = app.getSystemService(PowerManager::class.java)

    private class Line(val id: Long, val sender: Person?, val text: String, val timeMs: Long)
    private class Conversation(val lines: ArrayList<Line> = ArrayList(), var guildId: Long? = null)

    private val conversations = ConcurrentHashMap<Long, Conversation>()
    private val avatars = ConcurrentHashMap<Long, Bitmap>()
    @Volatile private var visibleChannel: Long? = null

    fun start() {
        NotificationPrefs.load(app)
        createChannels()
        app.scope.launch {
            app.discord.state.events.collect { e ->
                when (e) {
                    is StoreEvent.NewMessage -> onMessage(e.message, alert = true)
                    is StoreEvent.MessageRead -> clearChannel(e.channelId)
                    is StoreEvent.MessageDeleted -> onDeleted(e.channelId, e.messageId)
                    is StoreEvent.CallRinging -> onRinging(e.channelId)
                    is StoreEvent.CallStopped -> nm.cancel(TAG_CALL + e.channelId, ID_CALL)
                    StoreEvent.Synced -> catchUp()
                    else -> Unit
                }
            }
        }
    }

    fun setVisibleChannel(channelId: Long?) {
        visibleChannel = channelId
    }

    fun clearChannel(channelId: Long) {
        conversations.remove(channelId)
        nm.cancel(TAG_CHANNEL + channelId, ID_MESSAGE)
        nm.cancel(TAG_FAILED + channelId, ID_FAILED)
        updateSummary()
    }

    fun clearAll() {
        conversations.clear()
        avatars.clear()
        nm.cancelAll()
        // The connection notification belongs to the service, which restarts it if still wanted.
    }

    /**
     * Puts a failed reply back in front of its owner, with the words they would otherwise lose.
     * It is a notification of its own: Wear OS dismisses the one that was replied to, along with
     * any update posted to it.
     */
    fun replyFailed(channelId: Long, text: String) {
        val notification = NotificationCompat.Builder(app, CHANNEL_DMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reply not sent")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openIntent(MainActivity.EXTRA_OPEN_CHANNEL, channelId))
            .build()
        notifySafely(TAG_FAILED + channelId, ID_FAILED, notification)
    }

    private suspend fun onMessage(m: Message, alert: Boolean) {
        val mode = NotificationPrefs.mode.value
        if (mode == NotificationPrefs.Mode.OFF) return
        val snapshot = app.discord.state.value
        val kind = NotificationPolicy.decide(snapshot, m, dmsAndMentionsOnly = mode == NotificationPrefs.Mode.MENTIONS)
        noteSeen(m.id)
        if (kind == NotificationPolicy.Kind.NONE) return
        if (visibleChannel == m.channelId && power.isInteractive) return

        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristCord:notify").apply { setReferenceCounted(false); acquire(WAKE_MS) }
        try {
            val conversation = conversations.getOrPut(m.channelId) { Conversation() }
            val line = Line(m.id, person(m.author, m), NotificationPolicy.text(snapshot, m), Snowflake.timestampMs(m.id))
            synchronized(conversation) {
                if (conversation.lines.any { it.id == m.id }) return
                conversation.guildId = m.guildId ?: snapshot.channels[m.channelId]?.guildId
                conversation.lines += line
                conversation.lines.sortBy { it.timeMs }
                while (conversation.lines.size > MAX_LINES) conversation.lines.removeAt(0)
            }
            post(m.channelId, conversation, snapshot, kind, alert)
            updateSummary()
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun onDeleted(channelId: Long, messageId: Long) {
        val conversation = conversations[channelId] ?: return
        val empty = synchronized(conversation) {
            if (!conversation.lines.removeAll { it.id == messageId }) return
            conversation.lines.isEmpty()
        }
        if (empty) clearChannel(channelId)
        else post(channelId, conversation, app.discord.state.value, NotificationPolicy.Kind.MESSAGE, alert = false)
    }

    /**
     * After a full reconnect. A resume replays what was missed by itself, but READY does not, and
     * a watch back from a long sleep or a restart has only read states to go on.
     */
    private suspend fun catchUp() {
        val snapshot = app.discord.state.value
        // Whatever was read elsewhere while away should not still be on the wrist.
        for (id in conversations.keys) if (!snapshot.isUnread(id)) clearChannel(id)
        val mode = NotificationPrefs.mode.value
        val now = System.currentTimeMillis()
        val nowId = Snowflake.fromTimestamp(now)
        val mark = NotificationPrefs.watermark(app)
        // A first run or a long gap must not dump a day's worth of history on the wrist.
        val since = if (mark == 0L) nowId else maxOf(mark, Snowflake.fromTimestamp(now - MAX_CATCH_UP_MS))
        NotificationPrefs.setWatermark(app, nowId)
        if (mode == NotificationPrefs.Mode.OFF || app.discord.token == null) return
        val missed = runCatching { app.discord.missedMessages(since, mode == NotificationPrefs.Mode.MENTIONS) }
            .onFailure { Log.w(TAG, "catch-up failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        Log.i(TAG, "catch-up: ${missed.size} missed")
        missed.forEachIndexed { i, m -> onMessage(m, alert = i == missed.lastIndex) }
    }

    private fun noteSeen(id: Long) {
        if (id > NotificationPrefs.watermark(app)) NotificationPrefs.setWatermark(app, id)
    }

    private fun onRinging(channelId: Long) {
        val snapshot = app.discord.state.value
        val channel = snapshot.channels[channelId]
        if (NotificationPrefs.mode.value == NotificationPrefs.Mode.OFF || snapshot.settings.status == "dnd") return
        if (channel != null && snapshot.isChannelMuted(channel)) return
        val title = channel?.let { snapshot.channelTitle(it) } ?: "Discord call"
        val notification = NotificationCompat.Builder(app, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(title)
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_MS)
            .setContentIntent(openIntent(MainActivity.EXTRA_OPEN_CALL, channelId))
            .build()
        notifySafely(TAG_CALL + channelId, ID_CALL, notification)
    }

    private fun post(channelId: Long, conversation: Conversation, snapshot: Snapshot, kind: NotificationPolicy.Kind, alert: Boolean) {
        val channel = snapshot.channels[channelId]
        val lines = synchronized(conversation) { conversation.lines.toList() }
        val last = lines.lastOrNull() ?: return
        val me = Person.Builder().setName(snapshot.me?.displayName ?: "You").setKey(snapshot.me?.id?.toString() ?: "me").build()
        val isDirect = channel == null && conversation.guildId == null || channel?.isDm == true
        val title = when {
            channel == null -> "Discord"
            channel.isDm -> snapshot.channelTitle(channel)
            else -> "#${snapshot.channelTitle(channel)} · ${snapshot.guilds[conversation.guildId ?: 0L]?.name ?: "Discord"}"
        }
        val isGroup = channel != null && channel.type != ChannelType.DM
        val style = NotificationCompat.MessagingStyle(me)
        // A one-to-one chat's title would only repeat the sender's name.
        if (isGroup) style.setConversationTitle(title)
        style.isGroupConversation = isGroup
        for (line in lines) style.addMessage(NotificationCompat.MessagingStyle.Message(line.text, line.timeMs, line.sender))

        val latestRead = lines.maxOf { it.id }
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_notification, "Reply",
            actionIntent(NotificationActionReceiver.ACTION_REPLY, channelId, conversation.guildId, latestRead, mutable = true),
        )
            .addRemoteInput(RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT).setLabel("Reply").build())
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val read = NotificationCompat.Action.Builder(
            R.drawable.ic_notification, "Mark as read",
            actionIntent(NotificationActionReceiver.ACTION_MARK_READ, channelId, conversation.guildId, latestRead, mutable = false),
        )
            .setShowsUserInterface(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        val notification = NotificationCompat.Builder(app, when {
            kind == NotificationPolicy.Kind.DIRECT || isDirect -> CHANNEL_DMS
            kind == NotificationPolicy.Kind.MENTION -> CHANNEL_MENTIONS
            else -> CHANNEL_MESSAGES
        })
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(MainActivity.EXTRA_OPEN_CHANNEL, channelId))
            .setDeleteIntent(actionIntent(NotificationActionReceiver.ACTION_DISMISSED, channelId, conversation.guildId, latestRead, mutable = false))
            .setAutoCancel(true)
            .setWhen(last.timeMs)
            .setShowWhen(true)
            .setSilent(!alert)
            .setGroup(GROUP)
            .addAction(reply)
            .addAction(read)
            .build()
        notifySafely(TAG_CHANNEL + channelId, ID_MESSAGE, notification)
    }

    private fun updateSummary() {
        val count = conversations.size
        if (count < 2) {
            nm.cancel(ID_SUMMARY)
            return
        }
        val summary = NotificationCompat.Builder(app, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Discord")
            .setContentText("$count conversations")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        notifySafely(null, ID_SUMMARY, summary)
    }

    /** The conversation is gone from the wrist, by swiping or tapping; forget its lines. */
    fun forget(channelId: Long) {
        conversations.remove(channelId)
        updateSummary()
    }

    private fun notifySafely(tag: String?, id: Int, notification: android.app.Notification) {
        // POST_NOTIFICATIONS can be revoked at any moment; the call then throws or does nothing.
        runCatching { nm.notify(tag, id, notification) }.onFailure { Log.w(TAG, "could not post: ${it.javaClass.simpleName}") }
    }

    private suspend fun person(user: User?, m: Message): Person {
        val builder = Person.Builder()
            .setName(m.member?.nick ?: user?.displayName ?: "Unknown")
            .setKey(user?.id?.toString() ?: "unknown")
        if (user != null) avatar(user)?.let { builder.setIcon(IconCompat.createWithBitmap(it)) }
        return builder.build()
    }

    /** A picture is nice, but a message must not wait long for one. */
    private suspend fun avatar(user: User): Bitmap? {
        avatars[user.id]?.let { return it }
        val request = ImageRequest.Builder(app)
            .data(Cdn.avatar(user, 64))
            .size(Size(AVATAR_PX, AVATAR_PX))
            .allowHardware(false)
            .transformations(CircleCropTransformation())
            .build()
        val bitmap = withTimeoutOrNull(AVATAR_TIMEOUT_MS) {
            runCatching { SingletonImageLoader.get(app).execute(request).image?.toBitmap() }.getOrNull()
        } ?: return null
        if (avatars.size >= MAX_AVATARS) avatars.clear()
        avatars[user.id] = bitmap
        return bitmap
    }

    private fun openIntent(extra: String, channelId: Long): PendingIntent = PendingIntent.getActivity(
        app,
        (extra + channelId).hashCode(),
        Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(extra, channelId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, channelId: Long, guildId: Long?, messageId: Long, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
        app,
        (action + channelId).hashCode(),
        Intent(app, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_CHANNEL, channelId)
            .putExtra(NotificationActionReceiver.EXTRA_GUILD, guildId ?: 0L)
            .putExtra(NotificationActionReceiver.EXTRA_MESSAGE, messageId),
        // Only a RemoteInput needs mutable: the system writes the reply text into it.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
    )

    private fun createChannels() {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        fun channel(id: String, name: String, importance: Int, about: String) =
            NotificationChannel(id, name, importance).apply { description = about }
        manager.createNotificationChannels(
            listOf(
                channel(CHANNEL_DMS, "Direct messages", NotificationManager.IMPORTANCE_HIGH, "DMs and group DMs"),
                channel(CHANNEL_MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH, "When someone mentions you in a server"),
                channel(CHANNEL_MESSAGES, "Server messages", NotificationManager.IMPORTANCE_DEFAULT, "Channels set to notify for all messages"),
                channel(CHANNEL_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH, "When a DM or group call rings you"),
            ),
        )
    }

    private companion object {
        const val TAG = "WristCord/Notify"
        const val CHANNEL_DMS = "wristcord_dms"
        const val CHANNEL_MENTIONS = "wristcord_mentions"
        const val CHANNEL_MESSAGES = "wristcord_messages"
        const val CHANNEL_CALLS = "wristcord_incoming_calls"
        const val GROUP = "wristcord_messages"
        const val TAG_CHANNEL = "channel-"
        const val TAG_CALL = "call-"
        const val TAG_FAILED = "failed-"
        const val ID_MESSAGE = 8200
        const val ID_CALL = 8201
        const val ID_SUMMARY = 8202
        const val ID_FAILED = 8203
        const val MAX_LINES = 6
        const val MAX_AVATARS = 32
        const val AVATAR_PX = 96
        const val AVATAR_TIMEOUT_MS = 2_500L
        const val WAKE_MS = 10_000L
        const val RING_MS = 45_000L
        const val MAX_CATCH_UP_MS = 24 * 60 * 60_000L
    }
}
