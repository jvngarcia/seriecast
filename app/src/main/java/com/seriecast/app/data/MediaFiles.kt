package com.seriecast.app.data

import java.util.Locale

/**
 * File-name rules for "what is an episode" and "what is still being written".
 *
 * Pulled out of [MediaLibrary] so it can be unit-tested without a `Context`. The case that matters:
 * a remux interrupted halfway leaves a multi-gigabyte MP4 with no `moov` index, and listing it as an
 * episode makes the television refuse a file the app claims is ready.
 */
object MediaFiles {

    const val REMUX_SUFFIX = ".remux.mp4"

    private val PLAYABLE_EXTENSIONS =
        setOf("mp4", "m4v", "mkv", "ts", "m2ts", "mts", "avi", "webm", "mov")

    fun isTemporary(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(REMUX_SUFFIX) ||
            lower.endsWith(".part") ||
            lower.endsWith(".ytdl") ||
            lower.endsWith(".tmp") ||
            lower.contains(".part-frag") ||
            lower.endsWith(".download")
    }

    fun isPlayable(name: String): Boolean {
        if (isTemporary(name)) return false
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in PLAYABLE_EXTENSIONS
    }
}
