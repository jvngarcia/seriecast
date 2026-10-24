package com.seriecast.app.ui

import com.seriecast.app.data.Storage
import java.util.Locale

/**
 * The one status line the user reads while a job runs.
 *
 * It exists as a pure function because the two things that were wrong in the field were both
 * invisible in the code and obvious on the phone: a long phase with no percentage, and no answer to
 * "how much longer". Every number here comes from measured data — no estimate is invented when the
 * speed or the total size is unknown.
 */
object ProgressText {

    const val DOWNLOAD = "download"
    const val REMUX = "remux"

    private const val REMUX_STAGE = "Ajustando el contenedor a MP4…"

    fun line(
        kind: String,
        percent: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Double,
    ): String {
        val head = if (kind == REMUX) REMUX_STAGE else "Descargando"
        if (percent < 0f || totalBytes <= 0L) {
            return when {
                downloadedBytes > 0L -> "$head ${Storage.humanSize(downloadedBytes)}…"
                kind == REMUX -> REMUX_STAGE
                else -> "Conectando con el servidor del stream…"
            }
        }
        return buildString {
            append(head)
            append(' ')
            append(String.format(Locale.US, "%.0f%%", percent))
            append(" — ")
            append(Storage.humanSize(downloadedBytes))
            append(" de ")
            append(Storage.humanSize(totalBytes))
            if (speedBytesPerSecond > 0.0) {
                append(" (")
                append(Storage.humanSize(speedBytesPerSecond.toLong()))
                append("/s)")
            }
            etaLabel(totalBytes - downloadedBytes, speedBytesPerSecond)?.let { append(" · ").append(it) }
        }
    }

    /** `quedan ~7 min`, `menos de 1 min`, or null when there is nothing honest to say. */
    fun etaLabel(remainingBytes: Long, speedBytesPerSecond: Double): String? {
        if (speedBytesPerSecond <= 0.0 || remainingBytes <= 0L) return null
        val seconds = (remainingBytes / speedBytesPerSecond).toLong()
        if (seconds < 60L) return "menos de 1 min"
        val minutes = (seconds + 59L) / 60L
        return if (minutes < 60L) {
            "quedan ~$minutes min"
        } else {
            "quedan ~${minutes / 60L} h ${minutes % 60L} min"
        }
    }
}
