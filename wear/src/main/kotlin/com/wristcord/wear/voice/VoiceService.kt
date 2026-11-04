package com.wristcord.wear.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wristcord.R
import com.wristcord.wear.MainActivity
import com.wristcord.wear.WristCordApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a call on the watch alive with the screen off - a `microphone` foreground service, as
 * Android requires for anything recording - and puts the call on the watch face as an Ongoing
 * Activity chip, one tap back to the remote.
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!foreground("Connecting…", "Discord call")) {
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristcord:call")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        val app = WristCordApp.of(this)
        scope.launch {
            app.voiceCall.state.collect { s ->
                when (s) {
                    is CallState.Live -> {
                        val name = app.discord.state.value.channels[s.channelId]?.let { app.discord.state.value.channelTitle(it) } ?: "Discord call"
                        val line = when {
                            s.headsetLost -> "Headset disconnected"
                            s.reconnecting -> "Reconnecting…"
                            s.deafened -> "Deafened"
                            s.muted -> "Muted"
                            else -> "Live"
                        }
                        foreground(line, name)
                    }
                    is CallState.Joining -> foreground(s.step + "…", "Discord call")
                    else -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun foreground(line: String, title: String): Boolean {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_REMOTE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return try {
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_tile)
                .setTouchIntent(open)
                .setStatus(Status.Builder().addTemplate("$title · $line").build())
                .build()
                .apply(applicationContext)
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the call service", t)
            false
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are in a Discord call on the watch."
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    companion object {
        private const val TAG = "WristCord/CallService"
        private const val CHANNEL_ID = "wristcord_call"
        private const val NOTIFICATION_ID = 8124

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, VoiceService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the call service", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, VoiceService::class.java)) }
        }
    }
}
