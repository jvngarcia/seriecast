package com.seriecast.app.download

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.seriecast.app.SerieCastApp
import com.seriecast.app.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * yt-dlp downloader running through Chaquopy.
 *
 * Python is only allowed to fetch the file. Naming, container normalization (TS/MKV -> MP4) and
 * publishing to the media library happen on the Kotlin side, so a yt-dlp failure never leaves the
 * library in a half-updated state.
 */
class YtDlpEngine(private val context: Context) : DownloadEngine {

    private fun module(): PyObject? {
        if (!SerieCastApp.pythonAvailable) return null
        return runCatching { Python.getInstance().getModule("downloader") }
            .onFailure { Log.e(TAG, "python module unavailable", it) }
            .getOrNull()
    }

    suspend fun version(): String = withContext(Dispatchers.IO) {
        val module = module() ?: return@withContext "Python no disponible (${SerieCastApp.pythonError ?: "sin detalle"})"
        runCatching {
            val json = JSONObject(module.callAttr("version").toString())
            if (json.optBoolean("ok")) "yt-dlp ${json.optString("version")}"
            else "yt-dlp no instalado: ${json.optString("error")}"
        }.getOrElse { "yt-dlp no consultable: ${it.message}" }
    }

    override suspend fun probe(url: String, auth: AuthContext): ProbeOutcome = withContext(Dispatchers.IO) {
        val module = module()
            ?: return@withContext ProbeOutcome.Failure("Python no disponible: ${SerieCastApp.pythonError ?: "sin detalle"}")
        try {
            val result = JSONObject(
                module.callAttr(
                    "probe",
                    url,
                    auth.referer,
                    auth.userAgent,
                    auth.cookie,
                ).toString(),
            )
            if (result.optBoolean("ok")) {
                ProbeOutcome.Success(
                    title = result.optString("title").takeIf { it.isNotBlank() },
                    durationSeconds = result.optDouble("duration").takeIf { !it.isNaN() },
                    protocols = result.optJSONArray("protocols")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    } ?: emptyList(),
                    formatCount = result.optInt("format_count"),
                )
            } else {
                ProbeOutcome.Failure(result.optString("error").ifBlank { "extracción fallida" })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            ProbeOutcome.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    override suspend fun download(
        url: String,
        outDir: File,
        baseName: String,
        auth: AuthContext,
        maxHeight: Int,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val module = module()
            ?: return@withContext DownloadOutcome.Failure(
                DownloadError.PYTHON_UNAVAILABLE,
                "Python/yt-dlp no está disponible: ${SerieCastApp.pythonError ?: "sin detalle"}",
            )

        outDir.mkdirs()
        if (!outDir.isDirectory) {
            return@withContext DownloadOutcome.Failure(DownloadError.STORAGE_FULL, "No se pudo crear $outDir")
        }

        val progressBridge = ProgressBridge(onProgress)
        val cancelBridge = CancelBridge(isCancelled)

        try {
            val raw = module.callAttr(
                "download",
                url,
                outDir.absolutePath,
                baseName,
                auth.referer,
                auth.userAgent,
                auth.cookie,
                maxHeight,
                progressBridge,
                cancelBridge,
            ).toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                val error = json.optString("error").ifBlank { "descarga fallida" }
                return@withContext when {
                    error == "cancelled" -> DownloadOutcome.Cancelled
                    error.contains("archivo-no-encontrado") ->
                        DownloadOutcome.Failure(DownloadError.DOWNLOAD_FAILED, "yt-dlp no dejó ningún archivo en $outDir")
                    else -> DownloadOutcome.Failure(DownloadError.DOWNLOAD_FAILED, error)
                }
            }
            val path = json.optString("path")
            val downloaded = File(path)
            if (!downloaded.isFile || downloaded.length() == 0L) {
                return@withContext DownloadOutcome.Failure(
                    DownloadError.DOWNLOAD_FAILED,
                    "El archivo descargado no existe o está vacío: $path",
                )
            }
            DownloadOutcome.Success(
                file = downloaded,
                title = json.optString("title").takeIf { it.isNotBlank() },
                durationSeconds = json.optDouble("duration").takeIf { !it.isNaN() },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "download failed", t)
            val message = t.message ?: t.javaClass.simpleName
            if (message.contains("cancelled-by-user")) DownloadOutcome.Cancelled
            else DownloadOutcome.Failure(DownloadError.UNKNOWN, message)
        }
    }

    /** yt-dlp progress hook target. Called from the Python thread. */
    class ProgressBridge(private val sink: (DownloadProgress) -> Unit) {
        @Suppress("unused") // invoked from Python
        fun onProgress(status: String, percent: Double, downloaded: Long, total: Long, speed: Double) {
            runCatching {
                sink(
                    DownloadProgress(
                        status = status,
                        percent = percent.toFloat(),
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSecond = speed,
                    ),
                )
            }
        }
    }

    /** Polled by the Python progress hook on every fragment. */
    class CancelBridge(private val check: () -> Boolean) {
        @Suppress("unused") // invoked from Python
        fun isCancelled(): Boolean = check()
    }

    private companion object {
        const val TAG = "YtDlpEngine"
    }
}
