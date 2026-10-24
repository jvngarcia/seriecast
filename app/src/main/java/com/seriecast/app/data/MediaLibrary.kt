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
            if (file.isFile && MediaFiles.isTemporary(file.name)) runCatching { file.delete() }
        }
    }

    private fun isPlayable(file: File): Boolean = MediaFiles.isPlayable(file.name)

    private fun titleOf(file: File): String =
        file.nameWithoutExtension.replace('_', ' ').trim().ifBlank { file.name }
}
