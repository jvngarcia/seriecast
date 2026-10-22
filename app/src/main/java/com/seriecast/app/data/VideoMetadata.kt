package com.seriecast.app.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Duration and thumbnail extraction with a small in-memory cache. */
object VideoMetadata {

    private val durationCache = ConcurrentHashMap<String, Long>()
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()

    private fun key(file: File): String = "${file.absolutePath}:${file.length()}"

    fun durationMs(file: File): Long? = try {
        durationCache.getOrPut(key(file)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                // release() exists since API 10; close() only since API 29 (minSdk here is 26).
                runCatching { retriever.release() }
            }
        }.takeIf { it > 0 }
    } catch (t: Throwable) {
        null
    }

    fun thumbnail(file: File, atUs: Long = 3_000_000L): Bitmap? {
        val cacheKey = key(file)
        thumbnailCache[cacheKey]?.let { return it }
        return try {
            val retriever = MediaMetadataRetriever()
            val bitmap = try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
            if (bitmap != null) {
                // Bound memory: six 480px thumbs is plenty for a list that scrolls.
                if (thumbnailCache.size > 6) thumbnailCache.clear()
                thumbnailCache[cacheKey] = bitmap
            }
            bitmap
        } catch (t: Throwable) {
            null
        }
    }

    fun invalidate(file: File) {
        durationCache.remove(key(file))
        thumbnailCache.remove(key(file))
    }
}
