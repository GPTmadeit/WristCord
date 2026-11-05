package com.wristcord.wear.discord

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class PhoneDropServerTest {
    @get:Rule val temp = TemporaryFolder()

    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address

    private fun server(prepare: (File) -> File? = { f -> temp.newFile().also { f.copyTo(it, overwrite = true) } }) =
        PhoneDropServer(loopback, temp.newFolder(), "secret-token", prepare)

    private fun request(url: String, method: String = "GET", body: ByteArray? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to text
    }

    @Test
    fun `the page needs the token`() = server().use { s ->
        val (code, page) = request(s.url)
        assertEquals(200, code)
        assertTrue(page.contains("Send a photo to your watch"))
        val root = s.url.substringBeforeLast('/')
        assertEquals(404, request("$root/").first)
        assertEquals(404, request("$root/wrong-token").first)
        assertEquals(404, request("$root/wrong-token", "POST", byteArrayOf(1)).first)
    }

    @Test
    fun `a good upload arrives byte for byte and stops the server`() = server().use { s ->
        val bytes = ByteArray(300_000) { it.toByte() }
        assertEquals(200, request(s.url, "POST", bytes).first)
        val file = runBlocking { withTimeout(5_000) { s.awaitImage() } }
        assertArrayEquals(bytes, file.readBytes())
        val refused = runCatching { request(s.url) }.exceptionOrNull()
        assertTrue("expected the port closed, got $refused", refused is ConnectException)
    }

    @Test
    fun `an unreadable upload can be followed by a good one`() {
        var calls = 0
        server { f -> if (calls++ == 0) null else temp.newFile().also { f.copyTo(it, overwrite = true) } }.use { s ->
            assertEquals(415, request(s.url, "POST", byteArrayOf(1, 2, 3)).first)
            assertEquals(200, request(s.url, "POST", byteArrayOf(4, 5, 6)).first)
            val file = runBlocking { withTimeout(5_000) { s.awaitImage() } }
            assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
        }
    }

    @Test
    fun `an idle connection does not block the page`() = server().use { s ->
        val port = URL(s.url).port
        Socket(loopback, port).use {
            assertEquals(200, request(s.url).first)
        }
    }

    @Test
    fun `a body that is cut short never reaches prepare`() {
        var prepared = false
        server { prepared = true; it }.use { s ->
            val u = URL(s.url)
            Socket(loopback, u.port).use { sock ->
                sock.getOutputStream().write("POST ${u.path} HTTP/1.1\r\nContent-Length: 1000\r\n\r\nshort".toByteArray())
                sock.getOutputStream().flush()
                sock.shutdownOutput()
                sock.getInputStream().readBytes()
            }
            assertFalse(prepared)
            // And the slot was released: the next upload is still welcome.
            assertEquals(200, request(s.url).first)
        }
    }

    @Test
    fun `oversized uploads are refused from the header alone`() = server().use { s ->
        val u = URL(s.url)
        Socket(loopback, u.port).use { sock ->
            sock.getOutputStream().write("POST ${u.path} HTTP/1.1\r\nContent-Length: 99999999999\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
            val head = sock.getInputStream().readBytes().decodeToString()
            assertTrue(head, head.startsWith("HTTP/1.1 413"))
        }
    }
}
