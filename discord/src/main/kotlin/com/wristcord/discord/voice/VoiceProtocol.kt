package com.wristcord.discord.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Discord's voice gateway (v8) and media transport, as documented on Discord's developer site
 * and implemented by discord.js's voice library. See docs/discord-protocol.md.
 */
object VoiceOp {
    const val IDENTIFY = 0
    const val SELECT_PROTOCOL = 1
    const val READY = 2
    const val HEARTBEAT = 3
    const val SESSION_DESCRIPTION = 4
    const val SPEAKING = 5
    const val HEARTBEAT_ACK = 6
    const val RESUME = 7
    const val HELLO = 8
    const val RESUMED = 9
    const val CLIENTS_CONNECT = 11
    const val CLIENT_DISCONNECT = 13
    const val DAVE_PREPARE_TRANSITION = 21
    const val DAVE_EXECUTE_TRANSITION = 22
    const val DAVE_TRANSITION_READY = 23
    const val DAVE_PREPARE_EPOCH = 24
    const val DAVE_MLS_EXTERNAL_SENDER = 25
    const val DAVE_MLS_KEY_PACKAGE = 26
    const val DAVE_MLS_PROPOSALS = 27
    const val DAVE_MLS_COMMIT_WELCOME = 28
    const val DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION = 29
    const val DAVE_MLS_WELCOME = 30
    const val DAVE_MLS_INVALID_COMMIT_WELCOME = 31

    /** Close codes after which resuming is pointless (discord.js: resume only on 4015 or < 4000). */
    fun canResume(code: Int) = code == 4015 || code < 4000

    fun describeClose(code: Int): String = when (code) {
        4006 -> "Voice session expired"
        4009 -> "Voice session timed out"
        4011 -> "Voice server not found"
        4014 -> "Disconnected from the call"
        4015 -> "Voice server restarted"
        4016 -> "Unsupported encryption mode"
        4017 -> "Discord requires end-to-end encryption for this call"
        4020 -> "Voice gateway rejected a request"
        4021 -> "Rate limited by the voice server"
        4022 -> "The call was ended"
        else -> "Voice connection closed ($code)"
    }
}

/** Server-to-client binary messages are `[seq u16 BE][opcode u8][payload]`. */
data class BinaryVoiceMessage(val sequence: Int, val opcode: Int, val payload: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray): BinaryVoiceMessage? {
            if (bytes.size < 3) return null
            val seq = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            return BinaryVoiceMessage(seq, bytes[2].toInt() and 0xFF, bytes.copyOfRange(3, bytes.size))
        }

        /** Client-to-server binary messages carry no sequence number. */
        fun frame(opcode: Int, payload: ByteArray): ByteArray = ByteArray(payload.size + 1).also {
            it[0] = opcode.toByte()
            payload.copyInto(it, 1)
        }
    }
}

object IpDiscovery {
    const val SIZE = 74

    fun request(ssrc: Int): ByteArray = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN).apply {
        putShort(1)
        putShort(70)
        putInt(ssrc)
    }.array()

    /** Response: type 2, then the externally visible address (NUL-terminated) and port. */
    fun parse(bytes: ByteArray): Pair<String, Int>? {
        if (bytes.size < SIZE) return null
        if (((bytes[0].toInt() and 0xFF) shl 8 or (bytes[1].toInt() and 0xFF)) != 2) return null
        var end = 8
        while (end < 72 && bytes[end] != 0.toByte()) end++
        val ip = String(bytes, 8, end - 8, Charsets.UTF_8)
        val port = ((bytes[SIZE - 2].toInt() and 0xFF) shl 8) or (bytes[SIZE - 1].toInt() and 0xFF)
        return ip to port
    }
}

/** An AEAD for the transport layer: [seal] returns ciphertext || tag. */
interface TransportCipher {
    val mode: String
    val nonceSize: Int
    fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
}

class AesGcmTransport(key: ByteArray) : TransportCipher {
    override val mode = MODE_AES_GCM
    override val nonceSize = 12
    private val spec = SecretKeySpec(key, "AES")
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")

    override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = synchronized(enc) {
        enc.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
        enc.updateAAD(aad)
        enc.doFinal(plaintext)
    }

    override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = synchronized(dec) {
        try {
            dec.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce))
            dec.updateAAD(aad)
            dec.doFinal(ciphertextAndTag)
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        const val MODE_AES_GCM = "aead_aes256_gcm_rtpsize"
        const val MODE_XCHACHA = "aead_xchacha20_poly1305_rtpsize"
    }
}

/**
 * Outgoing RTP for one SSRC: 12-byte header (0x80, payload type 0x78, sequence, timestamp,
 * SSRC) as AAD, then ciphertext and tag, then the 4-byte big-endian nonce counter that the
 * AEAD nonce is built from (zero-padded to the cipher's nonce size).
 */
