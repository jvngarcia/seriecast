package com.seriecast.app.ui

import android.graphics.Bitmap
import android.webkit.CookieManager
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
import com.seriecast.app.download.StreamSniffer

/**
 * A real WebView used as a stream sniffer.
 *
 * It is not hidden: the user must press play for the page to request the playlist, and doing that
 * blind is impossible. While the page plays, every sub-resource request passes through
 * `shouldInterceptRequest`, which is the only hook Android gives us to observe them.
 */
@Composable
fun SnifferWebView(
    sniffer: StreamSniffer,
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
                // A plain desktop-ish UA gets the same page a browser gets; some sites serve
                // degraded markup or refuse playback to the default WebView signature.
                userAgentString = userAgentString.replace("; wv", "")
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    url?.let { sniffer.onPageStarted(it) }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    request?.url?.toString()?.let { candidate ->
                        sniffer.offer(candidate, CookieManager.getInstance().getCookie(candidate))
                    }
                    return null
                }

                @Deprecated("Superseded by the WebResourceRequest overload")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                    url?.let { sniffer.offer(it, CookieManager.getInstance().getCookie(it)) }
                    return null
                }

                override fun onLoadResource(view: WebView?, url: String?) {
                    url?.let { sniffer.offer(it, null) }
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
