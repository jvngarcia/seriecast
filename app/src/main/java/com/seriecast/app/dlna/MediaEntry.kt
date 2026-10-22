package com.seriecast.app.dlna

import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

/**
 * One video exposed through the UPnP ContentDirectory.
 *
 * [objectId] must stay stable across process restarts, otherwise televisions that cache a browse
 * result will hand back a dangling ObjectID when the user presses play.
 */
data class MediaEntry(
    val objectId: String,
    val title: String,
    val file: File,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long?,
) {
    /** Relative path used as the media URL tail, so serving never depends on object identity. */
    val mediaPath: String get() = encodePath(relativePath)

    val relativePath: String get() = file.name

    val durationAttribute: String? get() = durationMs?.let { formatDuration(it) }

    companion object {
        const val PARENT_ID = "0"

        fun fromFile(file: File, title: String, durationMs: Long?): MediaEntry = MediaEntry(
            objectId = stableId(file),
            title = title,
            file = file,
            mimeType = mimeTypeOf(file.name),
            sizeBytes = file.length(),
            durationMs = durationMs,
        )

        /** Deterministic per file name: survives restarts and folder rescans. */
        fun stableId(file: File): String =
            "m" + UUID.nameUUIDFromBytes(file.name.toByteArray(Charsets.UTF_8)).toString().replace("-", "")

        fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            return String.format(
                Locale.US,
                "%02d:%02d:%02d.%03d",
                totalSeconds / 3600,
                (totalSeconds % 3600) / 60,
                totalSeconds % 60,
                ms % 1000,
            )
        }

        /** Percent-encodes one path segment but keeps '/' so nested folders survive. */
        fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

        fun decodePath(path: String): String = path.split('/').joinToString("/") { segment ->
            URLDecoder.decode(segment, "UTF-8")
        }
    }
}
