package com.seriecast.app.dlna

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A UPnP `MediaServer:1` (DMS) exposing one flat list of videos.
 *
 * The root container (ObjectID 0) returns the videos directly instead of a sub-container: old
 * television firmwares are much more reliable with a one-level tree, and the user always wants the
 * whole library anyway.
 */
class DlnaServer(
    private val binding: LanBinding,
    val friendlyName: String,
    val uuid: String,
    private val library: () -> List<MediaEntry>,
    private val onLog: (String) -> Unit = {},
) {
    data class Info(val friendlyName: String, val host: String, val port: Int, val uuid: String, val videos: Int)

    data class SelfTest(
        val httpReachable: Boolean,
        val descriptionValid: Boolean,
        val ssdpBound: Boolean,
        val ssdpError: String?,
        val detail: String,
    ) {
        val discoverable: Boolean get() = httpReachable && descriptionValid && ssdpBound
    }

    private val running = AtomicBoolean(false)
    private val updateId = AtomicInteger(1)
    private var librarySignature: Int = 0

    @Volatile
    private var snapshot: List<MediaEntry> = emptyList()

    private val httpPort = AtomicInteger(0)
    private var httpServer: UpnpHttpServer? = null
    private var ssdp: SsdpResponder? = null

    val isRunning: Boolean get() = running.get()
    val port: Int get() = httpPort.get()
    val hostAddress: String get() = binding.hostAddress
    val videos: Int get() = snapshot.size

    private val router = object : UpnpHttpServer.Router {
        override fun description(): String = Descriptions.device(
            friendlyName = friendlyName,
            uuid = uuid,
            // URLBase is optional and some TVs ignore it; relative URLs in the description are
            // resolved against LOCATION, which is what we rely on.
            includeUrlBase = false,
            authority = authorityOrNull().orEmpty(),
        )

        override fun scpd(name: String): String? = Descriptions.scpd(name)

        override fun soap(service: String, action: String, body: String, authority: String): UpnpHttpServer.SoapBody =
            handleSoap(service, action, body, authority.ifBlank { authorityOrNull().orEmpty() })

        override fun media(relativePath: String): MediaEntry? =
            snapshot.firstOrNull { it.relativePath == relativePath }

        override fun indexPage(authority: String): String = Descriptions.indexPage(
            friendlyName = friendlyName,
            authority = authority.ifBlank { authorityOrNull().orEmpty() },
            port = httpPort.get(),
            videos = snapshot.size,
        )

        override fun onError(path: String, error: Throwable) {
            onLog("HTTP $path: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun authorityOrNull(): String? {
        val p = httpPort.get()
        return if (p > 0) "${binding.hostAddress}:$p" else null
    }

    /** Starts HTTP first (SSDP needs LOCATION) and then discovery. Returns the published address. */
    fun start(): Info {
        check(!running.get()) { "server already running" }
        refreshLibrary()
        val server = UpnpHttpServer(PREFERRED_PORT, router)
        val actualPort = server.start()
        httpServer = server
        httpPort.set(actualPort)

        val discovery = SsdpResponder(
            binding = binding,
            httpPort = actualPort,
            uuid = uuid,
            serverString = "Android UPnP/1.0 SerieCast/1.0",
        )
        ssdp = discovery
        val ssdpOk = discovery.start()
        if (!ssdpOk) onLog("SSDP failed to bind: ${discovery.lastError}")

        running.set(true)
        return Info(friendlyName, binding.hostAddress, actualPort, uuid, snapshot.size)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        ssdp?.stop()
        ssdp = null
        httpServer?.stop()
        httpServer = null
        httpPort.set(0)
    }

    /**
     * Re-reads the library snapshot. Cheap enough to call on a timer; only bumps the UPnP
     * SystemUpdateID when the content actually changed, so televisions do not re-browse constantly.
     */
    fun refreshLibrary() {
        val entries = library().sortedBy { it.file.name.lowercase() }
        val signature = entries.joinToString("|") { "${it.file.name}:${it.sizeBytes}:${it.durationMs}" }.hashCode()
        snapshot = entries
        if (signature != librarySignature) {
            librarySignature = signature
            updateId.incrementAndGet()
        }
    }

    /** Verifies from the phone itself that a TV would be able to discover and describe us. */
    fun selfTest(): SelfTest {
        val port = httpPort.get()
        if (!running.get() || port <= 0) {
            return SelfTest(false, false, false, "servidor detenido", "El servidor no está iniciado.")
        }
        var reachable = false
        var valid = false
        var detail = ""
        try {
            val url = URL("http://127.0.0.1:$port/description.xml")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
            }
            try {
                reachable = connection.responseCode == 200
                val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                valid = body.contains(SsdpResponder.DEVICE_TYPE) &&
                    body.contains("ContentDirectory") &&
                    body.contains("uuid:$uuid")
                detail = if (valid) {
                    "description.xml responde y declara MediaServer:1 con ContentDirectory."
                } else {
                    "description.xml respondió pero no declara el dispositivo esperado."
                }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            detail = "No se pudo leer description.xml en 127.0.0.1:$port: ${t.message}"
        }
        val discovery = ssdp
        return SelfTest(
            httpReachable = reachable,
            descriptionValid = valid,
            ssdpBound = discovery?.isBound == true,
            ssdpError = discovery?.lastError,
            detail = detail,
        )
    }

    // ---------------------------------------------------------------- SOAP

    private fun handleSoap(service: String, action: String, body: String, authority: String): UpnpHttpServer.SoapBody {
        val normalized = service.lowercase().trim('/')
        val isContentDirectory = normalized == "contentdirectory" ||
            normalized.endsWith(":contentdirectory")
        val isConnectionManager = normalized == "connectionmanager" ||
            normalized.endsWith(":connectionmanager")

        return when {
            isContentDirectory -> when (action) {
                "Browse" -> UpnpHttpServer.SoapBody(200, envelope(SsdpResponder.CONTENT_DIRECTORY_SERVICE, "Browse", browse(body, authority)))
                "GetSearchCapabilities" -> UpnpHttpServer.SoapBody(
                    200,
                    envelope(
                        SsdpResponder.CONTENT_DIRECTORY_SERVICE,
                        "GetSearchCapabilities",
                        "<SearchCaps></SearchCaps>",
                    ),
                )
                "GetSortCapabilities" -> UpnpHttpServer.SoapBody(
                    200,
                    envelope(
                        SsdpResponder.CONTENT_DIRECTORY_SERVICE,
                        "GetSortCapabilities",
                        "<SortCaps>dc:title</SortCaps>",
                    ),
                )
                "GetSystemUpdateID" -> UpnpHttpServer.SoapBody(
                    200,
                    envelope(
                        SsdpResponder.CONTENT_DIRECTORY_SERVICE,
                        "GetSystemUpdateID",
                        "<Id>${updateId.get()}</Id>",
                    ),
                )
                else -> fault(401, "Invalid Action")
            }

            isConnectionManager -> when (action) {
                "GetProtocolInfo" -> UpnpHttpServer.SoapBody(
                    200,
                    envelope(
                        SsdpResponder.CONNECTION_MANAGER_SERVICE,
                        "GetProtocolInfo",
                        "<Source>${Descriptions.escape(PROTOCOL_INFO_LIST)}</Source><Sink></Sink>",
                    ),
                )
                "GetCurrentConnectionIDs" -> UpnpHttpServer.SoapBody(
                    200,
                    envelope(
                        SsdpResponder.CONNECTION_MANAGER_SERVICE,
                        "GetCurrentConnectionIDs",
                        "<ConnectionIDs>0</ConnectionIDs>",
                    ),
                )
                "GetCurrentConnectionInfo" -> UpnpHttpServer.SoapBody(
                    200,
                    envelope(
                        SsdpResponder.CONNECTION_MANAGER_SERVICE,
                        "GetCurrentConnectionInfo",
                        "<RcsID>-1</RcsID><AVTransportID>-1</AVTransportID>" +
                            "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                            "<PeerConnectionID>-1</PeerConnectionID><Direction>Output</Direction>" +
                            "<Status>OK</Status>",
                    ),
                )
                else -> fault(401, "Invalid Action")
            }

            else -> fault(401, "Invalid Action")
        }
    }

    private fun browse(body: String, authority: String): String {
        val objectId = arg(body, "ObjectID")?.ifBlank { "0" } ?: "0"
        val flag = arg(body, "BrowseFlag")?.ifBlank { "BrowseDirectChildren" } ?: "BrowseDirectChildren"
        val startIndex = arg(body, "StartingIndex")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val requestedCount = arg(body, "RequestedCount")?.toIntOrNull() ?: 0

        val entries = snapshot
        val isRoot = objectId == "0" || objectId == "-1"

        val selected: List<MediaEntry>
        val total: Int
        if (flag.equals("BrowseMetadata", ignoreCase = true)) {
            selected = if (isRoot) emptyList() else entries.filter { it.objectId == objectId }
            total = selected.size
        } else {
            // Flat tree: only the root has children, every other ObjectID is a leaf item.
            val source = if (isRoot) entries else emptyList()
            total = source.size
            selected = if (requestedCount <= 0) {
                source.drop(startIndex)
            } else {
                source.drop(startIndex).take(requestedCount)
            }
        }

        val didl = buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
            append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
            append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
            append(" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
            selected.forEach { append(itemXml(it, authority)) }
            append("</DIDL-Lite>")
        }

        return buildString {
            append("<Result>").append(Descriptions.escape(didl)).append("</Result>")
            append("<NumberReturned>").append(selected.size).append("</NumberReturned>")
            append("<TotalMatches>").append(total).append("</TotalMatches>")
            append("<UpdateID>").append(updateId.get()).append("</UpdateID>")
        }
    }

    private fun itemXml(entry: MediaEntry, authority: String): String {
        val base = "http://" + authority.ifBlank { authorityOrNull().orEmpty() }
        val url = "$base/media/${entry.mediaPath}"
        return buildString {
            append("<item id=\"").append(Descriptions.escape(entry.objectId)).append('"')
            append(" parentID=\"").append(MediaEntry.PARENT_ID).append('"')
            append(" restricted=\"1\">")
            append("<dc:title>").append(Descriptions.escape(entry.title)).append("</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("<res protocolInfo=\"").append(Descriptions.escape(protocolInfo(entry.mimeType))).append('"')
            append(" size=\"").append(entry.sizeBytes).append('"')
            entry.durationAttribute?.let { append(" duration=\"").append(it).append('"') }
            append('>')
            append(Descriptions.escape(url))
            append("</res>")
            append("</item>")
        }
    }

    /**
     * DLNA.ORG_OP=01 advertises byte-range seek support, which is what the HTTP server implements.
     * No DLNA.ORG_PN profile is declared on purpose: a wrong profile name makes strict TVs refuse
     * the item, while an absent one lets them probe the stream.
     */
    private fun protocolInfo(mime: String): String =
        "http-get:*:$mime:$DLNA_FLAGS"

    private fun envelope(serviceType: String, action: String, inner: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
        append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
        append(inner)
        append("</u:").append(action).append("Response></s:Body></s:Envelope>")
    }

    private fun fault(code: Int, description: String): UpnpHttpServer.SoapBody = UpnpHttpServer.SoapBody(
        500,
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
            append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>")
            append("<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">")
            append("<errorCode>").append(code).append("</errorCode>")
            append("<errorDescription>").append(description).append("</errorDescription>")
            append("</UPnPError></detail></s:Fault></s:Body></s:Envelope>")
        },
    )

    companion object {
        const val PREFERRED_PORT = 8080
        private const val DLNA_FLAGS =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        private val PROTOCOL_INFO_LIST = listOf(
            "http-get:*:video/mp4:*",
            "http-get:*:video/x-matroska:*",
            "http-get:*:video/mp2t:*",
            "http-get:*:video/mpeg:*",
        ).joinToString(",")

        /**
         * Reads a SOAP argument whether the client prefixed it (`<u:ObjectID>`) or not, and treats
         * self-closing empty elements as blank.
         */
        fun arg(body: String, name: String): String? {
            val pattern = Regex(
                "<(?:[A-Za-z0-9_.-]+:)?" + Regex.escape(name) + "\\s*>(.*?)</(?:[A-Za-z0-9_.-]+:)?" +
                    Regex.escape(name) + "\\s*>",
                RegexOption.DOT_MATCHES_ALL,
            )
            pattern.find(body)?.let { return it.groupValues[1].trim() }
            if (Regex("<(?:[A-Za-z0-9_.-]+:)?" + Regex.escape(name) + "\\s*/>").containsMatchIn(body)) return ""
            return null
        }
    }
}
