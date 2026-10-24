package com.seriecast.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale

/** What the capture screen refused to do. Both kinds are counted and shown to the user. */
enum class BlockedKind { POPUP, NAVIGATION }

/** One refused attempt, kept so the user can see what the page tried and paste it by hand. */
data class BlockedAttempt(
    val url: String,
    val host: String,
    val kind: BlockedKind,
    val atMillis: Long,
)

/**
 * Decides what the capture WebView is allowed to do with an aggregator page full of ads.
 *
 * Two rules, both measured on a real page before being written down:
 *
 *  - a popup never opens: the player chain lives in nested iframes, so no legitimate flow of this
 *    screen needs a second window, and an ad only needs one to leave the app;
 *  - a main-frame navigation is a redirect hijack unless it stays inside the player chain, which
 *    is the page the user typed plus the hosts that already served a stream.
 *
 * Subframe navigations are always allowed: players and their ad overlays navigate their own iframes
 * constantly, and blocking those breaks playback without stopping the ad.
 *
 * The escape hatch is the address bar of the capture screen: loading a URL from there marks it as
 * the trusted page and never passes through this guard.
 *
 * A registered search origin is a second, narrower escape: while the document on screen is the
 * search engine the bar resolved, a click on any result is the user's own choice and must go
 * through, otherwise searching inside the app would be decorative. The moment a result loads,
 * `onPageLoaded` re-anchors the trusted page to it and these rules apply again.
 */
class NavigationGuard {

    private val internal = MutableStateFlow<List<BlockedAttempt>>(emptyList())
    val blocked: StateFlow<List<BlockedAttempt>> = internal.asStateFlow()

    @Volatile
    private var navigationBlocks = 0

    @Volatile
    private var popupBlocks = 0

    /** Registrable domain of the page the user navigated to: the root of the player chain. */
    @Volatile
    private var trustedPageDomain: String? = null

    /** Domains the address bar treats as a search engine; their page allows any result. */
    private val searchOrigins = LinkedHashSet<String>()

    /** True when the document on screen is a registered search engine. */
    @Volatile
    private var onSearchPage = false

    /** Registrable domains of the hosts that served a stream; dropped when the capture is cleared. */
    private val trustedStreamDomains = LinkedHashSet<String>()

    /** Marks a search engine as a page whose results may always be opened. */
    fun registerSearchOrigin(url: String) {
        hostOf(url)?.let { searchOrigins += registrableDomain(it) }
    }

    /** Marks the page the user navigated to as the root of the player chain. */
    fun onPageLoaded(url: String) {
        val host = hostOf(url) ?: return
        val domain = registrableDomain(host)
        trustedPageDomain = domain
        onSearchPage = domain in searchOrigins
    }

    /** Marks a URL that was accepted as a media stream; only those hosts can be navigated to. */
    fun trustStream(url: String) {
        val host = hostOf(url) ?: return
        trustedStreamDomains += registrableDomain(host)
    }

    /** A popup is never allowed. The attempt is counted so the user sees the ad working. */
    fun shouldOpenPopup(targetUrl: String): Boolean {
        record(targetUrl, BlockedKind.POPUP)
        return false
    }

    fun shouldLoadNavigation(targetUrl: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return true
        val host = hostOf(targetUrl) ?: return true
        val scheme = schemeOf(targetUrl) ?: return true
        // Only a real web page can take the player away from the user.
        if (scheme != "http" && scheme != "https") return true
        // A search page is a browser: opening a result is the whole point of being there. It is not
        // counted, because the user asked for it and nothing is being hijacked.
        if (onSearchPage) return true
        val trustedPage = trustedPageDomain ?: return true
        val domain = registrableDomain(host)
        if (domain == trustedPage || domain in trustedStreamDomains) return true
        record(targetUrl, BlockedKind.NAVIGATION)
        return false
    }

    fun countOf(kind: BlockedKind): Int = when (kind) {
        BlockedKind.POPUP -> popupBlocks
        BlockedKind.NAVIGATION -> navigationBlocks
    }

    /**
     * Forgets the captured stream hosts and every blocked attempt. The loaded page stays trusted:
     * it is still the document on screen, so an ad must not become reachable after a clean-up.
     */
    fun clear() {
        trustedStreamDomains.clear()
        navigationBlocks = 0
        popupBlocks = 0
        internal.value = emptyList()
    }

    private fun record(targetUrl: String, kind: BlockedKind) {
        when (kind) {
            BlockedKind.POPUP -> popupBlocks++
            BlockedKind.NAVIGATION -> navigationBlocks++
        }
        val attempt = BlockedAttempt(
            url = targetUrl,
            host = hostOf(targetUrl).orEmpty(),
            kind = kind,
            atMillis = System.currentTimeMillis(),
        )
        internal.value = (listOf(attempt) + internal.value).take(MAX_BLOCKED)
    }

    private fun schemeOf(url: String): String? = runCatching { URI(url.trim()).scheme }
        .getOrNull()
        ?.lowercase(Locale.ROOT)

    private fun hostOf(url: String): String? = runCatching { URI(url.trim()).host }
        .getOrNull()
        ?.lowercase(Locale.ROOT)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * Best-effort registrable domain, good enough to recognise the player chain: the last two
     * labels, or the last three when the site lives under a two-label public suffix (`com.ar`).
     */
    private fun registrableDomain(host: String): String {
        if (IPV4.matches(host)) return host
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LABEL_PUBLIC_SUFFIXES) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    companion object {
        /** How many refused attempts stay visible; the counters keep counting past this. */
        const val MAX_BLOCKED = 8

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        private val TWO_LABEL_PUBLIC_SUFFIXES = setOf(
            "com.ar", "net.ar", "org.ar", "com.br", "com.mx", "com.co", "com.pe", "com.ve",
            "com.uy", "com.ec", "com.bo", "com.py", "co.uk", "org.uk", "co.jp", "co.in",
            "com.au", "com.es", "com.de",
        )
    }
}
