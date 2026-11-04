package com.wristcord.discord.remoteauth

import com.wristcord.discord.Discord
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.rest.DiscordRest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * "Log in with QR code", the same flow Discord's own app uses to sign a new device in
 * (modules/remote_auth/useAuthWebsocket.tsx). The watch shows the QR; the phone, already signed
 * in, scans and approves it. No password and no MFA prompt ever touch the watch.
 */
class RemoteAuth(
    private val http: OkHttpClient,
    private val rest: DiscordRest,
    private val scope: CoroutineScope,
) {
    sealed interface Step {
        data object Connecting : Step
        /** Show [qrUrl] as a QR code. */
        data class ShowQr(val qrUrl: String, val expiresAtMs: Long) : Step
        /** The phone scanned it and is asking its owner to confirm. */
        data class Scanned(val user: ScannedUser) : Step
        data object LoggingIn : Step
        data class Success(val token: String) : Step
        data object Cancelled : Step
        data class Failed(val message: String, val captcha: Boolean = false) : Step
    }

    data class ScannedUser(val id: Long, val discriminator: String, val avatar: String?, val username: String)

    private val _step = MutableStateFlow<Step>(Step.Connecting)
    val step: StateFlow<Step> = _step

    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var keys: KeyPair? = null
    @Volatile private var generation = 0

    fun start() {
        stop()
        val gen = ++generation
        _step.value = Step.Connecting
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        keys = pair
        val request = Request.Builder()
            .url(Discord.REMOTE_AUTH_URL)
            // The remote-auth gateway refuses connections that do not come from Discord's origin.
            .header("Origin", Discord.ORIGIN)
            .header("User-Agent", Discord.USER_AGENT)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                runCatching { handle(webSocket, pair, DiscordJson.parseToJsonElement(text).jsonObject) }
                    .onFailure { fail(gen, "QR login failed: ${it.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (gen != generation) return
                val s = _step.value
                // The QR expires after `timeout_ms`; a fresh one is generated rather than an error.
                if (s is Step.ShowQr || s is Step.Connecting) scope.launch { if (gen == generation) start() }
                else if (s !is Step.Success && s !is Step.LoggingIn && s !is Step.Cancelled) {
                    fail(gen, "Discord closed the QR session ($code)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(gen, t.message ?: "Could not reach Discord")
            }
        })
    }

    fun stop() {
        generation++
        heartbeat?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    private fun handle(ws: WebSocket, pair: KeyPair, msg: JsonObject) {
        when ((msg["op"] as? JsonPrimitive)?.content) {
            "hello" -> {
                val interval = (msg["heartbeat_interval"] as? JsonPrimitive)?.longOrNull ?: 41_250L
                val timeout = (msg["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 120_000L
                expiresAt = System.currentTimeMillis() + timeout
                startHeartbeat(ws, interval)
                ws.send(buildJsonObject {
                    put("op", "init")
                    put("encoded_public_key", Base64.getEncoder().encodeToString(pair.public.encoded))
                }.toString())
            }
            "nonce_proof" -> {
                val encrypted = (msg["encrypted_nonce"] as JsonPrimitive).content
                val nonce = decrypt(pair, encrypted)
                // v2 sends the decrypted nonce back, base64 with the URL-safe alphabet but padding
                // kept (RemoteAuthCrypto.android.tsx: replace(/\//g,"_").replace(/\+/g,"-")).
                val proof = Base64.getEncoder().encodeToString(nonce).replace('/', '_').replace('+', '-')
                ws.send(buildJsonObject {
                    put("op", "nonce_proof")
                    put("nonce", proof)
                }.toString())
            }
            "pending_remote_init" -> {
                val fingerprint = (msg["fingerprint"] as JsonPrimitive).content
                val ours = fingerprint(pair)
                if (ours != fingerprint) {
                    fail(generation, "Discord answered for a different key")
                    return
                }
                _step.value = Step.ShowQr(Discord.REMOTE_AUTH_QR_PREFIX + fingerprint, expiresAt)
            }
            "pending_ticket" -> {
                val payload = String(decrypt(pair, (msg["encrypted_user_payload"] as JsonPrimitive).content), Charsets.UTF_8)
                _step.value = Step.Scanned(parseUser(payload))
            }
            "pending_login" -> {
                val ticket = (msg["ticket"] as JsonPrimitive).content
                _step.value = Step.LoggingIn
                val gen = generation
                scope.launch {
                    try {
                        val encrypted = rest.remoteAuthLogin(ticket)
                        val token = String(decrypt(pair, encrypted), Charsets.UTF_8)
                        if (gen == generation) _step.value = Step.Success(token)
                    } catch (e: com.wristcord.discord.rest.DiscordHttpException) {
                        if (gen == generation) _step.value = Step.Failed(
                            if (e.status == 400 && e.message?.contains("captcha", true) == true)
                                "Discord wants a captcha for this login. Try again in a few minutes."
                            else e.message ?: "Login failed",
                            captcha = e.message?.contains("captcha", true) == true,
                        )
                    } catch (t: Throwable) {
                        if (gen == generation) _step.value = Step.Failed(t.message ?: "Login failed")
                    } finally {
                        stop()
                    }
                }
            }
            "cancel" -> {
                _step.value = Step.Cancelled
                stop()
            }
            "heartbeat_ack" -> Unit
        }
    }

    @Volatile private var expiresAt = 0L

    private fun startHeartbeat(ws: WebSocket, interval: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(interval)
                ws.send("""{"op":"heartbeat"}""")
            }
        }
    }

    private fun fail(gen: Int, message: String) {
        if (gen != generation) return
        _step.value = Step.Failed(message)
        stop()
    }

    companion object {
        /** Discord's format: `id:discriminator:avatar_hash:username`. */
        fun parseUser(payload: String): ScannedUser {
            val parts = payload.split(':', limit = 4)
            return ScannedUser(
                id = parts.getOrNull(0)?.toLongOrNull() ?: 0L,
                discriminator = parts.getOrNull(1).orEmpty(),
                avatar = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "0" },
                username = parts.getOrNull(3).orEmpty(),
            )
        }

        /** SHA-256 of the SPKI public key, base64url without padding. */
        fun fingerprint(pair: KeyPair): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(pair.public.encoded))

        fun decrypt(pair: KeyPair, base64: String): ByteArray {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, pair.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            return cipher.doFinal(Base64.getDecoder().decode(base64))
        }
    }
}
