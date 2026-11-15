// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.torrent

import java.io.BufferedOutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder

/**
 * A small web server on this device only (127.0.0.1) that hands a playing torrent's file to the player,
 * a piece at a time as it arrives. When the player jumps ahead, the pieces from there are asked for first.
 * Being an ordinary address, it also works with "Open in another player".
 */
internal class TorrentServer(private val engine: TorrentEngine) {
    private var socket: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        socket = s
        Thread({ accept(s) }, "torrent-server").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun address(key: String, name: String): String =
        "http://127.0.0.1:${port()}/bt/$key/" + URLEncoder.encode(name.substringAfterLast('/'), "UTF-8").replace("+", "%20")

    fun keyOf(address: String?): String? =
        address?.takeIf { TorrentEngine.isLocal(it) }?.substringAfter("/bt/")?.substringBefore('/')

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            Thread({ runCatching { serve(c) }; runCatching { c.close() } }, "torrent-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val parts = request.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val key = parts.getOrNull(1)?.substringAfter("/bt/", "")?.substringBefore('/').orEmpty()
        val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
        val p = engine.find(key)
        if (p == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }

        val total = p.size
        var start = 0L
        var end = total - 1
        range?.removePrefix("bytes=")?.split('-')?.let { r ->
            r.getOrNull(0)?.toLongOrNull()?.let { start = it }
            r.getOrNull(1)?.toLongOrNull()?.let { end = minOf(it, total - 1) }
        }
        if (start >= total) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }
        val length = end - start + 1
        val head = buildString {
            append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(mime(p.file.name)).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray()); out.flush()
        if (method == "HEAD") return

        engine.want(p, p.pieceAt(start))
        val buf = ByteArray(64 * 1024)
        var pos = start
        var file: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val piece = p.pieceAt(pos)
                // Wait for this piece, asking for it (and the next few) to come first.
                var waited = 0
                while (!p.handle.havePiece(piece)) {
                    if (!p.handle.isValid || engine.find(key) == null) return
                    if (waited % 20 == 0) engine.want(p, piece)
                    Thread.sleep(100); waited++
                }
                if (file == null) file = RandomAccessFile(p.file, "r")
                // Read up to the end of this piece.
                val pieceEnd = (piece + 1).toLong() * p.pieceLength - p.fileOffset - 1
                val n = minOf(buf.size.toLong(), end - pos + 1, pieceEnd - pos + 1).toInt()
                file.seek(pos)
                val got = file.read(buf, 0, n)
                if (got <= 0) { Thread.sleep(100); continue }
                out.write(buf, 0, got)
                pos += got
                if (pos % (4L * p.pieceLength) < got) engine.want(p, p.pieceAt(pos))
            }
            out.flush()
        } finally {
            runCatching { file?.close() }
        }
    }

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
}
