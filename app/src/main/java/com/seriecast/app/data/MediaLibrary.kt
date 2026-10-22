package com.seriecast.app.data

import android.content.Context
import com.seriecast.app.dlna.MediaEntry
import java.io.File
import java.util.Locale

/**
 * The single source of truth for "what is on the phone".
 *
 * Only finished video files count: partial downloads (`*.part`, `*.ytdl`, fragment temp files) are
 * skipped so a television never sees a half-written episode.
 */
class MediaLibrary(private val context: Context) {

    fun directory(): File = Storage.mediaDir(context)

    fun scan(): List<MediaEntry> {
        val dir = directory()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.length() > 0 }
            .filter { isPlayable(it) }
            .map { file ->
                MediaEntry.fromFile(
                    file = file,
                    title = titleOf(file),
                    durationMs = VideoMetadata.durationMs(file),
                )
            }
            .sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }))
            .toList()
    }

    fun delete(entry: MediaEntry): Boolean {
        VideoMetadata.invalidate(entry.file)
        return runCatching { entry.file.delete() }.getOrDefault(false)
    }

    fun deleteOrphanTemporaries() {
        directory().listFiles()?.forEach { file ->
            if (file.isFile && isTemporary(file.name)) runCatching { file.delete() }
        }
    }

    private fun isPlayable(file: File): Boolean {
        if (isTemporary(file.name)) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in PLAYABLE_EXTENSIONS
    }

    private fun isTemporary(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".part") || lower.endsWith(".ytdl") || lower.endsWith(".tmp") ||
            lower.contains(".part-frag") || lower.endsWith(".download")
    }

    private fun titleOf(file: File): String =
        file.nameWithoutExtension.replace('_', ' ').trim().ifBlank { file.name }

    private companion object {
        val PLAYABLE_EXTENSIONS = setOf("mp4", "m4v", "mkv", "ts", "m2ts", "mts", "avi", "webm", "mov")
    }
}
