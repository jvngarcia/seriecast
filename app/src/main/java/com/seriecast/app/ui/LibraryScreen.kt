package com.seriecast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seriecast.app.R
import com.seriecast.app.service.DlnaController

@Composable
fun LibraryScreen(viewModel: AppViewModel, onGoToDownload: () -> Unit) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val server by viewModel.serverState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ServerCard(state = server, onStart = viewModel::startServer, onStop = viewModel::stopServer) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Videos descargados (${videos.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::refreshLibrary) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar biblioteca")
                }
            }
        }

        if (videos.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Todavía no hay episodios descargados.", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Copiá la URL del stream o capturala con el navegador integrado, y descargala en MP4.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(viewModel.libraryPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Button(onClick = onGoToDownload) { Text("Ir a Descargar") }
                    }
                }
            }
        }

        items(videos, key = { it.entry.objectId }) { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VideoThumbnail(
                        file = row.entry.file,
                        modifier = Modifier.size(width = 96.dp, height = 54.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Row {
                            row.marker?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(" · ", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(row.sizeLabel, style = MaterialTheme.typography.labelSmall)
                            row.durationLabel?.let {
                                Text(" · $it", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.delete(row) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Borrar")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ServerCard(
    state: DlnaController.State,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state is DlnaController.State.Running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (state) {
                    is DlnaController.State.Running -> stringResource(R.string.server_running)
                    is DlnaController.State.Failed -> "No se pudo iniciar"
                    DlnaController.State.Stopped -> stringResource(R.string.server_idle)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            when (state) {
                is DlnaController.State.Running -> {
                    Text(
                        text = "http://${state.info.host}:${state.info.port}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("Nombre en la red: ${state.info.friendlyName}", style = MaterialTheme.typography.bodySmall)
                    Text("Videos publicados: ${state.info.videos}", style = MaterialTheme.typography.bodySmall)
                }

                is DlnaController.State.Failed -> {
                    Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "El servidor necesita Wi-Fi (o Ethernet) en la misma red que el televisor.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                DlnaController.State.Stopped -> Text(
                    "El televisor no puede ver el teléfono hasta que inicies el servidor.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is DlnaController.State.Running) {
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.server_stop)) }
                } else {
                    Button(onClick = onStart) { Text(stringResource(R.string.server_start)) }
                }
            }

            Text(
                text = stringResource(R.string.tv_instructions),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
