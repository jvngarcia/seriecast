package com.seriecast.app.ui

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.seriecast.app.download.NavigationGuard
import com.seriecast.app.download.StreamSniffer

/**
 * A real WebView used as a stream sniffer.
 *
 * It is not hidden: the user must press play for the page to request the playlist, and doing that
 * blind is impossible. While the page plays, every sub-resource request passes through
 * `shouldInterceptRequest`, which is the only hook Android gives us to observe them.
 *
 * Aggregator pages are full of ads, so the WebView is also fenced by [NavigationGuard]: popups are
 * refused explicitly and a main-frame navigation only proceeds inside the player chain. The page is
 * loaded from the capture screen's address bar, which never passes through the guard, so a host the
 * guard gets wrong is always recoverable by pasting its URL there.
 */
@Composable
fun SnifferWebView(
    sniffer: StreamSniffer,
    guard: NavigationGuard,
    loadUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lastLoaded = remember { arrayOfNulls<String>(1) }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                // We want to see every window request so it can be counted, not silently ignored.
                // The transport WebView is never provided, so `onCreateWindow` returning false is
                // the whole answer: no window is created and `window.open` yields null.
                setSupportMultipleWindows(true)
                // A plain desktop-ish UA gets the same page a browser gets; some sites serve
                // degraded markup or refuse playback to the default WebView signature.
                userAgentString = userAgentString.replace("; wv", "")
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?,
                ): Boolean = guard.shouldOpenPopup("")
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    url?.let {
                        sniffer.onPageStarted(it)
                        // The page the user reached is the root of the player chain.
                        guard.onPageLoaded(it)
                    }
                }

                /**
                 * Only the main frame is judged: players and their ad overlays navigate their own
                 * iframes all the time, and blocking those breaks playback without stopping the ad.
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    val allowed = guard.shouldLoadNavigation(
                        targetUrl = target,
                        isMainFrame = request.isForMainFrame,
                    )
                    return !allowed
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    request?.url?.toString()?.let { candidate ->
                        // Many CDNs reject the download without the Referer the player used; the
                        // WebView exposes it here before the request leaves the app.
                        val headers = request.requestHeaders.orEmpty()
                        val referer = headers.entries
                            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                            ?.value
                        sniffer.offer(
                            rawUrl = candidate,
                            cookie = CookieManager.getInstance().getCookie(candidate),
                            referer = referer,
                        )?.let { guard.trustStream(it.url) }
                    }
                    return null
                }

                @Deprecated("Superseded by the WebResourceRequest overload")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                    url?.let {
                        sniffer.offer(it, CookieManager.getInstance().getCookie(it))
                            ?.let { stream -> guard.trustStream(stream.url) }
                    }
                    return null
                }

                override fun onLoadResource(view: WebView?, url: String?) {
                    url?.let { sniffer.offer(it, cookie = null) }
                }
            }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            val target = loadUrl
            if (!target.isNullOrBlank() && lastLoaded[0] != target) {
                lastLoaded[0] = target
                view.loadUrl(target)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
}
