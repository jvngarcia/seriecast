package com.seriecast.app.dlna

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.file.Files

/**
 * End-to-end test of the UPnP media server over loopback.
 *
 * The `dlna` package deliberately depends only on `java.net`/`java.io`, so the real server — the same
 * classes the APK ships — can be started on a plain JVM. SSDP discovery is the only part that needs a
 * multicast-capable interface; here `start()` is allowed to fail at that stage and the HTTP, SOAP and
 * byte-range contracts are still asserted.
 */
class DlnaServerTest {

    private lateinit var dir: File
    private lateinit var media: File
    private lateinit var server: DlnaServer
    private var port = 0

    private val uuid = "11111111-2222-3333-4444-555555555555"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("seriecast-dlna").toFile()
        media = File(dir, "Serie - S01E02 - Piloto.mp4")
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        // Real MP4 magic: 'ftyp' at offset 4.
        "ftyp".toByteArray().copyInto(bytes, 4)
        media.writeBytes(bytes)

        val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
        val nif = requireNotNull(NetworkInterface.getByInetAddress(loopback)) { "sin interfaz loopback" }
        val binding = LanBinding("lo", loopback, nif, "loopback")
        server = DlnaServer(
            binding = binding,
            friendlyName = "SerieCast (test)",
            uuid = uuid,
            library = { listOf(MediaEntry.fromFile(media, "Serie - S01E02 - Piloto", 2_520_000L)) },
        )
        port = server.start().port
    }

    @After
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    private data class Response(val code: Int, val body: String, val headers: Map<String, String?>)

    private val interestingHeaders = listOf("Content-Range", "Content-Length", "Accept-Ranges", "Content-Type")

    private fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val collected = interestingHeaders.associateWith { connection.getHeaderField(it) }
        connection.disconnect()
        return Response(code, text, collected)
    }

    private fun mediaPath(): String = "/media/" + MediaEntry.encodePath(media.name)

    /** Mirrors what a DLNA client does before parsing the DIDL. `&amp;` must stay last. */
    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun browse(objectId: String = "0", flag: String = "BrowseDirectChildren"): Response = request(
        method = "POST",
        path = "/control/contentdirectory",
        headers = mapOf("SOAPACTION" to "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""),
        body = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <ObjectID>$objectId</ObjectID>
                  <BrowseFlag>$flag</BrowseFlag>
                  <Filter>*</Filter>
                  <StartingIndex>0</StartingIndex>
                  <RequestedCount>0</RequestedCount>
                  <SortCriteria></SortCriteria>
                </u:Browse>
              </s:Body>
            </s:Envelope>
        """.trimIndent(),
    )

    // -------------------------------------------------------------------- tests

    @Test
    fun `description declares a browsable media server, not a renderer`() {
        val response = request("GET", "/description.xml")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("urn:schemas-upnp-org:device:MediaServer:1"))
        assertTrue(response.body.contains("urn:schemas-upnp-org:service:ContentDirectory:1"))
        assertTrue(response.body.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertTrue(response.body.contains("DMS-1.50"))
        assertTrue(response.body.contains("uuid:$uuid"))
        assertTrue(response.body.contains("SerieCast (test)"))
        assertTrue(
            "un renderer confundiria al televisor",
            !response.body.contains("MediaRenderer"),
        )
    }

    @Test
    fun `scpd is served for both services and 404 for anything else`() {
        val contentDirectory = request("GET", "/scpd/contentdirectory.xml")
        assertEquals(200, contentDirectory.code)
        assertTrue(contentDirectory.body.contains("<name>Browse</name>"))

        val connectionManager = request("GET", "/scpd/connectionmanager.xml")
        assertEquals(200, connectionManager.code)
        assertTrue(connectionManager.body.contains("<name>GetProtocolInfo</name>"))

        assertEquals(404, request("GET", "/scpd/does-not-exist.xml").code)
    }

    @Test
    fun `browse returns one video item with a playable res element`() {
        val response = browse()
        assertEquals(200, response.code)
        assertTrue(response.body.contains("<NumberReturned>1</NumberReturned>"))
        assertTrue(response.body.contains("<TotalMatches>1</TotalMatches>"))

        // The DIDL must be XML-escaped inside <Result>, or the SOAP envelope breaks.
        assertTrue(
            "el DIDL tiene que ir escapado dentro de <Result>",
            response.body.contains("&lt;DIDL-Lite"),
        )

        // What the television parses is the payload after unescaping: attributes must be intact.
        val didl = unescapeXml(response.body)
        assertTrue(didl.contains("<DIDL-Lite"))
        assertTrue(didl.contains("</DIDL-Lite>"))
        assertTrue(didl.contains("object.item.videoItem"))
        assertTrue(didl.contains("Serie - S01E02 - Piloto"))
        assertTrue("duration es un atributo del <res>", didl.contains("duration=\"00:42:00.000\""))
        assertTrue("size es un atributo del <res>", didl.contains("size=\"1024\""))
        assertTrue(didl.contains("DLNA.ORG_OP=01"))
    }

    @Test
    fun `res url advertised to the television actually serves the file`() {
        val didl = browse().body
        val url = requireNotNull(Regex("http://[^<&]*?/media/[^<&]*").find(didl)) { "sin <res> con URL" }.value
        assertTrue("la URL debe apuntar a nuestro puerto, vino: $url", url.contains("127.0.0.1:$port/media/"))

        val full = request("GET", url.substringAfter("127.0.0.1:$port"))
        assertEquals(200, full.code)
        assertEquals("1024", full.headers["Content-Length"])
        assertEquals("bytes", full.headers["Accept-Ranges"])
    }

    @Test
    fun `range request returns 206 with the exact byte window`() {
        val partial = request("GET", mediaPath(), mapOf("Range" to "bytes=0-99"))
        assertEquals(206, partial.code)
        assertEquals("bytes 0-99/1024", partial.headers["Content-Range"])
        assertEquals("100", partial.headers["Content-Length"])
        assertEquals(100, partial.body.length)
    }

    @Test
    fun `open ended and suffix ranges follow rfc 7233`() {
        val openEnded = request("GET", mediaPath(), mapOf("Range" to "bytes=1000-"))
        assertEquals(206, openEnded.code)
        assertEquals("bytes 1000-1023/1024", openEnded.headers["Content-Range"])

        val suffix = request("GET", mediaPath(), mapOf("Range" to "bytes=-100"))
        assertEquals(206, suffix.code)
        assertEquals("bytes 924-1023/1024", suffix.headers["Content-Range"])
    }

    @Test
    fun `range beyond the end returns 416 with the total size`() {
        val beyond = request("GET", mediaPath(), mapOf("Range" to "bytes=5000-6000"))
        assertEquals(416, beyond.code)
        assertEquals("bytes */1024", beyond.headers["Content-Range"])
    }

    @Test
    fun `head returns headers without a body`() {
        val response = request("HEAD", mediaPath())
        assertEquals(200, response.code)
        assertEquals("1024", response.headers["Content-Length"])
        assertEquals("", response.body)
    }

    @Test
    fun `missing media and unknown paths return 404`() {
        assertEquals(404, request("GET", "/media/does-not-exist.mp4").code)
        assertEquals(404, request("GET", "/nope").code)
    }

    @Test
    fun `connection manager advertises the mp4 source protocol`() {
        val response = request(
            method = "POST",
            path = "/control/connectionmanager",
            headers = mapOf("SOAPACTION" to "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\""),
            body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body><u:GetProtocolInfo xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\"/></s:Body></s:Envelope>",
        )
        assertEquals(200, response.code)
        assertTrue(response.body.contains("http-get:*:video/mp4:*"))
    }

    @Test
    fun `browse metadata on an item returns only that item`() {
        val objectId = MediaEntry.stableId(media)
        val response = browse(objectId = objectId, flag = "BrowseMetadata")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("<NumberReturned>1</NumberReturned>"))
        assertTrue(response.body.contains("<TotalMatches>1</TotalMatches>"))
    }

    @Test
    fun `every response header line has a colon`() {
        // curl aborts a whole response when a header lacks its colon; that is exactly how the bug
        // in "EXT" was found on a device. HttpURLConnection is lenient, so read the raw bytes.
        val raw = rawRequest("/description.xml")
        assertTrue("la respuesta debe ser 200", raw.startsWith("HTTP/1.1 200 OK"))
        val headerBlock = raw.substringAfter("\r\n").substringBefore("\r\n\r\n")
        val offending = headerBlock.split("\r\n").filter { it.isNotBlank() && !it.contains(':') }
        assertTrue(
            "headers sin dos puntos: $offending — bloque crudo: ${headerBlock.take(300)}",
            offending.isEmpty(),
        )
        assertTrue("UPnP exige el header EXT:", headerBlock.contains("EXT:"))
    }

    @Test
    fun `raw get returns the body after the headers`() {
        val raw = rawRequest("/description.xml")
        val body = raw.substringAfter("\r\n\r\n")
        assertTrue(body.contains("urn:schemas-upnp-org:device:MediaServer:1"))
    }

    private fun rawRequest(path: String): String {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write("GET $path HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            out.flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `unknown soap action returns upnp error 401`() {
        val response = request(
            method = "POST",
            path = "/control/contentdirectory",
            headers = mapOf("SOAPACTION" to "\"urn:schemas-upnp-org:service:ContentDirectory:1#Nope\""),
            body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>",
        )
        assertEquals(500, response.code)
        assertTrue(response.body.contains("<errorCode>401</errorCode>"))
    }
}
