package com.seriecast.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seriecast.app.download.BlockedKind
import com.seriecast.app.download.NavigationGuard
import com.seriecast.app.download.SniffedStream
import com.seriecast.app.download.StreamSniffer

/**
 * Full-screen stream capture.
 *
 * The player chain of an aggregator site lives three iframes deep, behind its own play buttons and
 * with ads on top. Hosting the WebView inside the scrolling download form (the old 420dp card) made
 * that chain practically unreachable: the container scrolled, the page scrolled, and the server list
 * sat below both. Here the WebView owns the whole screen and the captured candidates move to a
 * compact strip at the bottom, so nothing competes for the same gesture.
 *
 * The page is fenced by [NavigationGuard]: popups never open and a main-frame redirect only
 * proceeds inside the player chain. The blocked counters are shown, and the address bar is the way
 * out when the guard refuses a host the user actually wants.
 */
@Composable
fun CaptureScreen(
    sniffer: StreamSniffer,
    guard: NavigationGuard,
    initialUrl: String,
    onClose: () -> Unit,
    onUse: (SniffedStream) -> Unit,
) {
    val candidates by sniffer.candidates.collectAsStateWithLifecycle()
    val blocked by guard.blocked.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(initialUrl) }
    var loadedUrl by rememberSaveable { mutableStateOf(initialUrl.takeIf { it.isNotBlank() }) }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Surface(tonalElevation = 3.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar la captura")
                    }
                    Text(
                        "Capturar stream",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            sniffer.clear()
                            guard.clear()
                        },
                    ) { Text("Limpiar") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Dirección de la página") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { loadedUrl = address.trim().ifBlank { "https://www.google.com" } }) {
                        Text("Ir")
                    }
                }
                Text(
                    "Tocá el servidor y después play en el reproductor. Cuando el stream aparezca " +
                        "abajo, tocá Usar.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (blocked.isNotEmpty()) {
                    val popups = guard.countOf(BlockedKind.POPUP)
                    val redirects = guard.countOf(BlockedKind.NAVIGATION)
                    // A popup is refused before its destination is known, so only a redirect has a
                    // host worth showing.
                    val lastHost = blocked.first().host
                    val summary = buildString {
                        append("Bloqueado: $popups ventana(s) · $redirects redirección(es)")
                        if (lastHost.isNotEmpty()) append(" · último: $lastHost")
                    }
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Si es un servidor que sí querés, pegá su dirección arriba y tocá Ir.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        val current = loadedUrl
        if (current.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pegá la dirección del episodio y tocá Ir.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SnifferWebView(
                sniffer = sniffer,
                guard = guard,
                loadUrl = current,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }

        if (candidates.isNotEmpty()) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .navigationBarsPadding()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Streams detectados (${candidates.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(candidates, key = { it.url }) { stream ->
                        CandidateCard(stream = stream, onUse = { onUse(stream) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(stream: SniffedStream, onUse: () -> Unit) {
    Card(
        // A LazyRow measures its children against an unbounded width, so the card needs an
        // explicit width instead of a fraction: fillMaxWidth() collapses it to the button.
        modifier = Modifier.width(300.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${stream.kind} · ${stream.displayHost}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stream.displayTail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                )
                Text(
                    if (stream.referer != null) "con Referer del reproductor" else "sin Referer",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onUse) { Text("Usar") }
        }
    }
}
