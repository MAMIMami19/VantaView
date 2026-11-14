// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.backup

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Serves one backup file on the home network so a phone can download it by scanning a QR code.
 * Only answers the exact secret address, and stops after 10 minutes or when closed.
 */
class BackupShareServer(private val fileName: String, private val content: ByteArray) {
    private var server: ServerSocket? = null
    private val token = ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /** The address to put in the QR code, or null if there's no Wi-Fi or wired network address. */
    var url: String? = null
        private set

    fun start(): String? {
        val ip = localAddress() ?: return null
        val s = ServerSocket(0)
        server = s
        url = "http://$ip:${s.localPort}/$token/$fileName"
        thread(isDaemon = true, name = "backup-share") {
            val until = System.currentTimeMillis() + 10 * 60_000L
            s.soTimeout = 1_000
            while (!s.isClosed && System.currentTimeMillis() < until) {
                val c = try { s.accept() } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break }
                thread(isDaemon = true) { answer(c) }
            }
            runCatching { s.close() }
        }
        return url
    }

    fun stop() { runCatching { server?.close() } }

    private fun answer(c: Socket) = c.use { sock ->
        sock.soTimeout = 10_000
        val line = BufferedReader(InputStreamReader(sock.getInputStream())).readLine().orEmpty()
        val path = line.split(" ").getOrNull(1).orEmpty()
        val out = sock.getOutputStream()
        if (line.startsWith("GET ") && path == "/$token/$fileName") {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${content.size}\r\n" +
                    "Content-Disposition: attachment; filename=\"$fileName\"\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(content)
        } else {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        out.flush()
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
