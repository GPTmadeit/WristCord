package com.wristcord.discord

import com.wristcord.discord.gateway.Gateway
import com.wristcord.discord.gateway.GatewayState
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadySupplemental
import com.wristcord.discord.remoteauth.RemoteAuth
import com.wristcord.discord.rest.DiscordRest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Against Discord's real endpoints, needing no account. Run with
 * `./gradlew :discord:test -Dwristcord.live=true`.
 */
class LiveTest {

    private fun live() = assumeTrue("set -Dwristcord.live=true", System.getProperty("wristcord.live") == "true")

    /**
     * Proves the reverse-engineered QR handshake end to end: Discord only sends
     * `pending_remote_init` after accepting our public key AND our nonce proof, so reaching
     * ShowQr means init, OAEP decryption, the proof format and the fingerprint are all right.
     */
    @Test
    fun `remote auth handshake reaches a scannable QR`() = runBlocking {
        live()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = DiscordClient(ClientIdentity.forTests(), scope)
        val ra = RemoteAuth(client.http, DiscordRest(client.http, client.identity) { null }, scope)
        ra.start()
        val step = withTimeout(30_000) {
            ra.step.first { it is RemoteAuth.Step.ShowQr || it is RemoteAuth.Step.Failed }
        }
        println("remote auth -> $step")
        ra.stop()
        scope.cancel()
        assertTrue("expected a QR, got $step", step is RemoteAuth.Step.ShowQr)
        assertTrue((step as RemoteAuth.Step.ShowQr).qrUrl.startsWith("https://discord.com/ra/"))
    }

    /**
     * Proves the gateway path short of logging in: connect, inflate zlib-stream HELLO, start
     * heartbeating, send IDENTIFY - and get Discord's 4004 for a token that cannot be valid.
     */
    @Test
    fun `gateway identifies and reports a rejected token as auth failure`() = runBlocking {
        live()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rejected = CountDownLatch(1)
        val gw = Gateway(DiscordClient.defaultHttp(), ClientIdentity.forTests(), scope, object : Gateway.Listener {
            override fun onReady(ready: Ready) = Unit
            override fun onReadySupplemental(supplemental: ReadySupplemental) = Unit
            override fun onDispatch(type: String, data: JsonElement) = Unit
            override fun onAuthenticationFailed() = rejected.countDown()
        })
        gw.connect("definitely-not-a-token")
        val ok = rejected.await(30, TimeUnit.SECONDS)
        println("gateway state -> ${gw.state.value}")
        scope.cancel()
        assertTrue("no 4004 within 30s; state=${gw.state.value}", ok)
        assertTrue(gw.state.value is GatewayState.Fatal)
    }
}
