package com.seriecast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seriecast.app.download.ProbeOutcome

@Composable
fun DownloadScreen(
    viewModel: AppViewModel,
    form: AppViewModel.DownloadForm,
    onFormChange: (AppViewModel.DownloadForm) -> Unit,
    captureUrl: String,
    onCaptureUrlChange: (String) -> Unit,
    onOpenCapture: () -> Unit,
) {
    val job by viewModel.job.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val engineVersion by viewModel.engineVersion.collectAsStateWithLifecycle()
    val candidates by viewModel.sniffer.candidates.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Descarga de episodios", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(engineVersion, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)

        // ---------------------------------------------------------------- capture
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Capturar la URL del stream", fontWeight = FontWeight.SemiBold)
                Text(
                    "Abrí la página del episodio en la pantalla de captura, tocá el servidor y después " +
                        "play. La captura ocupa toda la pantalla para que el reproductor se pueda usar; " +
                        "volvés acá con el botón Usar.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = captureUrl,
                    onValueChange = onCaptureUrlChange,
                    label = { Text("Dirección de la página") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenCapture) { Text("Abrir navegador") }
                    if (candidates.isNotEmpty()) {
                        OutlinedButton(onClick = onOpenCapture) {
                            Text("Ver capturas (${candidates.size})")
                        }
                    }
                }

                if (candidates.isNotEmpty()) {
                    val latest = candidates.first()
                    Text(
                        "Último detectado: ${latest.kind} · ${latest.displayHost}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- manual
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. URL, credenciales y nombre", fontWeight = FontWeight.SemiBold)
                Text(
                    "Si ya tenés la URL del .m3u8 o .mp4, pegala acá. Referer y Cookie son opcionales, " +
                        "pero muchos CDN rechazan la descarga sin ellos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = form.url,
                    onValueChange = { onFormChange(form.copy(url = it)) },
                    label = { Text("URL del stream (m3u8 / mp4)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.referer,
                    onValueChange = { onFormChange(form.copy(referer = it)) },
                    label = { Text("Referer (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.cookie,
                    onValueChange = { onFormChange(form.copy(cookie = it)) },
                    label = { Text("Cookie (opcional)") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 110.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.series,
                        onValueChange = { onFormChange(form.copy(series = it)) },
                        label = { Text("Serie") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.season,
                        onValueChange = { onFormChange(form.copy(season = it.filter(Char::isDigit))) },
                        label = { Text("Temporada") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.episode,
                        onValueChange = { onFormChange(form.copy(episode = it.filter(Char::isDigit))) },
                        label = { Text("Episodio") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { onFormChange(form.copy(title = it)) },
                    label = { Text("Título del episodio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.maxHeight,
                    onValueChange = { onFormChange(form.copy(maxHeight = it.filter(Char::isDigit))) },
                    label = { Text("Altura máxima en píxeles (vacío = mejor calidad)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = form.publishToGallery,
                        onCheckedChange = { onFormChange(form.copy(publishToGallery = it)) },
                    )
                    Text(
                        "Copiar también a Movies/Doramas (visible en Galería, ocupa el doble)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.probe(form) },
                enabled = !busy && job !is AppViewModel.Job.Running,
                modifier = Modifier.weight(1f),
            ) { Text("Probar URL") }
            Button(
                onClick = { viewModel.startDownload(form) },
                enabled = job !is AppViewModel.Job.Running,
                modifier = Modifier.weight(1f),
            ) { Text("Descargar") }
        }

        when (val current = probe) {
            is ProbeOutcome.Success -> MessageCard(
                title = "La URL responde",
                body = buildString {
                    append("Título: ").append(current.title ?: "(sin título)").append('\n')
                    current.durationSeconds?.let { append("Duración: ").append(it.toInt()).append(" s\n") }
                    append("Formatos: ").append(current.formatCount).append('\n')
                    if (current.protocols.isNotEmpty()) append("Protocolos: ").append(current.protocols.joinToString())
                },
            )
            is ProbeOutcome.Failure -> MessageCard(title = "La URL no responde", body = current.detail, error = true)
            null -> Unit
        }

        when (val current = job) {
            is AppViewModel.Job.Running -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current.stage, style = MaterialTheme.typography.bodyMedium)
                    val percent = current.progress?.percent ?: -1f
                    if (percent >= 0f) {
                        LinearProgressIndicator(
                            progress = { (percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = viewModel::cancelDownload) { Text("Cancelar") }
                }
            }

            is AppViewModel.Job.Done -> MessageCard(
                title = "Listo: ${current.file.name}",
                body = buildString {
                    append("Archivo: ").append(current.file.absolutePath).append('\n')
                    append("Tamaño: ").append(com.seriecast.app.data.Storage.humanSize(current.file.length()))
                    current.remuxNote?.let { append("\nAviso: ").append(it) }
                    append("\nEl servidor DLNA lo publica automáticamente si está activo.")
                },
                onDismiss = viewModel::clearJob,
            )

            is AppViewModel.Job.Error -> MessageCard(
                title = "La descarga falló",
                body = current.message,
                error = true,
                onDismiss = viewModel::clearJob,
            )

            null -> Unit
        }

        Text(
            "Los archivos se guardan en ${viewModel.libraryPath}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Formatos aceptados por el televisor: MP4 con video H.264 y audio AAC.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    error: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
            onDismiss?.let { dismiss -> TextButton(onClick = dismiss) { Text("Cerrar") } }
        }
    }
}
