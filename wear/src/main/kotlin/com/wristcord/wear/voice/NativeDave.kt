package com.wristcord.wear.voice

import android.util.Log
import com.wristcord.discord.voice.AesGcmTransport
import com.wristcord.discord.voice.DaveCrypto
import com.wristcord.discord.voice.TransportCipher
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Discord's DAVE end-to-end encryption, through libwristcord_dave.so (native/dave): a JNI
 * bridge to davey, the Rust implementation of the protocol. Discord has refused voice
 * connections without DAVE since March 2026.
 *
 * Every call takes the read lock; [close] takes the write lock, so an audio thread can never
 * touch a session that has been freed.
 */
class NativeDave private constructor(private var handle: Long, private val userId: Long, private val channelId: Long) : DaveCrypto {

    private val lock = ReentrantReadWriteLock()

    private inline fun <T> call(fallback: T, block: (Long) -> T): T = lock.read {
        val h = handle
        if (h == 0L) fallback else block(h)
    }

    override fun reinit(protocolVersion: Int): Boolean = call(false) { nativeReinit(it, protocolVersion, userId, channelId) }
    override fun reset() { call(Unit) { nativeReset(it) } }
    override fun setExternalSender(data: ByteArray): Boolean = call(false) { nativeSetExternalSender(it, data) }
    override fun createKeyPackage(): ByteArray? = call(null) { nativeCreateKeyPackage(it) }

    override fun processProposals(operationType: Int, data: ByteArray, expectedUserIds: LongArray): ByteArray? = call(null) { h ->
        val r = nativeProcessProposals(h, operationType, data, expectedUserIds) ?: return@call null
        when (r.firstOrNull()?.toInt()) {
            0 -> ByteArray(0)
            1 -> r.copyOfRange(1, r.size)
            else -> null
        }
    }

    override fun processWelcome(data: ByteArray, recognizedUserIds: LongArray): Boolean =
        call(false) { nativeProcessWelcome(it, data, recognizedUserIds) }

    override fun processCommit(data: ByteArray): Int = call(-1) { nativeProcessCommit(it, data) }
    override fun prepareTransition(transitionId: Int, protocolVersion: Int): Boolean =
        call(false) { nativePrepareTransition(it, transitionId, protocolVersion) }
    override fun executeTransition(transitionId: Int): Int = call(-1) { nativeExecuteTransition(it, transitionId) }
    override fun encryptOpus(frame: ByteArray): ByteArray? = call(null) { nativeEncryptOpus(it, frame) }
    override fun decrypt(userId: Long, frame: ByteArray): ByteArray? = call(null) { nativeDecrypt(it, userId, frame) }
    override val isReady: Boolean get() = call(false) { nativeIsReady(it) }
    override fun canPassthrough(userId: Long): Boolean = call(false) { nativeCanPassthrough(it, userId) }
    override fun setPassthroughMode(enabled: Boolean, expirySeconds: Int) { call(Unit) { nativeSetPassthroughMode(it, enabled, expirySeconds) } }
    override val voicePrivacyCode: String get() = call("") { nativeVoicePrivacyCode(it) ?: "" }
    override val lastError: String get() = call("") { nativeLastError(it) ?: "" }

    override fun close() {
        lock.write {
            val h = handle
            handle = 0
            if (h != 0L) nativeDestroy(h)
        }
    }

    companion object {
        private const val TAG = "WristCord/DAVE"

        val available: Boolean = runCatching { System.loadLibrary("wristcord_dave") }
            .onFailure { Log.e(TAG, "native DAVE library missing", it) }
            .isSuccess

        val maxProtocolVersion: Int get() = if (available) nativeMaxProtocolVersion() else 0

        fun create(protocolVersion: Int, userId: Long, channelId: Long): NativeDave? {
            if (!available) return null
            val h = nativeCreate(protocolVersion, userId, channelId)
            return if (h == 0L) null else NativeDave(h, userId, channelId)
        }

        /** The XChaCha20-Poly1305 transport mode, which Java's crypto providers lack. */
        fun xchacha(key: ByteArray): TransportCipher = object : TransportCipher {
            override val mode = AesGcmTransport.MODE_XCHACHA
            override val nonceSize = 24
            override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
                nativeXChaChaSeal(key, nonce, aad, plaintext) ?: error("XChaCha seal failed")
            override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? =
                nativeXChaChaOpen(key, nonce, aad, ciphertextAndTag)
        }

        @JvmStatic private external fun nativeMaxProtocolVersion(): Int
        @JvmStatic private external fun nativeCreate(protocolVersion: Int, userId: Long, channelId: Long): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeReinit(handle: Long, protocolVersion: Int, userId: Long, channelId: Long): Boolean
        @JvmStatic private external fun nativeReset(handle: Long): Boolean
        @JvmStatic private external fun nativeSetExternalSender(handle: Long, data: ByteArray): Boolean
        @JvmStatic private external fun nativeCreateKeyPackage(handle: Long): ByteArray?
        @JvmStatic private external fun nativeProcessProposals(handle: Long, operationType: Int, data: ByteArray, expected: LongArray?): ByteArray?
        @JvmStatic private external fun nativeProcessWelcome(handle: Long, data: ByteArray, recognized: LongArray?): Boolean
        @JvmStatic private external fun nativeProcessCommit(handle: Long, data: ByteArray): Int
        @JvmStatic private external fun nativePrepareTransition(handle: Long, transitionId: Int, protocolVersion: Int): Boolean
        @JvmStatic private external fun nativeExecuteTransition(handle: Long, transitionId: Int): Int
        @JvmStatic private external fun nativeEncryptOpus(handle: Long, frame: ByteArray): ByteArray?
        @JvmStatic private external fun nativeDecrypt(handle: Long, userId: Long, frame: ByteArray): ByteArray?
        @JvmStatic private external fun nativeIsReady(handle: Long): Boolean
        @JvmStatic private external fun nativeCanEncrypt(handle: Long): Boolean
        @JvmStatic private external fun nativeCanPassthrough(handle: Long, userId: Long): Boolean
        @JvmStatic private external fun nativeSetPassthroughMode(handle: Long, enabled: Boolean, expirySeconds: Int)
        @JvmStatic private external fun nativeVoicePrivacyCode(handle: Long): String?
        @JvmStatic private external fun nativeLastError(handle: Long): String?
        @JvmStatic private external fun nativeXChaChaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray?
        @JvmStatic private external fun nativeXChaChaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?
    }
}
