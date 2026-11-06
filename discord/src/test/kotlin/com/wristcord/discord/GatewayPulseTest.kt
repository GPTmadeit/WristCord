package com.wristcord.discord

import com.wristcord.discord.gateway.Gateway
import com.wristcord.discord.gateway.GatewayState
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadySupplemental
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The watch sleeping through its heartbeats: a wake-up must notice a socket that has died. */
class GatewayPulseTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val connections = AtomicInteger()
    private val frames = LinkedBlockingQueue<String>()
    private lateinit var gateway: Gateway
    @Volatile private var ack = true

    private val hello = """{"op":10,"d":{"heartbeat_interval":3600000}}"""
    private val ready = """{"t":"READY","s":1,"op":0,"d":{"user":{"id":"10","username":"neo"},"session_id":"s","resume_gateway_url":"${"$"}URL"}}"""

    @Before
    fun setUp() {
        server.start()
        val url = server.url("/gw").toString().replace("http", "ws")
        repeat(4) {
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connections.incrementAndGet()
                    webSocket.send(hello)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(text)
                    when {
                        text.contains("\"op\":2") -> webSocket.send(ready.replace("\$URL", url))
                        text.contains("\"op\":1,") && ack -> webSocket.send("""{"op":11}""")
                    }
                }
            }).build())
        }
        gateway = Gateway(http, ClientIdentity.forTests(), scope, object : Gateway.Listener {
            override fun onReady(ready: Ready) = Unit
            override fun onReadySupplemental(supplemental: ReadySupplemental) = Unit
            override fun onDispatch(type: String, data: JsonElement) = Unit
            override fun onAuthenticationFailed() = Unit
        }, gatewayUrl = url, pulseAckMs = 300)
    }

    @After
    fun tearDown() {
        gateway.disconnect(resumable = false)
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        // Server-side sockets from MockWebServer cannot be cancelled, so close can give up on them.
        runCatching { server.close() }
    }

    private fun connected() = runBlocking {
        withTimeout(5_000) { gateway.state.first { it is GatewayState.Connected } }
    }

    private fun nextHeartbeat(): String? {
        while (true) {
            val frame = frames.poll(3, TimeUnit.SECONDS) ?: return null
            if (frame.contains("\"op\":1,")) return frame
        }
    }

    @Test
    fun `a healthy socket gets a heartbeat and stays up`() {
        gateway.connect("t")
        connected()
        gateway.pulse()
        assertTrue(nextHeartbeat() != null)
        Thread.sleep(700)
        assertEquals(1, connections.get())
        assertTrue(gateway.state.value is GatewayState.Connected)
    }

    @Test
    fun `a socket that stopped answering is replaced`() {
        gateway.connect("t")
        connected()
        ack = false
        gateway.pulse()
        assertTrue(nextHeartbeat() != null)
        val deadline = System.currentTimeMillis() + 5_000
        while (connections.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(2, connections.get())
    }

    @Test
    fun `a pulse does nothing once disconnected on purpose`() {
        gateway.pulse()
        Thread.sleep(200)
        assertEquals(0, connections.get())
    }
}
