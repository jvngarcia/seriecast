package com.seriecast.app.dlna

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small HTTP/1.1 server: serves the UPnP description, SCPDs, SOAP control endpoints and the media
 * files themselves.
 *
 * Deliberately hand-written instead of NanoHTTPD because byte-range support is the single feature
 * that decides whether an old television can pause and seek. Every response closes the connection
 * (`Connection: close`), which is legal for HTTP/1.1 and avoids chunked-encoding edge cases that
 * cheap TV firmwares get wrong.
 */
internal class UpnpHttpServer(
    private val preferredPort: Int,
    private val router: Router,
) {
    interface Router {
        fun description(): String
        fun scpd(name: String): String?
        fun soap(service: String, action: String, body: String, authority: String): SoapBody
        fun media(relativePath: String): MediaEntry?
        fun indexPage(authority: String): String
        fun onError(path: String, error: Throwable)
    }

    data class SoapBody(val status: Int, val body: String)

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workers = Executors.newCachedThreadPool { r -> Thread(r, "upnp-http").apply { isDaemon = true } }

    @Volatile
    var port: Int = 0
        private set

    /**
     * Binds the preferred port, falling back to an ephemeral one when it is already taken.
     * @throws IOException when no socket can be bound at all.
     */
    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(preferredPort))
        } catch (busy: IOException) {
            socket.bind(InetSocketAddress(0))
        }
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        Thread({ acceptLoop(socket) }, "upnp-http-accept").apply {
            isDaemon = true
            start()
        }
        return port
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
        workers = Executors.newCachedThreadPool { r -> Thread(r, "upnp-http").apply { isDaemon = true } }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                workers.execute { handleClient(client) }
            } catch (io: IOException) {
                if (!running.get()) return
            } catch (t: Throwable) {
                if (!running.get()) return
                router.onError("/", t)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val raw = readHead(input) ?: return
                val request = parse(raw, input) ?: return
                val output = BufferedOutputStream(socket.getOutputStream())
                dispatch(request, output)
                output.flush()
            } catch (io: IOException) {
                // Client hung up mid-transfer (very common when a TV seeks). Not an error.
            } catch (t: Throwable) {
                router.onError(safePath(client), t)
            }
        }
    }

    private fun safePath(client: Socket): String =
        runCatching { client.inetAddress?.hostAddress ?: "?" }.getOrDefault("?")

    private fun dispatch(request: Request, out: OutputStream) {
        val authority = request.headers["host"]?.takeIf { it.isNotBlank() }.orEmpty()
        when {
            request.method == "GET" && request.path == "/description.xml" ->
                xml(out, 200, router.description())

            request.method == "GET" && request.path.startsWith("/scpd/") -> {
                val name = request.path.removePrefix("/scpd/")
                val scpd = router.scpd(name)
                if (scpd == null) notFound(out) else xml(out, 200, scpd)
            }

            request.method == "POST" && request.path.startsWith("/control/") -> {
                val service = request.path.removePrefix("/control/")
                val action = (request.headers["soapaction"] ?: "").trim().trim('"').substringAfter('#')
                val result = router.soap(service, action, request.body.toString(Charsets.UTF_8), authority)
                xml(out, result.status, result.body)
            }

            request.method == "SUBSCRIBE" -> {
                writeHead(
                    out, 200, "OK", "text/xml; charset=\"utf-8\"",
                    listOf(
                        "SID" to "uuid:${request.headers["sid"] ?: java.util.UUID.randomUUID()}",
                        "TIMEOUT" to "Second-1800",
                        "Content-Length" to "0",
                    ),
                )
            }

            request.method == "UNSUBSCRIBE" -> writeHead(out, 200, "OK", "text/plain", listOf("Content-Length" to "0"))

            (request.method == "GET" || request.method == "HEAD") && request.path.startsWith("/media/") -> {
                val relative = MediaEntry.decodePath(request.path.removePrefix("/media/"))
                val entry = router.media(relative)
                if (entry == null) notFound(out) else serveMedia(out, request, entry)
            }

            request.method == "GET" && (request.path == "/" || request.path == "/index.html") ->
                html(out, router.indexPage(authority))

            else -> notFound(out)
        }
    }

    private fun html(out: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(
            out, 200, reason(200), "text/html; charset=utf-8",
            listOf("Content-Length" to bytes.size.toString()),
        )
        out.write(bytes)
    }

    private fun xml(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(
            out, status, reason(status), "text/xml; charset=\"utf-8\"",
            listOf("EXT" to "", "Content-Length" to bytes.size.toString()),
        )
        out.write(bytes)
    }

    private fun notFound(out: OutputStream) {
        val body = "Not found".toByteArray(Charsets.UTF_8)
        writeHead(
            out, 404, reason(404), "text/plain; charset=utf-8",
            listOf("Content-Length" to body.size.toString()),
        )
        out.write(body)
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, contentType: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Date: ").append(SsdpResponder.HTTP_DATE.get().format(java.util.Date())).append("\r\n")
        sb.append("Server: ").append(SERVER).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        for ((k, v) in headers) {
            if (v.isEmpty()) sb.append(k).append("\r\n") else sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun serveMedia(out: OutputStream, request: Request, entry: MediaEntry) {
        val file: File = entry.file
        val total = file.length()
        if (!file.isFile || total <= 0L) {
            notFound(out)
            return
        }
        val isHead = request.method.equals("HEAD", ignoreCase = true)
        when (val range = parseRange(request.headers["range"], total)) {
            is RangeSpec.Unsatisfiable -> writeHead(
                out, 416, reason(416), entry.mimeType,
                listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"),
            )

            is RangeSpec.Partial -> {
                val length = range.end - range.start + 1
                writeHead(
                    out, 206, reason(206), entry.mimeType,
                    listOf(
                        "Content-Range" to "bytes ${range.start}-${range.end}/$total",
                        "Content-Length" to length.toString(),
                        "Accept-Ranges" to "bytes",
                    ),
                )
                if (!isHead) copyRange(file, range.start, length, out)
            }

            RangeSpec.Full -> {
                writeHead(
                    out, 200, reason(200), entry.mimeType,
                    listOf("Content-Length" to total.toString(), "Accept-Ranges" to "bytes"),
                )
                if (!isHead) copyRange(file, 0, total, out)
            }
        }
    }

    private fun copyRange(file: File, start: Long, length: Long, out: OutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private sealed interface RangeSpec {
        data object Full : RangeSpec
        data class Partial(val start: Long, val end: Long) : RangeSpec
        data object Unsatisfiable : RangeSpec
    }

    private fun parseRange(header: String?, total: Long): RangeSpec {
        if (header.isNullOrBlank()) return RangeSpec.Full
        val spec = header.trim()
        if (!spec.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Full
        val first = spec.substringAfter('=').substringBefore(',').trim()
        if (first.isEmpty()) return RangeSpec.Full
        val dash = first.indexOf('-')
        if (dash < 0) return RangeSpec.Unsatisfiable
        val startRaw = first.substring(0, dash).trim()
        val endRaw = first.substring(dash + 1).trim()
        return if (startRaw.isEmpty()) {
            val suffix = endRaw.toLongOrNull() ?: return RangeSpec.Unsatisfiable
            if (suffix <= 0L) return RangeSpec.Unsatisfiable
            RangeSpec.Partial((total - suffix).coerceAtLeast(0L), total - 1)
        } else {
            val start = startRaw.toLongOrNull() ?: return RangeSpec.Unsatisfiable
            if (start >= total) return RangeSpec.Unsatisfiable
            val end = if (endRaw.isEmpty()) {
                total - 1
            } else {
                val requested = endRaw.toLongOrNull() ?: return RangeSpec.Unsatisfiable
                requested.coerceAtMost(total - 1)
            }
            if (end < start) RangeSpec.Unsatisfiable else RangeSpec.Partial(start, end)
        }
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(1024)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            buffer.write(b)
            if (buffer.size() > MAX_HEAD_BYTES) throw IOException("request head too large")
            if (buffer.size() >= 4) {
                val tail = buffer.toString("ISO-8859-1")
                if (tail.endsWith("\r\n\r\n") || tail.endsWith("\n\n")) return tail
            }
        }
    }

    private fun parse(head: String, input: InputStream): Request? {
        val lines = head.split("\r\n", "\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > MAX_BODY_BYTES) throw IOException("request body too large")
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return Request(
            method = method,
            path = target.substringBefore('?'),
            query = target.substringAfter('?', ""),
            headers = headers,
            body = body.copyOf(read),
        )
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    companion object {
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_HEAD_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val SERVER = "Android/10 UPnP/1.0 SerieCast/1.0"
    }
}
