package com.seriecast.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Some stream CDNs answer 404 to any User-Agent that is not a mobile browser. Measured against
 * `edge1-moscow-sprintcdn.owphbf24.com`: the media playlist returns 200 for an Android UA and 404
 * for a desktop one, which is exactly what yt-dlp sends when nobody sets this header.
 *
 * The downloader must therefore present the same client that played the page: the WebView's own
 * User-Agent.
 */
class WebUserAgentTest {

    @Test
    fun `the webview marker is stripped so the cdn sees a plain browser`() {
        val raw = "Mozilla/5.0 (Linux; Android 10; Infinix X692; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"

        val normalized = WebUserAgent.normalize(raw)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; Infinix X692) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36",
            normalized,
        )
    }

    @Test
    fun `a user agent without the marker is left as it is`() {
        val raw = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Mobile Safari/537.36"

        assertEquals(raw, WebUserAgent.normalize(raw))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        assertEquals("Mozilla/5.0", WebUserAgent.normalize("  Mozilla/5.0  "))
    }

    @Test
    fun `the fallback is a mobile browser and not a webview`() {
        assertTrue("tiene que declararse Android", WebUserAgent.FALLBACK.contains("Android"))
        assertTrue("tiene que declararse movil", WebUserAgent.FALLBACK.contains("Mobile"))
        assertFalse("no puede llevar el marcador de WebView", WebUserAgent.FALLBACK.contains("; wv"))
    }
}
