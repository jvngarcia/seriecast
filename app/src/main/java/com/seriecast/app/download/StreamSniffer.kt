package com.seriecast.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class StreamKind { HLS, DASH, PROGRESSIVE }

data class SniffedStream(
    val url: String,
    val kind: StreamKind,
    val pageUrl: String,
    val cookie: String?,
    val seenAtMillis: Long,
    /** The `Referer` the page sent with the media request, when the WebView exposes it. */
    val referer: String? = null,
) {
    val displayHost: String
        get() = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    val displayTail: String
        get() = url.substringAfterLast('/').take(64)

    /**
     * Referer to hand to the downloader: the embed page that actually requested the media is more
     * accurate than the aggregator page the user opened, but the latter is better than nothing.
     */
    val effectiveReferer: String?
        get() = referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: pageUrl.trim().takeIf { it.isNotEmpty() }
}

/**
 * Collects media URLs observed by a WebView.
 *
 * WebView does not expose a network log to the app, but `shouldInterceptRequest` and
 * `onLoadResource` do fire for every sub-resource, including the `hls.js` XHRs that request the
 * playlist and the segments. That is what makes stream capture possible without a proxy.
 */
class StreamSniffer {

    private val internal = MutableStateFlow<List<SniffedStream>>(emptyList())
    val candidates: StateFlow<List<SniffedStream>> = internal.asStateFlow()

    private val seen = CopyOnWriteArrayList<String>()

    @Volatile
    var currentPageUrl: String = ""
        private set

    fun onPageStarted(url: String) {
        currentPageUrl = url
    }

    /**
     * Feeds one observed URL; non-media URLs are ignored. Returns the accepted candidate so the
     * caller can trust exactly the hosts that served media, never the ad CDNs the page also loads.
     */
    fun offer(
        rawUrl: String,
        cookie: String?,
        referer: String? = null,
        pageUrl: String = currentPageUrl,
    ): SniffedStream? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null
        // blob:/data: cannot be handed to yt-dlp: they only exist inside this WebView instance.
        if (url.startsWith("blob:", true) || url.startsWith("data:", true)) return null
        val kind = classify(url) ?: return null
        if (seen.contains(url)) return null
        seen.add(url)
        val entry = SniffedStream(
            url = url,
            kind = kind,
            pageUrl = pageUrl,
            cookie = cookie,
            seenAtMillis = System.currentTimeMillis(),
            referer = referer?.trim()?.takeIf { it.isNotEmpty() },
        )
        internal.value = (listOf(entry) + internal.value).take(MAX_CANDIDATES)
        return entry
    }

    fun clear() {
        seen.clear()
        internal.value = emptyList()
    }

    private fun classify(url: String): StreamKind? {
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?').substringBefore('#')
        when {
            path.endsWith(".m3u8") || path.contains("manifest.m3u8") -> return StreamKind.HLS
            path.endsWith(".mpd") -> return StreamKind.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mkv") ||
                path.endsWith(".webm") || path.endsWith(".mov") -> return StreamKind.PROGRESSIVE
        }
        // An asset is never a stream, even when its name contains "hls" or "playlist".
        if (ASSET_SUFFIXES.any { path.endsWith(it) }) return null
        // Plenty of CDNs hide the extension behind a route or a signed query
        // (``/playlist?token=…``, ``/hls/<id>/720``). Fall back to the vocabulary of the URL.
        if (DASH_HINTS.any { lower.contains(it) }) return StreamKind.DASH
        if (HLS_HINTS.any { lower.contains(it) }) return StreamKind.HLS
        return null
    }

    private companion object {
        const val MAX_CANDIDATES = 12

        /** Request targets that can never be a video source; checked before the hint words. */
        val ASSET_SUFFIXES = listOf(
            ".js", ".mjs", ".css", ".map", ".json", ".html", ".htm", ".php", ".txt", ".xml",
            ".vtt", ".srt", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
            ".woff", ".woff2", ".ttf", ".otf",
            // Segments: only the playlist that lists them is downloadable by yt-dlp.
            ".ts", ".m4s",
        )

        val HLS_HINTS = listOf("m3u8", "/hls/", "/hls?", "hls_", "hls=", "manifest", "playlist")

        val DASH_HINTS = listOf(".mpd", "/mpd/", "/mpd?", "dash=")
    }
}
