package com.paw.agent.chatgpt

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Small real loopback HTTP fixture; also works on the Android unit-test compiler bootclasspath. */
internal class LocalHttpServer {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val address get() = listener.localSocketAddress as java.net.InetSocketAddress
    lateinit var executor: Executor
    private lateinit var handler: (HttpExchange) -> Unit
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    fun createContext(@Suppress("UNUSED_PARAMETER") path: String, handler: (HttpExchange) -> Unit) {
        this.handler = handler
    }

    fun start() {
        Thread({
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                executor.execute {
                    try { socket.use { handler(HttpExchange(it)) } }
                    finally { sockets.remove(socket) }
                }
            }
        }, "chatgpt-test-http").apply { isDaemon = true; start() }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
    }
}

internal class HttpHeaders {
    private val values = mutableMapOf<String, String>()
    fun add(name: String, value: String) { values[name.lowercase()] = value }
    fun getFirst(name: String): String? = values[name.lowercase()]
    fun entries() = values.entries
}

internal class HttpExchange(private val socket: Socket) {
    val requestHeaders = HttpHeaders()
    val responseHeaders = HttpHeaders()
    val requestURI: URI
    val requestBody: InputStream
    val responseBody get() = socket.getOutputStream()

    init {
        socket.soTimeout = 8_000
        val input = socket.getInputStream()
        requestURI = URI(line(input).split(" ")[1])
        while (true) {
            val header = line(input)
            if (header.isEmpty()) break
            requestHeaders.add(header.substringBefore(":"), header.substringAfter(":").trim())
        }
        val length = requestHeaders.getFirst("Content-Length")?.toInt() ?: 0
        check(length in 0..1_048_576)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            check(count > 0)
            offset += count
        }
        requestBody = ByteArrayInputStream(bytes)
    }

    fun sendResponseHeaders(code: Int, length: Long) {
        val headers = buildString {
            append("HTTP/1.1 $code Test\r\nConnection: close\r\n")
            // Zero denotes a deliberately unfinished body in cancellation tests.
            append("Content-Length: ${if (length == 0L) 1_048_576 else length.coerceAtLeast(0)}\r\n")
            responseHeaders.entries().forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        responseBody.write(headers.toByteArray(Charsets.US_ASCII))
        responseBody.flush()
    }

    fun close() = socket.close()

    private fun line(input: InputStream): String {
        val result = StringBuilder()
        while (true) {
            val next = input.read()
            check(next >= 0) { "Unexpected end of HTTP headers" }
            if (next == 10) return result.toString().removeSuffix("\r")
            result.append(next.toChar())
            check(result.length < 65_536)
        }
    }
}
