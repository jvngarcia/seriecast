package com.seriecast.app.dlna

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal SSDP endpoint for a UPnP `MediaServer:1` device.
 *
 * It answers `M-SEARCH` datagrams and multicasts `ssdp:alive` / `ssdp:byebye` notifications. This is
 * the whole discovery story an old television needs: without a correct SSDP answer the TV never
 * requests `description.xml` and the device is invisible no matter how good the HTTP server is.
 */
internal class SsdpResponder(
    private val binding: LanBinding,
    private val httpPort: Int,
    private val uuid: String,
    private val serverString: String,
) {
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var receiveThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null

    /** True when the multicast socket bound and joined the SSDP group; surfaced in diagnostics. */
    @Volatile
    var isBound: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val targets: List<String>
        get() = listOf(
            "upnp:rootdevice",
            DEVICE_TYPE,
            CONTENT_DIRECTORY_SERVICE,
            CONNECTION_MANAGER_SERVICE,
            "uuid:$uuid",
        )

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val s = MulticastSocket(null as SocketAddress?)
            s.reuseAddress = true
            s.bind(InetSocketAddress(SSDP_PORT))
            s.networkInterface = binding.networkInterface
            s.timeToLive = 2
            s.joinGroup(InetSocketAddress(group, SSDP_PORT), binding.networkInterface)
            socket = s
            running.set(true)
            isBound = true
            lastError = null

            receiveThread = Thread({ receiveLoop(s) }, "ssdp-rx").apply {
                isDaemon = true
                start()
            }
            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ssdp-tx").apply { isDaemon = true }
            }.also { exec ->
                // Delay the first announcement so the HTTP server is already accepting connections.
                exec.schedule({ safeAlive() }, 300, TimeUnit.MILLISECONDS)
                exec.scheduleWithFixedDelay({ safeAlive() }, ANNOUNCE_INTERVAL_SECONDS, ANNOUNCE_INTERVAL_SECONDS, TimeUnit.SECONDS)
            }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            isBound = false
            runCatching { socket?.close() }
            socket = null
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        safeByeBye()
        scheduler?.shutdownNow()
        scheduler = null
        runCatching { socket?.let { s ->
            runCatching { s.leaveGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT), binding.networkInterface) }
            s.close()
        } }
        socket = null
        isBound = false
        receiveThread?.join(1_000)
        receiveThread = null
    }

    private fun receiveLoop(s: MulticastSocket) {
        val buffer = ByteArray(2048)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
                val headers = parseHeaders(text)
                val man = headers["man"] ?: continue
                if (!man.contains("ssdp:discover", ignoreCase = true)) continue
                answerSearch(s, headers["st"]?.trim() ?: "ssdp:all", packet.address, packet.port)
            } catch (io: java.io.IOException) {
                if (!running.get()) return
                lastError = io.message
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    private fun answerSearch(s: MulticastSocket, searchTarget: String, address: InetAddress, port: Int) {
        val replies = if (searchTarget.equals("ssdp:all", ignoreCase = true)) {
            targets
        } else {
            targets.filter { it.equals(searchTarget, ignoreCase = true) }
        }
        for (target in replies) {
            val payload = searchResponse(target)
            runCatching {
                s.send(DatagramPacket(payload.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8).size, address, port))
            }
        }
    }

    private fun searchResponse(target: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n")
        append("DATE: ").append(httpDate()).append("\r\n")
        append("EXT:\r\n")
        append("LOCATION: ").append(location()).append("\r\n")
        append("SERVER: ").append(serverString).append("\r\n")
        append("ST: ").append(target).append("\r\n")
        append("USN: ").append(usn(target)).append("\r\n")
        append("BOOTID.UPNP.ORG: 1\r\n")
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
    }

    private fun safeAlive() {
        if (running.get()) runCatching { announce(alive = true) }
    }

    private fun safeByeBye() = runCatching { announce(alive = false) }

    private fun announce(alive: Boolean) {
        val s = socket ?: return
        val group = InetAddress.getByName(SSDP_ADDRESS)
        for (target in targets) {
            val payload = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: ").append(SSDP_ADDRESS).append(':').append(SSDP_PORT).append("\r\n")
                if (alive) {
                    append("CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n")
                    append("LOCATION: ").append(location()).append("\r\n")
                    append("SERVER: ").append(serverString).append("\r\n")
                }
                append("NT: ").append(target).append("\r\n")
                append("NTS: ").append(if (alive) "ssdp:alive" else "ssdp:byebye").append("\r\n")
                append("USN: ").append(usn(target)).append("\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
            // Two sends: UPnP requires NOTIFY to be repeated, and a single lost datagram at boot
            // is the most common "my TV does not see the phone" cause.
            repeat(2) {
                runCatching { s.send(DatagramPacket(payload, payload.size, group, SSDP_PORT)) }
            }
        }
    }

    private fun usn(target: String): String =
        if (target.startsWith("uuid:")) "uuid:$uuid" else "uuid:$uuid::$target"

    private fun location(): String = "http://${binding.hostAddress}:$httpPort/description.xml"

    private fun httpDate(): String = HTTP_DATE.get().format(Date())

    private fun parseHeaders(raw: String): Map<String, String> {
        val result = HashMap<String, String>()
        raw.split("\r\n", "\n").drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) result[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        return result
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaServer:1"
        const val CONTENT_DIRECTORY_SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val CONNECTION_MANAGER_SERVICE = "urn:schemas-upnp-org:service:ConnectionManager:1"

        private const val MAX_AGE_SECONDS = 1800
        private const val ANNOUNCE_INTERVAL_SECONDS = 600L
        val HTTP_DATE: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
        }
    }
}