class RtpSender(private val ssrc: Int, private val cipher: TransportCipher) {
    private val random = SecureRandom()
    private var sequence = random.nextInt(1 shl 16)
    private var timestamp = random.nextInt().toLong() and 0xFFFFFFFFL
    private var nonce = 0L

    @Synchronized
    fun packet(opus: ByteArray, samples: Int = FRAME_SAMPLES): ByteArray {
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x80.toByte())
            put(PAYLOAD_TYPE.toByte())
            putShort(sequence.toShort())
            putInt(timestamp.toInt())
            putInt(ssrc)
        }.array()
        sequence = (sequence + 1) and 0xFFFF
        timestamp = (timestamp + samples) and 0xFFFFFFFFL
        nonce = (nonce + 1) and 0xFFFFFFFFL
        val nonceBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nonce.toInt()).array()
        val fullNonce = ByteArray(cipher.nonceSize).also { nonceBytes.copyInto(it, 0) }
        val sealed = cipher.seal(fullNonce, header, opus)
        return header + sealed + nonceBytes
    }

    companion object {
        const val PAYLOAD_TYPE = 0x78
        const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz
    }
}

data class RtpPacket(val ssrc: Int, val sequence: Int, val timestamp: Long, val payload: ByteArray)

object RtpReceiver {
    /**
     * Incoming RTP: the unencrypted header is the fixed 12 bytes, the CSRCs, and (with the X
     * bit) the 4-byte extension preamble; the extension body is encrypted with the payload and
     * skipped after decryption. Returns null for anything that is not decryptable Opus RTP.
     */
    fun open(packet: ByteArray, cipher: TransportCipher): RtpPacket? {
        if (packet.size < 12 + 16 + 4) return null
        val first = packet[0].toInt() and 0xFF
        if (first shr 6 != 2) return null
        val pt = packet[1].toInt() and 0x7F
        if (pt != RtpSender.PAYLOAD_TYPE) return null // RTCP and anything else
        val cc = first and 0x0F
        val hasExtension = (first shr 4) and 1 == 1
        val hasPadding = (first shr 5) and 1 == 1
        var headerSize = 12 + 4 * cc
        val extOffset = headerSize
        if (hasExtension) headerSize += 4
        if (packet.size < headerSize + 16 + 4) return null
        val nonceBytes = packet.copyOfRange(packet.size - 4, packet.size)
        val fullNonce = ByteArray(cipher.nonceSize).also { nonceBytes.copyInto(it, 0) }
        val aad = packet.copyOfRange(0, headerSize)
        val body = packet.copyOfRange(headerSize, packet.size - 4)
        var payload = cipher.open(fullNonce, aad, body) ?: return null
        if (hasPadding && payload.isNotEmpty()) {
            val pad = payload.last().toInt() and 0xFF
            if (pad < payload.size) payload = payload.copyOfRange(0, payload.size - pad)
        }
        if (hasExtension) {
            val words = ((packet[extOffset + 2].toInt() and 0xFF) shl 8) or (packet[extOffset + 3].toInt() and 0xFF)
            val skip = 4 * words
            if (skip > payload.size) return null
            payload = payload.copyOfRange(skip, payload.size)
        }
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val ts = ByteBuffer.wrap(packet, 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val ssrc = ByteBuffer.wrap(packet, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        return RtpPacket(ssrc, seq, ts, payload)
    }

    fun ssrcOf(packet: ByteArray): Int? =
        if (packet.size < 12) null else ByteBuffer.wrap(packet, 8, 4).order(ByteOrder.BIG_ENDIAN).int
}

/** Opus "silence" frame. Five are sent whenever transmission stops. */
val OPUS_SILENCE = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())

/**
 * DAVE end-to-end encryption, implemented natively (davey) on the watch. Mirrors the calls
 * the voice gateway flow needs; see VoiceConnection for when each is made.
 */
interface DaveCrypto {
    fun reinit(protocolVersion: Int): Boolean
    fun reset()
    fun setExternalSender(data: ByteArray): Boolean
    fun createKeyPackage(): ByteArray?
    /** Returns the opcode-28 payload (commit || welcome), an empty array for "nothing to send", or null on error. */
    fun processProposals(operationType: Int, data: ByteArray, expectedUserIds: LongArray): ByteArray?
    fun processWelcome(data: ByteArray, recognizedUserIds: LongArray): Boolean
    /** 1 = applied, 0 = ignored, -1 = invalid. */
    fun processCommit(data: ByteArray): Int
    fun prepareTransition(transitionId: Int, protocolVersion: Int): Boolean
    fun executeTransition(transitionId: Int): Int
    fun encryptOpus(frame: ByteArray): ByteArray?
    fun decrypt(userId: Long, frame: ByteArray): ByteArray?
    val isReady: Boolean
    fun canPassthrough(userId: Long): Boolean
    fun setPassthroughMode(enabled: Boolean, expirySeconds: Int)
    val voicePrivacyCode: String
    val lastError: String
    fun close()
}
