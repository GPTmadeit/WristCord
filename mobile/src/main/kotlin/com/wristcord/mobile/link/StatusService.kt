package com.wristcord.mobile.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wristcord.R
import com.wristcord.mobile.discord.DiscordController
import com.wristcord.mobile.ui.MainActivity
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Optional. Keeps a status notification on the phone showing whether the watch is reachable and
 * what Discord is currently doing, with Mute and Leave right there in the shade.
 *
 * WristCord already works without this - the notification listener keeps the process alive and
 * Play services starts the message listener on demand. What a foreground service buys is
 * visibility (you can see at a glance that the link is up) and a little insurance against
 * aggressive battery managers on some phones. It is off by default because a permanent
 * notification is a real cost, and nothing depends on it.
 */
class StatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    @Volatile
    private var watchReachable = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            build(StatePublisher.get(this).state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        EventLog.i(TAG, "status notification on")

        scope.launch {
            StatePublisher.get(this@StatusService).state.collectLatest { state ->
                notify(state)
            }
        }
        watchJob = scope.launch {
            while (true) {
                val reachable = runCatching {
                    Wearable.getCapabilityClient(this@StatusService)
                        .getCapability(Wire.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                        .await()
                        .nodes
                        .isNotEmpty()
                }.getOrDefault(false)
                if (reachable != watchReachable) {
                    watchReachable = reachable
                    notify(StatePublisher.get(this@StatusService).state.value)
                }
                delay(WATCH_POLL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        EventLog.i(TAG, "status notification off")
        super.onDestroy()
    }

    private fun notify(state: VoiceState) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(state))
        }
    }

    private fun build(state: VoiceState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            state.channel.isNotBlank() -> state.channel
            state.inCall -> "In a Discord call"
            else -> "WristCord"
        }

        val bits = buildList {
            add(if (watchReachable) "Watch connected" else "Watch not reachable")
            if (state.blocker.isNotBlank()) {
                add(state.blocker)
            } else if (state.inCall) {
                add(if (state.muted == Toggle.ON) "Muted" else "Live")
                if (state.deafened == Toggle.ON) add("Deafened")
                state.outputs.firstOrNull { it.active }?.let { add(it.label) }
            } else {
                add("Not in a voice call")
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title)
            .setContentText(bits.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bits.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.inCall) {
            builder.addAction(
                0,
                if (state.muted == Toggle.ON) "Unmute" else "Mute",
                commandIntent(Wire.PATH_MUTE),
            )
            builder.addAction(0, "Leave", commandIntent(Wire.PATH_DISCONNECT))
        }
        builder.addAction(0, "Hide", stopIntent())

        return builder.build()
    }

    private fun commandIntent(path: String): PendingIntent = PendingIntent.getBroadcast(
        this,
        path.hashCode(),
        Intent(this, StatusActionReceiver::class.java)
            .setPackage(packageName)
            .putExtra(StatusActionReceiver.EXTRA_PATH, path),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, StatusService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WristCord status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows whether the watch link is up and what Discord is doing."
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WristCord/Status"
        private const val CHANNEL_ID = "wristcord_status"
        private const val NOTIFICATION_ID = 4711
        private const val WATCH_POLL_MS = 60_000L
        private const val PREFS = "wristcord_status"
        private const val KEY_ENABLED = "enabled"

        const val ACTION_STOP = "com.wristcord.STATUS_STOP"

        fun isEnabled(ctx: Context): Boolean =
            prefs(ctx).getBoolean(KEY_ENABLED, false)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) start(ctx) else stop(ctx)
        }

        /** Called on boot and on app start so the choice survives a restart. */
        fun startIfEnabled(ctx: Context) {
            if (isEnabled(ctx)) start(ctx)
        }

        private fun start(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, StatusService::class.java))
            }.onFailure { EventLog.e(TAG, "could not start status service: ${it.message}") }
        }

        private fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, StatusService::class.java)) }
        }

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/** Runs the Mute / Leave actions offered in the status notification. */
class StatusActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        if (path !in Wire.ALL_ACTION_PATHS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                DiscordController(app).handle(path, ByteArray(0))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "com.wristcord.STATUS_PATH"
    }
}

/** Restores the status notification after a reboot, if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            StatusService.startIfEnabled(context)
        }
    }
}
