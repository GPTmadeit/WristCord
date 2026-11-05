package com.wristcord.wear.discord

import kotlinx.coroutines.CompletableDeferred
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Lets the phone hand the watch a photo, with no phone app: a one-shot web page on the local
 * network, reached by scanning a QR code. A watch has no camera and rarely any pictures of its
 * own, so this is how a real photo gets onto one.
 *
 * It exists only while its screen is open and stops after the first good image. Every path needs
 * the random token in the QR code, but it is plain HTTP, so anyone able to read the Wi-Fi traffic
 * could see the token and the picture. An upload only ever reaches the preview: nothing is sent
 * to Discord until the watch's owner presses Send.
 */
class PhoneDropServer(
    address: Inet4Address,
    private val workDir: File,
    private val token: String = newToken(),
    /** Turns the received bytes into the file to send, or null if they are not an image. */
    private val prepare: (File) -> File?,
) : Closeable {
    private val socket = ServerSocket(0, 8, address)
    private val tokenBytes = "/$token".toByteArray()
    private val claimed = AtomicBoolean(false)
    private val connections = AtomicInteger(0)
    private val result = CompletableDeferred<File>()

    val url: String = "http://${address.hostAddress}:${socket.localPort}/$token"

    init {
        thread(name = "PhoneDrop", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    // Browsers open spare connections that send nothing, so one must not block the next.
                    if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                        connections.decrementAndGet()
                        runCatching { client.close() }
                        continue
                    }
                    thread(name = "PhoneDrop-conn", isDaemon = true) {
                        try {
                            client.use { handle(it) }
                        } catch (_: Exception) {
                            // A browser that went away mid-request: nothing to tell anyone.
                        } finally {
                            connections.decrementAndGet()
                        }
                    }
                }
            } catch (_: SocketException) {
                // close() ended accept().
            }
        }
    }

    /** Suspends until a phone has delivered a good image. */
    suspend fun awaitImage(): File = result.await()

    override fun close() {
        runCatching { socket.close() }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 3) return respond(client, 400, "text/plain", "Bad request")
        val path = parts[1].substringBefore('?')
        if (!MessageDigest.isEqual(path.toByteArray(), tokenBytes)) return respond(client, 404, "text/plain", "Not found")
        when (parts[0]) {
            "GET" -> respond(client, 200, "text/html; charset=utf-8", PAGE)
            "POST" -> receive(client, input, lines)
            else -> respond(client, 405, "text/plain", "Method not allowed")
        }
    }

    private fun receive(client: Socket, input: InputStream, headers: List<String>) {
        val length = headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull()
        if (length == null || length <= 0) return respond(client, 411, "text/plain", "Length required")
        if (length > MAX_UPLOAD_BYTES) return respond(client, 413, "text/plain", "That image is too large")
        if (!claimed.compareAndSet(false, true)) return respond(client, 409, "text/plain", "Already receiving an image")
        var done = false
        val temp = File.createTempFile("drop", ".bin", workDir)
        try {
            val complete = temp.outputStream().use { copyExactly(input, it, length) }
            if (!complete) return respond(client, 400, "text/plain", "The upload was cut short")
            val prepared = prepare(temp)
                ?: return respond(client, 415, "text/plain", "The watch could not read that image")
            respond(client, 200, "text/plain", "ok")
            done = true
            close()
            result.complete(prepared)
        } finally {
            temp.delete()
            if (!done) claimed.set(false)
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b == HEAD_END[matched].toInt() -> matched + 1
                b == HEAD_END[0].toInt() -> 1
                else -> 0
            }
            if (matched == HEAD_END.size) return out.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, length: Long): Boolean {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) return false
            output.write(buffer, 0, n)
            left -= n
        }
        return true
    }

    private fun respond(client: Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src blob:\r\n")
            append("Connection: close\r\n\r\n")
        }
        BufferedOutputStream(client.getOutputStream()).apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 40L * 1024 * 1024
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_CONNECTIONS = 6
        private const val READ_TIMEOUT_MS = 30_000
        private val HEAD_END = "\r\n\r\n".toByteArray()
        private val REASONS = mapOf(
            200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict",
            411 to "Length Required", 413 to "Payload Too Large", 415 to "Unsupported Media Type",
        )

        fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        /** The page shrinks the photo in the phone's browser, so the watch's Wi-Fi carries less. */
        private val PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Send to WristCord</title>
            <style>
            :root { color-scheme: dark; }
            body { margin: 0; min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center;
                   gap: 16px; padding: 24px; box-sizing: border-box; background: #1e1f22; color: #f2f3f5;
                   font: 16px system-ui, sans-serif; text-align: center; }
            h1 { font-size: 22px; margin: 0; }
            label { display: block; padding: 14px 24px; border-radius: 10px; background: #5865f2; color: #fff; font-weight: 600; cursor: pointer; }
            input { position: absolute; opacity: 0; width: 1px; height: 1px; }
            input:focus-visible + label { outline: 2px solid #fff; outline-offset: 2px; }
            #s { min-height: 1.4em; color: #b5bac1; }
            </style></head><body>
            <h1>Send a photo to your watch</h1>
            <input id="f" type="file" accept="image/*"><label for="f">Choose a photo</label>
            <div id="s" role="status"></div>
            <script>
            const s = document.getElementById('s'), f = document.getElementById('f');
            const MAX = 1600;
            function shrink(file) {
              return new Promise(done => {
                const url = URL.createObjectURL(file), img = new Image();
                img.onload = () => {
                  const r = Math.min(1, MAX / Math.max(img.naturalWidth, img.naturalHeight));
                  const c = document.createElement('canvas');
                  c.width = Math.max(1, Math.round(img.naturalWidth * r));
                  c.height = Math.max(1, Math.round(img.naturalHeight * r));
                  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
                  URL.revokeObjectURL(url);
                  c.toBlob(b => done(b || file), file.type === 'image/png' ? 'image/png' : 'image/jpeg', 0.88);
                };
                img.onerror = () => { URL.revokeObjectURL(url); done(file); };
                img.src = url;
              });
            }
            f.addEventListener('change', async () => {
              if (!f.files.length) return;
              f.disabled = true;
              s.textContent = 'Sending to the watch…';
              try {
                const body = await shrink(f.files[0]);
                const res = await fetch(location.pathname, { method: 'POST', body });
                if (res.ok) { s.textContent = 'Done. Check your watch to send it.'; return; }
                s.textContent = await res.text();
              } catch (e) {
                s.textContent = 'Could not reach the watch. Is it still showing the code?';
              }
              f.disabled = false;
              f.value = '';
            });
            </script></body></html>
        """.trimIndent()
    }
}
