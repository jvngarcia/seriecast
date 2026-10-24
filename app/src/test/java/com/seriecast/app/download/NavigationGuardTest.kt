package com.seriecast.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture screen loads aggregators full of ads. Two things must never happen there: a popup
 * escaping to another window, and the player being replaced by an ad page that navigates the main
 * frame. The legitimate player chain, however, has to keep working.
 */
class NavigationGuardTest {

    private val guard = NavigationGuard()

    @Test
    fun `a popup is blocked even from the loaded page itself`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertFalse(guard.shouldOpenPopup("https://agregador.com/promo"))
        assertEquals(1, guard.blocked.value.size)
        val attempt = guard.blocked.value.single()
        assertEquals(BlockedKind.POPUP, attempt.kind)
        assertEquals("agregador.com", attempt.host)
        assertEquals("https://agregador.com/promo", attempt.url)
    }

    @Test
    fun `a popup request without a url is still blocked and counted`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertFalse(guard.shouldOpenPopup(""))

        val attempt = guard.blocked.value.single()
        assertEquals(BlockedKind.POPUP, attempt.kind)
        assertEquals("", attempt.host)
    }

    @Test
    fun `a top frame navigation to the same host is allowed`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertTrue(guard.shouldLoadNavigation("https://agregador.com/serie/1/capitulo", isMainFrame = true))
        assertTrue(guard.blocked.value.isEmpty())
    }

    @Test
    fun `a top frame navigation to a sibling subdomain of the loaded page is allowed`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertTrue(guard.shouldLoadNavigation("https://cdn.agregador.com/reproductor", isMainFrame = true))
        assertTrue(guard.blocked.value.isEmpty())
    }

    @Test
    fun `a top frame navigation to a host that served a stream is allowed`() {
        guard.onPageLoaded("https://agregador.com/serie/1")
        guard.trustStream("https://media.callistanise.com/stream/v7pml7nsxiap/master.m3u8")

        assertTrue(guard.shouldLoadNavigation("https://media.callistanise.com/embed/v7pml7nsxiap", isMainFrame = true))
        assertTrue(guard.shouldLoadNavigation("https://cdn2.callistanise.com/player", isMainFrame = true))
        assertTrue(guard.blocked.value.isEmpty())
    }

    @Test
    fun `a top frame navigation to an unrelated host is blocked and counted`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertFalse(guard.shouldLoadNavigation("https://ads.example.net/virus.apk", isMainFrame = true))
        val attempt = guard.blocked.value.single()
        assertEquals(BlockedKind.NAVIGATION, attempt.kind)
        assertEquals("ads.example.net", attempt.host)
    }

    @Test
    fun `a subframe navigation is allowed and never counted`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertTrue(guard.shouldLoadNavigation("https://ads.example.net/frame", isMainFrame = false))
        assertTrue(guard.blocked.value.isEmpty())
    }

    @Test
    fun `schemes that cannot reach another host are allowed and not counted`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        listOf("about:blank", "javascript:void(0)", "blob:https://agregador.com/1234", "intent://x#Intent;end")
            .forEach { target ->
                assertTrue(target, guard.shouldLoadNavigation(target, isMainFrame = true))
            }
        assertTrue(guard.blocked.value.isEmpty())
    }

    @Test
    fun `a two part public suffix does not make unrelated sites siblings`() {
        guard.onPageLoaded("https://agregador.com.ar/serie/1")

        assertTrue(guard.shouldLoadNavigation("https://cdn.agregador.com.ar/player", isMainFrame = true))
        assertFalse(guard.shouldLoadNavigation("https://otro.com.ar/anuncio", isMainFrame = true))
    }

    @Test
    fun `an unparsable target is allowed instead of breaking the page`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        assertTrue(guard.shouldLoadNavigation("no-es-una-url", isMainFrame = true))
        assertTrue(guard.blocked.value.isEmpty())
    }

    @Test
    fun `the blocked history keeps the most recent attempts only`() {
        guard.onPageLoaded("https://agregador.com/serie/1")

        repeat(10) { index ->
            guard.shouldLoadNavigation("https://ads$index.example.net/x$index", isMainFrame = true)
        }

        assertEquals(NavigationGuard.MAX_BLOCKED, guard.blocked.value.size)
        assertEquals("https://ads9.example.net/x9", guard.blocked.value.first().url)
        assertEquals(10, guard.countOf(BlockedKind.NAVIGATION))
    }

    @Test
    fun `clearing forgets the stream hosts and the blocked attempts but keeps the loaded page`() {
        guard.onPageLoaded("https://agregador.com/serie/1")
        guard.trustStream("https://media.callistanise.com/master.m3u8")
        guard.shouldOpenPopup("https://ads.example.net/x")

        guard.clear()

        assertTrue(guard.blocked.value.isEmpty())
        assertEquals(0, guard.countOf(BlockedKind.POPUP))
        // The page is still the document on screen, so its own chain keeps working...
        assertTrue(guard.shouldLoadNavigation("https://agregador.com/serie/2", isMainFrame = true))
        // ...but the hosts captured before the clean-up are no longer trusted.
        assertFalse(guard.shouldLoadNavigation("https://media.callistanise.com/embed/1", isMainFrame = true))
        val attempt = guard.blocked.value.singleOrNull()
        assertNotNull(attempt)
        assertEquals(BlockedKind.NAVIGATION, attempt!!.kind)
    }
}
