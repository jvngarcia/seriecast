package com.seriecast.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * File layout and naming.
 *
 * Videos live in the app-specific external Movies folder: a real filesystem path (so Python and
 * yt-dlp write to it directly), no runtime permission needed on any Android version, and it
 * survives reboots. `publishToMediaStore` is the explicit opt-in that copies the file into the
 * public `Movies/Doramas` collection so it shows up in a file manager or gallery.
 */
object Storage {

    const val FOLDER_NAME = "Doramas"
    const val PUBLIC_RELATIVE_PATH = "Movies/$FOLDER_NAME"

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun mediaDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
        val dir = File(root, FOLDER_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            // Fall back to internal storage rather than failing the download outright.
            val internal = File(context.filesDir, "Movies/$FOLDER_NAME")
            internal.mkdirs()
            return internal
        }
        return dir
    }

    fun sanitize(raw: String, maxLength: Int = 110): String {
        val cleaned = ILLEGAL.replace(raw, " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\.+$"), "")
            .trim()
        return cleaned.take(maxLength).ifBlank { "episodio" }
    }

    /**
     * Builds the legible file name: `Serie - S01E02 - Titulo`.
     * Any of the parts may be missing; the result is always a safe single path segment.
     */
    fun buildBaseName(series: String?, season: Int?, episode: Int?, title: String): String {
        val cleanTitle = sanitize(title)
        val cleanSeries = series?.let { sanitize(it) }?.takeIf { it.isNotBlank() }
        return when {
            cleanSeries != null && season != null && episode != null ->
                "$cleanSeries - ${formatMarker(season, episode)} - $cleanTitle"
            cleanSeries != null && episode != null ->
                "$cleanSeries - Ep$episode - $cleanTitle"
            cleanSeries != null -> "$cleanSeries - $cleanTitle"
            else -> cleanTitle
        }
    }

    fun formatMarker(season: Int, episode: Int): String =
        String.format(Locale.US, "S%02dE%02d", season, episode)

    /** Tries to read `SxxExx` out of a file name so the UI can show season/episode. */
    fun parseMarker(fileName: String): Pair<Int, Int>? {
        val match = Regex("S(\\d{1,2})E(\\d{1,3})", RegexOption.IGNORE_CASE).find(fileName) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    /**
     * Copies a finished file into the public Movies collection.
     * Requires Android 10+; returns null on older versions or on failure.
     */
    fun publishToMediaStore(context: Context, file: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: error("no output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
