package com.seriecast.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture address bar has to behave like a browser's: a person who does not know the episode
 * URL types what they want and gets a search, inside the app, instead of leaving to copy a link.
 *
 * Only http and https are pages. Any other scheme is text: `javascript:` and `file:` must never be
 * handed to the WebView, where loading them would run code or read local files.
 */
class OmniboxTest {

    @Test
    fun `an http url is loaded as written`() {
        val resolved = Omnibox.resolve("http://filemon.tv/serie/1")

        assertEquals(OmniboxTarget.Page("http://filemon.tv/serie/1"), resolved)
    }

    @Test
    fun `an https url is loaded as written`() {
        val resolved = Omnibox.resolve("https://filemon.tv/serie/1?t=abc")

        assertEquals(OmniboxTarget.Page("https://filemon.tv/serie/1?t=abc"), resolved)
    }

    @Test
    fun `a bare host gets https and is loaded`() {
        val resolved = Omnibox.resolve("filemon.tv/serie/1")

        assertEquals(OmniboxTarget.Page("https://filemon.tv/serie/1"), resolved)
    }

    @Test
    fun `a bare host with a port is loaded`() {
        val resolved = Omnibox.resolve("127.0.0.1:8099/index.html")

        assertEquals(OmniboxTarget.Page("https://127.0.0.1:8099/index.html"), resolved)
    }

    @Test
    fun `words with spaces become a google search`() {
        val resolved = Omnibox.resolve("el flash temporada 3")

        assertEquals(
            OmniboxTarget.Search(
                url = "https://www.google.com/search?q=el+flash+temporada+3",
                query = "el flash temporada 3",
            ),
            resolved,
        )
    }

    @Test
    fun `a single word without a dot becomes a search`() {
        val resolved = Omnibox.resolve("filemon")

        assertEquals(
            OmniboxTarget.Search("https://www.google.com/search?q=filemon", "filemon"),
            resolved,
        )
    }

    @Test
    fun `a dot without an alphabetic tld is a search, not a host`() {
        val resolved = Omnibox.resolve("temporada 3.5")

        assertTrue(resolved is OmniboxTarget.Search)
    }

    @Test
    fun `a javascript scheme is text, never a page`() {
        val resolved = Omnibox.resolve("javascript:alert(1)")

        assertTrue("un esquema ejecutable no puede ser pagina", resolved is OmniboxTarget.Search)
    }

    @Test
    fun `a file scheme is text, never a page`() {
        val resolved = Omnibox.resolve("file:///etc/hosts")

        assertTrue(resolved is OmniboxTarget.Search)
    }

    @Test
    fun `an empty input resolves to nothing`() {
        assertNull(Omnibox.resolve("   "))
    }

    @Test
    fun `surrounding spaces are trimmed before deciding`() {
        val resolved = Omnibox.resolve("  filemon.tv/x  ")

        assertEquals(OmniboxTarget.Page("https://filemon.tv/x"), resolved)
    }

    @Test
    fun `an uppercase scheme is still a page`() {
        val resolved = Omnibox.resolve("HTTPS://filemon.tv/x")

        assertEquals(OmniboxTarget.Page("HTTPS://filemon.tv/x"), resolved)
    }

    @Test
    fun `a query with accents and ampersands is encoded`() {
        val resolved = Omnibox.resolve("quién & serie")

        assertEquals(
            OmniboxTarget.Search(
                url = "https://www.google.com/search?q=qui%C3%A9n+%26+serie",
                query = "quién & serie",
            ),
            resolved,
        )
    }
}
