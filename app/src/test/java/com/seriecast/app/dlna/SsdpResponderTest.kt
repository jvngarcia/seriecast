package com.seriecast.app.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * SSDP message contract, without sockets.
 *
 * A television only learns that the server exists from these datagrams, so their exact shape is the
 * difference between "appears in the list" and "invisible device". Instantiated without `start()`,
 * so no port is bound and no multicast group is joined.
 */
class SsdpResponderTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun responder(): SsdpResponder {
        val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
        val nif = requireNotNull(NetworkInterface.getByInetAddress(loopback)) { "sin interfaz loopback" }
        return SsdpResponder(
            binding = LanBinding("lo", loopback, nif, "loopback"),
            httpPort = 8080,
            uuid = uuid,
            serverString = "Android UPnP/1.0 SerieCast/1.0",
        )
    }

    @Test
    fun `m-search for the device type yields exactly one reply`() {
        assertEquals(
            listOf("urn:schemas-upnp-org:device:MediaServer:1"),
            responder().replyTargets("urn:schemas-upnp-org:device:MediaServer:1"),
        )
    }

    @Test
    fun `m-search matching is case insensitive`() {
        assertEquals(1, responder().replyTargets("URN:SCHEMAS-UPNP-ORG:DEVICE:MEDIASERVER:1").size)
    }

    @Test
    fun `ssdp all yields every advertised target`() {
        val targets = responder().replyTargets("ssdp:all")
        assertEquals(5, targets.size)
        assertTrue(targets.contains("upnp:rootdevice"))
        assertTrue(targets.contains("urn:schemas-upnp-org:device:MediaServer:1"))
        assertTrue(targets.contains("urn:schemas-upnp-org:service:ContentDirectory:1"))
        assertTrue(targets.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertTrue(targets.contains("uuid:$uuid"))
    }

    @Test
    fun `a renderer search is not answered`() {
        assertTrue(responder().replyTargets("urn:schemas-upnp-org:device:MediaRenderer:1").isEmpty())
    }

    @Test
    fun `response carries every header a television needs`() {
        val text = responder().searchResponse("urn:schemas-upnp-org:device:MediaServer:1")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("CACHE-CONTROL: max-age=1800\r\n"))
        assertTrue(text.contains("DATE: "))
        assertTrue(text.contains("EXT:\r\n"))
        assertTrue(text.contains("LOCATION: http://127.0.0.1:8080/description.xml\r\n"))
        assertTrue(text.contains("SERVER: Android UPnP/1.0 SerieCast/1.0\r\n"))
        assertTrue(text.contains("ST: urn:schemas-upnp-org:device:MediaServer:1\r\n"))
        assertTrue(
            text.contains("USN: uuid:$uuid::urn:schemas-upnp-org:device:MediaServer:1\r\n"),
        )
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `usn for the bare uuid target is not doubled`() {
        val text = responder().searchResponse("uuid:$uuid")
        assertTrue(text.contains("USN: uuid:$uuid\r\n"))
        assertTrue(!text.contains("uuid:$uuid::"))
    }
}
