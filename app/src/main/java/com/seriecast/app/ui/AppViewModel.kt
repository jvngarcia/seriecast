package com.seriecast.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seriecast.app.data.MediaLibrary
import com.seriecast.app.data.NetworkStatus
import com.seriecast.app.data.Storage
import com.seriecast.app.data.VideoMetadata
import com.seriecast.app.dlna.DlnaServer
import com.seriecast.app.dlna.MediaEntry
import com.seriecast.app.download.AuthContext
import com.seriecast.app.download.DownloadEngine
import com.seriecast.app.download.DownloadOutcome
import com.seriecast.app.download.DownloadProgress
import com.seriecast.app.download.Mp4Remuxer
import com.seriecast.app.download.ProbeOutcome
import com.seriecast.app.download.StreamSniffer
import com.seriecast.app.download.YtDlpEngine
import com.seriecast.app.service.DlnaController
import com.seriecast.app.service.DlnaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AppViewModel(application: Application) : AndroidViewModel(application) {

    data class VideoRow(
        val entry: MediaEntry,
        val sizeLabel: String,
        val durationLabel: String?,
        val marker: String?,
    )

    data class DownloadForm(
        val url: String = "",
        val referer: String = "",
        val cookie: String = "",
        val series: String = "",
        val season: String = "",
        val episode: String = "",
        val title: String = "",
        val publishToGallery: Boolean = false,
        val maxHeight: String = "",
    )

    sealed interface Job {
        data class Running(val stage: String, val progress: DownloadProgress?) : Job
        data class Done(val file: File, val remuxNote: String?) : Job
        data class Error(val message: String) : Job
    }

    private val app = application
    private val library = MediaLibrary(app)
    private val engine: DownloadEngine = YtDlpEngine(app)
    private val cancelRequested = AtomicBoolean(false)

    val sniffer = StreamSniffer()

    private val _videos = MutableStateFlow<List<VideoRow>>(emptyList())
    val videos: StateFlow<List<VideoRow>> = _videos.asStateFlow()

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _engineVersion = MutableStateFlow("consultando…")
    val engineVersion: StateFlow<String> = _engineVersion.asStateFlow()

    private val _probe = MutableStateFlow<ProbeOutcome?>(null)
    val probe: StateFlow<ProbeOutcome?> = _probe.asStateFlow()

    private val _selfTest = MutableStateFlow<DlnaServer.SelfTest?>(null)
    val selfTest: StateFlow<DlnaServer.SelfTest?> = _selfTest.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val serverState: StateFlow<DlnaController.State> = DlnaController.state

    val libraryPath: String get() = library.directory().absolutePath

    init {
        refreshLibrary()
        viewModelScope.launch(Dispatchers.IO) { library.deleteOrphanTemporaries() }
        viewModelScope.launch {
            _engineVersion.value = (engine as? YtDlpEngine)?.version() ?: "motor no disponible"
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                // The DLNA snapshot must be rebuilt on the same IO pass, not on the main thread.
                DlnaController.refreshLibrary()
                library.scan().map { entry ->
                    VideoRow(
                        entry = entry,
                        sizeLabel = Storage.humanSize(entry.sizeBytes),
                        durationLabel = entry.durationMs?.let { formatDuration(it) },
                        marker = Storage.parseMarker(entry.file.name)?.let { (season, episode) ->
                            Storage.formatMarker(season, episode)
                        },
                    )
                }
            }
            _videos.value = rows
        }
    }

    fun startServer() {
        DlnaService.clearFailure()
        DlnaService.start(app)
        // The service resolves the LAN; surface its refusal so the button never looks broken.
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            DlnaService.notificationFailure?.let { _message.value = it }
        }
    }

    fun stopServer() {
        _selfTest.value = null
        DlnaService.stop(app)
    }

    fun runSelfTest() {
        viewModelScope.launch {
            _busy.value = true
            _selfTest.value = withContext(Dispatchers.IO) { DlnaController.selfTest() }
            _busy.value = false
        }
    }

    fun probe(form: DownloadForm) {
        val url = form.url.trim()
        if (url.isBlank()) {
            _message.value = "Pegá primero la URL del stream."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _probe.value = null
            val auth = AuthContext(
                referer = form.referer.ifBlank { null },
                cookie = form.cookie.ifBlank { null },
            )
            _probe.value = engine.probe(url, auth)
            _busy.value = false
        }
    }

    fun clearProbe() {
        _probe.value = null
    }

    fun startDownload(form: DownloadForm) {
        if (_job.value is Job.Running) return
        val url = form.url.trim()
        if (url.isBlank()) {
            _message.value = "Pegá la URL del stream o capturala desde el navegador."
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            _message.value = "La URL tiene que empezar con http:// o https://"
            return
        }
        val network = NetworkStatus.check(app)
        if (!network.online) {
            _job.value = Job.Error(
                "Sin conexión a internet (${network.description}). El servidor DLNA funciona sin internet, pero la descarga no.",
            )
            return
        }

        cancelRequested.set(false)
        viewModelScope.launch {
            val title = form.title.ifBlank { guessTitle(url) }
            val baseName = Storage.buildBaseName(
                series = form.series.ifBlank { null },
                season = form.season.trim().toIntOrNull(),
                episode = form.episode.trim().toIntOrNull(),
                title = title,
            )
            val auth = AuthContext(
                referer = form.referer.ifBlank { null },
                cookie = form.cookie.ifBlank { null },
            )
            val maxHeight = form.maxHeight.trim().toIntOrNull() ?: 0

            _job.value = Job.Running("Preparando la descarga…", null)
            val outcome = engine.download(
                url = url,
                outDir = library.directory(),
                baseName = baseName,
                auth = auth,
                maxHeight = maxHeight,
                onProgress = { progress ->
                    val current = _job.value
                    if (current is Job.Running || current == null) {
                        _job.value = Job.Running(describe(progress), progress)
                    }
                },
                isCancelled = { cancelRequested.get() },
            )

            when (outcome) {
                is DownloadOutcome.Success -> {
                    _job.value = Job.Running("Ajustando el contenedor a MP4…", null)
                    val remux = withContext(Dispatchers.IO) {
                        Mp4Remuxer.ensureMp4(outcome.file, baseName)
                    }
                    if (form.publishToGallery) {
                        _job.value = Job.Running("Copiando a Movies/Doramas…", null)
                        withContext(Dispatchers.IO) {
                            Storage.publishToMediaStore(app, remux.file, remux.file.name)
                        }
                    }
                    refreshLibrary()
                    _job.value = Job.Done(remux.file, remux.note)
                }

                is DownloadOutcome.Cancelled -> {
                    _job.value = null
                    _message.value = "Descarga cancelada. Los archivos parciales se descartan."
                    library.deleteOrphanTemporaries()
                }

                is DownloadOutcome.Failure -> {
                    _job.value = Job.Error(outcome.detail)
                }
            }
        }
    }

    fun cancelDownload() {
        cancelRequested.set(true)
    }

    fun clearJob() {
        _job.value = null
    }

    fun delete(row: VideoRow) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { library.delete(row.entry) }
            _message.value = if (removed) {
                "Borrado: ${row.entry.file.name}"
            } else {
                "No se pudo borrar ${row.entry.file.name}"
            }
            refreshLibrary()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun consumeSnifferCandidate(stream: com.seriecast.app.download.SniffedStream): DownloadForm = DownloadForm(
        url = stream.url,
        referer = stream.pageUrl,
        cookie = stream.cookie.orEmpty(),
    )

    private fun describe(progress: DownloadProgress): String = when {
        progress.isFinishing -> "Procesando el archivo descargado…"
        progress.percent >= 0f -> String.format(
            Locale.US,
            "Descargando %.0f%% — %s de %s (%s/s)",
            progress.percent,
            Storage.humanSize(progress.downloadedBytes),
            Storage.humanSize(progress.totalBytes),
            Storage.humanSize(progress.speedBytesPerSecond.toLong()),
        )
        progress.downloadedBytes > 0 -> "Descargando ${Storage.humanSize(progress.downloadedBytes)}…"
        else -> "Conectando con el servidor del stream…"
    }

    private fun guessTitle(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/')
        val cleaned = last.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()
        return cleaned.ifBlank { "episodio" }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return if (totalSeconds >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
        } else {
            String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }

    /** Prefetch used by the list thumbnails; the composable owns the actual bitmap lifecycle. */
    fun warmThumbnail(entry: MediaEntry) {
        viewModelScope.launch(Dispatchers.IO) { VideoMetadata.thumbnail(entry.file) }
    }
}
