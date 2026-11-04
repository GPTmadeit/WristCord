package com.wristcord.wear.voice

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAttributes
import android.telecom.CallControl
import android.telecom.CallControlCallback
import android.telecom.CallEndpoint
import android.telecom.CallEventCallback
import android.telecom.CallException
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Debug builds only (`--ez com.wristcord.SCOTEST true`): does what a call does to reach the
 * headset (call mode, a silent voice-call stream, setCommunicationDevice) without Discord, and
 * logs what Android does with it under WristCord/ScoProbe. Pair with
 * `adb logcat -s WristCord/ScoProbe AS.AudioDeviceBroker AS.BtHelper HeadsetService`.
 */
object ScoProbe {
    private const val TAG = "WristCord/ScoProbe"

    fun run(ctx: Context) {
        runCatching { telecom(ctx) }.onFailure { Log.e(TAG, "telecom probe failed", it) }
        runCatching { media(ctx) }.onFailure { Log.e(TAG, "media probe failed", it) }
        runCatching { direct(ctx) }.onFailure { Log.e(TAG, "direct probe failed", it) }
    }

    private fun silentTrack(usage: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private fun feed(track: AudioTrack): Thread = Thread {
        val silence = ShortArray(960)
        track.play()
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.write(silence, 0, silence.size)
    }.apply { start() }

    /** A self-managed Telecom call (Android's VoIP path), asking for the Bluetooth endpoint. */
    private fun telecom(ctx: Context) {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        val handle = PhoneAccountHandle(ComponentName(ctx, com.wristcord.wear.MainActivity::class.java), "wristcord-voice")
        tm.registerPhoneAccount(
            PhoneAccount.builder(handle, "WristCord")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS)
                .build()
        )
        val ex = Executors.newSingleThreadExecutor()
        val controls = LinkedBlockingQueue<CallControl>()
        val endpoints = LinkedBlockingQueue<List<CallEndpoint>>()
        fun <T> logged(what: String) = object : OutcomeReceiver<T, CallException> {
            override fun onResult(result: T) { Log.i(TAG, "telecom $what ok") }
            override fun onError(error: CallException) { Log.w(TAG, "telecom $what failed: ${error.code} ${error.message}") }
        }
        val attrs = CallAttributes.Builder(handle, CallAttributes.DIRECTION_OUTGOING, "Discord voice", Uri.parse("wristcord:probe"))
            .setCallType(CallAttributes.AUDIO_CALL)
            .setCallCapabilities(CallAttributes.SUPPORTS_SET_INACTIVE)
            .build()
        tm.addCall(attrs, ex, object : OutcomeReceiver<CallControl, CallException> {
            override fun onResult(result: CallControl) { Log.i(TAG, "telecom addCall ok"); controls += result }
            override fun onError(error: CallException) { Log.w(TAG, "telecom addCall failed: ${error.code} ${error.message}") }
        }, object : CallControlCallback {
            override fun onSetActive(wasCompleted: Consumer<Boolean>) = wasCompleted.accept(true)
            override fun onSetInactive(wasCompleted: Consumer<Boolean>) = wasCompleted.accept(true)
            override fun onAnswer(videoState: Int, wasCompleted: Consumer<Boolean>) = wasCompleted.accept(true)
            override fun onDisconnect(cause: DisconnectCause, wasCompleted: Consumer<Boolean>) = wasCompleted.accept(true)
            override fun onCallStreamingStarted(wasCompleted: Consumer<Boolean>) = wasCompleted.accept(false)
        }, object : CallEventCallback {
            override fun onCallEndpointChanged(newCallEndpoint: CallEndpoint) { Log.i(TAG, "telecom endpoint -> ${newCallEndpoint.endpointName} (type ${newCallEndpoint.endpointType})") }
            override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
                Log.i(TAG, "telecom endpoints: " + availableEndpoints.joinToString { "${it.endpointName}/${it.endpointType}" })
                endpoints += availableEndpoints
            }
            override fun onMuteStateChanged(isMuted: Boolean) {}
            override fun onCallStreamingFailed(reason: Int) {}
            override fun onEvent(event: String, extras: Bundle) {}
        })
        val control = controls.poll(5, TimeUnit.SECONDS) ?: return
        control.setActive(ex, logged("setActive"))
        val track = silentTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        val writer = feed(track)
        val bt = generateSequence { endpoints.poll(3, TimeUnit.SECONDS) }.take(3).flatten()
            .firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        if (bt != null) control.requestCallEndpointChange(bt, ex, logged("endpoint change to ${bt.endpointName}"))
        else Log.w(TAG, "telecom: no Bluetooth endpoint offered")
        repeat(8) { i ->
            Thread.sleep(500)
            Log.i(TAG, "telecom t+${(i + 1) * 500}ms: mode=${am.mode} communication=${am.communicationDevice?.let(Headset::describe)} track=${Headset.describe(track.routedDevice)}")
        }
        control.disconnect(DisconnectCause(DisconnectCause.LOCAL), ex, logged("disconnect"))
        track.stop()
        writer.join(500)
        track.release()
        Thread.sleep(1_000)
        tm.unregisterPhoneAccount(handle)
    }

    /** Plain media playback (A2DP) with the watch's own mic. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun media(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val track = silentTrack(AudioAttributes.USAGE_MEDIA)
        val writer = feed(track)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 48_000)
        record.startRecording()
        val buf = ShortArray(960)
        repeat(4) { i ->
            Thread.sleep(500)
            record.read(buf, 0, buf.size)
            Log.i(TAG, "media t+${(i + 1) * 500}ms: mode=${am.mode} track=${Headset.describe(track.routedDevice)} mic=${record.routedDevice?.let { "${it.type}:${it.productName}" }}")
        }
        record.stop()
        record.release()
        track.stop()
        writer.join(500)
        track.release()
    }

    /** What beta.2/beta.3 did: call mode and setCommunicationDevice. */
    private fun direct(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val listener = AudioManager.OnCommunicationDeviceChangedListener { d ->
            Log.i(TAG, "communication device changed -> ${d?.let { "${Headset.describe(it)} (${it.productName})" }}")
        }
        am.addOnCommunicationDeviceChangedListener(Executors.newSingleThreadExecutor(), listener)
        val devices = am.availableCommunicationDevices
        Log.i(TAG, "available: " + devices.joinToString { "${it.type}:${it.productName}" })
        val headset = devices.firstOrNull(Headset::isWireless)
        if (headset == null) {
            Log.i(TAG, "no wireless headset available")
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val silence = ShortArray(960)
        val writer = Thread {
            track.play()
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.write(silence, 0, silence.size)
        }.apply { start() }
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "mode -> ${am.mode}")
            val ok = am.setCommunicationDevice(headset)
            Log.i(TAG, "setCommunicationDevice(${Headset.describe(headset)}) = $ok")
            repeat(6) { i ->
                Thread.sleep(500)
                Log.i(TAG, "t+${(i + 1) * 500}ms: communication=${am.communicationDevice?.let(Headset::describe)} track=${Headset.describe(track.routedDevice)} scoOn=${am.isBluetoothScoOn}")
            }
            // The pre-Android-12 path, for comparison.
            @Suppress("DEPRECATION")
            run {
                Log.i(TAG, "legacy startBluetoothSco()")
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
            repeat(6) { i ->
                Thread.sleep(500)
                Log.i(TAG, "legacy t+${(i + 1) * 500}ms: communication=${am.communicationDevice?.let(Headset::describe)} track=${Headset.describe(track.routedDevice)} scoOn=${am.isBluetoothScoOn}")
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
            runCatching { am.clearCommunicationDevice() }
            am.mode = AudioManager.MODE_NORMAL
            track.stop()
            writer.join(500)
            track.release()
            am.removeOnCommunicationDeviceChangedListener(listener)
            Log.i(TAG, "done")
        }
    }
}
