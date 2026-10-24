package com.seriecast.app.download

import android.content.Context
import android.webkit.WebSettings

/**
 * The User-Agent of the capture WebView, which is also the one the downloader has to send.
 *
 * Some stream CDNs answer 404 to anything that is not a mobile browser. Measured against
 * `edge1-moscow-sprintcdn.owphbf24.com`: the same signed playlist returned 200 for an Android
 * User-Agent and 404 for a desktop one. yt-dlp sends its own desktop User-Agent when nobody sets
 * the header, so a URL that played fine inside the app was reported as "404 Not Found" by
 * `Probar URL`.
 *
 * The fix keeps one source of truth: whatever the WebView presents is what the engine presents.
 */
object WebUserAgent {

    /** Used when the platform cannot report the WebView User-Agent. */
    const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"

    /** The WebView User-Agent without the marker that announces it is an embedded browser. */
    fun of(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.let(::normalize)
            ?.takeIf { it.isNotEmpty() }
            ?: FALLBACK

    fun normalize(raw: String): String = raw.replace("; wv", "").trim()
}
