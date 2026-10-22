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
) {
    val displayHost: String
        get() = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    val displayTail: String
        get() = url.substringAfterLast('/').take(64)
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

    /** Feeds one observed URL; non-media URLs are ignored. */
    fun offer(rawUrl: String, cookie: String?) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return
        // blob:/data: cannot be handed to yt-dlp: they only exist inside this WebView instance.
        if (url.startsWith("blob:", true) || url.startsWith("data:", true)) return
        val kind = classify(url) ?: return
        if (seen.contains(url)) return
        seen.add(url)
        val entry = SniffedStream(url, kind, currentPageUrl, cookie, System.currentTimeMillis())
        internal.value = (listOf(entry) + internal.value).take(MAX_CANDIDATES)
    }

    fun clear() {
        seen.clear()
        internal.value = emptyList()
    }

    private fun classify(url: String): StreamKind? {
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") || path.contains("manifest.m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") -> StreamKind.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mkv") ||
                path.endsWith(".webm") || path.endsWith(".mov") -> StreamKind.PROGRESSIVE
            else -> null
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 12
    }
}
