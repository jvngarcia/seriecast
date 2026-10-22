package com.seriecast.app.download

import java.io.File

/** Headers a streaming page needs so the CDN accepts the request. */
data class AuthContext(
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
) {
    val isEmpty: Boolean get() = referer.isNullOrBlank() && userAgent.isNullOrBlank() && cookie.isNullOrBlank()
}

data class DownloadProgress(
    val status: String,
    val percent: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Double,
) {
    val isFinishing: Boolean get() = status == "finished"
}

enum class DownloadError {
    NO_NETWORK,
    PYTHON_UNAVAILABLE,
    EXTRACT_FAILED,
    DOWNLOAD_FAILED,
    CANCELLED,
    STORAGE_FULL,
    UNKNOWN,
}

sealed interface DownloadOutcome {
    data class Success(val file: File, val title: String?, val durationSeconds: Double?) : DownloadOutcome
    data class Failure(val code: DownloadError, val detail: String) : DownloadOutcome
    data object Cancelled : DownloadOutcome
}

sealed interface ProbeOutcome {
    data class Success(
        val title: String?,
        val durationSeconds: Double?,
        val protocols: List<String>,
        val formatCount: Int,
    ) : ProbeOutcome

    data class Failure(val detail: String) : ProbeOutcome
}

/**
 * The download boundary. Kept as an interface so yt-dlp can be replaced (for example by a native
 * HLS downloader) without touching the UI, the service or the library code.
 */
interface DownloadEngine {
    suspend fun probe(url: String, auth: AuthContext): ProbeOutcome

    suspend fun download(
        url: String,
        outDir: File,
        baseName: String,
        auth: AuthContext,
        maxHeight: Int,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): DownloadOutcome
}
