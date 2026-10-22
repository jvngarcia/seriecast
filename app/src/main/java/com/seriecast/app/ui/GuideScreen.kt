package com.seriecast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seriecast.app.R
import com.seriecast.app.service.DlnaController

@Composable
fun GuideScreen(viewModel: AppViewModel) {
    val server by viewModel.serverState.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cómo lo ve el televisor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Step(
            number = "1",
            title = "Mismo Wi-Fi, sin aislamiento",
            body = "El teléfono y el televisor tienen que estar en la misma red. Si el router tiene " +
                "\"aislamiento de AP\" o \"client isolation\" activo, o estás en la red de invitados, " +
                "el televisor nunca va a descubrir el teléfono aunque la IP sea correcta.",
        )
        Step(
            number = "2",
            title = "Descargá el episodio en MP4",
            body = "El televisor antiguo reproduce MP4 con video H.264 y audio AAC. La app remuxea " +
                "sola cuando yt-dlp deja TS o MKV, pero no puede convertir codecs que el televisor " +
                "no decodifica (por ejemplo audio AC3 o DTS).",
        )
        Step(
            number = "3",
            title = "Iniciá el servidor",
            body = "Con el servidor activo, el teléfono se anuncia por SSDP cada 10 minutos y responde " +
                "a las búsquedas del televisor. Vas a ver la IP y el puerto en la pestaña Videos.",
        )
        Step(
            number = "4",
            title = "Buscá el teléfono en el televisor",
            body = stringResource(R.string.tv_instructions) +
                ". Suele estar en Fuente / Entrada → DLNA, o en Red → Dispositivos multimedia.",
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Autodiagnóstico", fontWeight = FontWeight.SemiBold)
                Text(
                    "Comprueba desde el propio teléfono que el HTTP responde y que SSDP quedó escuchando. " +
                        "Si esto pasa y el televisor no lo ve, el problema está en la red (paso 1).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = viewModel::runSelfTest,
                    enabled = !busy && server is DlnaController.State.Running,
                ) { Text("Ejecutar autodiagnóstico") }

                if (server !is DlnaController.State.Running) {
                    Text("Iniciá el servidor para poder diagnosticar.", style = MaterialTheme.typography.bodySmall)
                }

                selfTest?.let { result ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Check("HTTP responde en 127.0.0.1", result.httpReachable)
                        Check("description.xml declara MediaServer:1 + ContentDirectory", result.descriptionValid)
                        Check("SSDP escuchando en 239.255.255.250:1900", result.ssdpBound)
                        result.ssdpError?.let {
                            Text("Error SSDP: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(result.detail, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (result.discoverable) {
                                "Todo listo del lado del teléfono. Si el televisor no aparece, revisá el paso 1."
                            } else {
                                "Falta algo del lado del teléfono: revisá los puntos en rojo."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Si el televisor no descubre el servidor", fontWeight = FontWeight.SemiBold)
                Bullet("Probá abrir en otro dispositivo la dirección http://IP:PUERTO que muestra la app. Si no abre, es firewall o aislamiento.")
                Bullet("Desactivá el \"aislamiento de AP\" / \"client isolation\" del router y usá la red principal, no la de invitados.")
                Bullet("Apagá y prendé el televisor: muchos modelos solo buscan servidores DLNA al encenderse.")
                Bullet("Revisá que el teléfono no esté con VPN activa y que no tenga el ahorro de batería matando el servicio.")
                Bullet("Algunos televisores solo listan servidores con ContentDirectory:1; esta app no publica un renderer, publica un MediaServer.")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Dónde quedan los archivos", fontWeight = FontWeight.SemiBold)
                Text(viewModel.libraryPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    "Es la carpeta privada de la app en el almacenamiento externo: no requiere permisos y " +
                        "sobrevive reinicios. Si querés verla desde el explorador de archivos o la galería, " +
                        "activá la copia a Movies/Doramas al descargar.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Step(number: String, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text(
        text = (if (ok) "✓ " else "✗ ") + label,
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", style = MaterialTheme.typography.bodySmall)
}
