package com.seriecast.app.download

import java.net.URLEncoder
import java.util.Locale

/** What the address bar decided to do with what the user typed. */
sealed interface OmniboxTarget {
    /** The URL the WebView must load, already complete (scheme included). */
    val url: String

    data class Page(override val url: String) : OmniboxTarget

    data class Search(override val url: String, val query: String) : OmniboxTarget
}

/**
 * Turns what the user typed in the address bar into something the WebView can load.
 *
 * A person who does not know the episode URL should not have to leave the app to find it, so the
 * bar behaves like a browser's: a page address is loaded, and anything else is searched.
 *
 * Only `http` and `https` are pages. `javascript:`, `file:`, `intent:` and friends are treated as
 * search text: handing them to [android.webkit.WebView.loadUrl] would execute code or read local
 * files, and nobody types those on purpose in this screen.
 */
object Omnibox {

    /** Google, chosen by the user, used when what was typed is not an address. */
    const val DEFAULT_SEARCH_URL = "https://www.google.com/search?q="

    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")

    private val IPV4_HOST =
        Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""")

    private val NAMED_HOST =
        Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(:\d+)?(/.*)?$""")

    private val LOCALHOST = Regex("""^localhost(:\d+)?(/.*)?$""")

    /**
     * Resolves [input] into a page or a search, or null when there is nothing to load.
     *
     * [searchBase] is the search URL without the query, so tests can pin the engine and a future
     * setting can change it in one place.
     */
    fun resolve(input: String, searchBase: String = DEFAULT_SEARCH_URL): OmniboxTarget? {
        val text = input.trim()
        if (text.isEmpty()) return null

        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (scheme != null) {
            return if (scheme == "http" || scheme == "https") {
                OmniboxTarget.Page(text)
            } else {
                search(text, searchBase)
            }
        }

        return if (looksLikeHost(text)) {
            OmniboxTarget.Page("https://$text")
        } else {
            search(text, searchBase)
        }
    }

    private fun search(query: String, searchBase: String) =
        OmniboxTarget.Search(searchBase + URLEncoder.encode(query, "UTF-8"), query)

    /**
     * A host needs no scheme only when it cannot be a sentence: no spaces, and a shape the DNS can
     * actually resolve. `temporada 3.5` has a dot but is a search, not a page.
     */
    private fun looksLikeHost(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        return IPV4_HOST.matches(text) || NAMED_HOST.matches(text) || LOCALHOST.matches(text)
    }
}
