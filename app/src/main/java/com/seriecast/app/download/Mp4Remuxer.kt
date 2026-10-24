package com.seriecast.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Container normalization without ffmpeg.
 *
 * `ffmpeg-kit` was retired and its Maven artifacts removed, so the remux is done with the platform
 * muxer: MediaExtractor demuxes, MediaMuxer writes an MP4 with an AVC video track and an AAC audio
 * track. That covers what old televisions accept, and it costs no APK size.
 *
 * The source is read **once**: both tracks are selected before the loop and samples come out in file
 * order, which is also the interleaved order an MP4 wants. The previous implementation copied video
 * and then audio in two complete passes; because MPEG-TS has no index, the second pass re-parsed the
 * whole file just to find audio packets — 16 minutes of CPU for a 1.1 GB episode.
 *
 * Documented limits: AC-3/DTS/Opus audio cannot go into MP4 this way, and HEVC output depends on the
 * television. When the remux fails or is cancelled the original file is kept, never deleted.
 */
object Mp4Remuxer {

    data class Result(
        val file: File,
        val remuxed: Boolean,
        val note: String?,
        val cancelled: Boolean = false,
    )

    /** What the copy loop knows while it runs: enough for a percentage, a rate and an estimate. */
    data class Progress(
        val percent: Float,
        val processedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double,
    )

    private const val TAG = "Mp4Remuxer"
    private const val BUFFER_SIZE = 4 * 1024 * 1024
    private const val PROGRESS_INTERVAL_NS = 250_000_000L

    fun ensureMp4(
        source: File,
        baseName: String,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        return when (ContainerSniffer.detect(source)) {
            Container.MP4 -> Result(normalizeExtension(source, baseName), false, null)
            Container.MPEG_TS, Container.MATROSKA -> remuxOrKeep(source, baseName, onProgress, isCancelled)
            Container.UNKNOWN -> Result(
                source,
                false,
                "Contenedor no reconocido: se conserva ${source.name} tal cual.",
            )
        }
    }

    /**
     * Output file for a remux.
     *
     * It is never the source itself. yt-dlp's native HLS downloader routinely leaves MPEG-TS bytes
     * inside a file already named `.mp4`, so a naive `"$baseName.mp4"` target resolves to the source:
     * MediaMuxer truncates its output and the download is destroyed before the first sample is
     * written. Internal so the guarantee is unit-tested without touching the platform muxer.
     */
    internal fun remuxTarget(source: File, baseName: String): File {
        val target = File(source.parentFile, "$baseName.remux.mp4")
        check(target.absolutePath != source.absolutePath) {
            "el destino del remux no puede ser el archivo de origen"
        }
        return target
    }

    private fun normalizeExtension(source: File, baseName: String): File {
        if (source.name.endsWith(".mp4", ignoreCase = true)) return source
        val target = File(source.parentFile, "$baseName.mp4")
        if (target == source) return source
        return if (source.renameTo(target)) target else source
    }

    private fun remuxOrKeep(
        source: File,
        baseName: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        val temp = remuxTarget(source, baseName)
        val desired = File(source.parentFile, "$baseName.mp4")
        if (temp.exists()) temp.delete()
        return try {
            if (!remux(source, temp, onProgress, isCancelled)) {
                runCatching { temp.delete() }
                return Result(source, false, null, cancelled = true)
            }
            if (temp.length() <= 0L) {
                runCatching { temp.delete() }
                keepOriginal(source, "El remux a MP4 produjo un archivo vacío.")
            } else {
                // The source is the only copy until this point, so it is deleted only now.
                runCatching { source.delete() }
                val finalFile = if (temp.renameTo(desired)) desired else temp
                Result(finalFile, true, null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "remux failed: ${t.javaClass.simpleName}: ${t.message}", t)
            runCatching { if (temp.exists()) temp.delete() }
            keepOriginal(
                source,
                "No se pudo convertir a MP4 (${t.javaClass.simpleName}). Se conserva el archivo " +
                    "descargado sin convertir: puede que el televisor no lo reproduzca.",
            )
        }
    }

    private fun keepOriginal(source: File, note: String): Result = Result(
        if (ContainerSniffer.detect(source) == Container.MATROSKA &&
            !source.name.endsWith(".mkv", true)
        ) {
            val renamed = File(source.parentFile, source.name.substringBeforeLast('.') + ".mkv")
            if (source.renameTo(renamed)) renamed else source
        } else {
            source
        },
        false,
        note,
    )

    /** @return false when the run was cancelled, so the caller discards the partial output. */
    private fun remux(
        source: File,
        target: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.absolutePath)
            val videoTrack = selectTrack(extractor) { it.startsWith("video/") }
                ?: error("sin pista de video")
            // AAC is what every DLNA television decodes; fall back to any audio track.
            val audioTrack = selectTrack(extractor) { it == MediaFormat.MIMETYPE_AUDIO_AAC }
                ?: selectTrack(extractor) { it.startsWith("audio/") }

            val muxerTracks = LinkedHashMap<Int, Int>()
            muxerTracks[videoTrack] = muxer.addTrack(extractor.getTrackFormat(videoTrack))
            audioTrack?.let { muxerTracks[it] = muxer.addTrack(extractor.getTrackFormat(it)) }

            muxerTracks.keys.forEach { extractor.selectTrack(it) }
            muxer.start()
            val complete = copySamples(
                extractor = extractor,
                muxer = muxer,
                gate = SampleGate(videoTrack, muxerTracks),
                totalBytes = source.length(),
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            if (!complete) return false
            muxer.stop()
            return true
        } finally {
            // Never call extractor.unselectTrack() here: with the extractor at EOF it throws
            // IllegalArgumentException, and that exception used to discard an already-written
            // 1.05 GB MP4 because stop() came after it. release() is enough.
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        gate: SampleGate,
        totalBytes: Long,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        val startedNs = System.nanoTime()
        var reportedAt = 0L
        var processed = 0L

        while (true) {
            if (isCancelled()) return false
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            processed += size
            val track = extractor.sampleTrackIndex
            val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            gate.inspect(track, extractor.sampleTime, keyFrame)?.let { decision ->
                if (size > 0) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = decision.presentationTimeUs
                    info.flags = if (decision.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(decision.muxerIndex, buffer, info)
                }
            }
            val now = System.nanoTime()
            if (now - reportedAt >= PROGRESS_INTERVAL_NS) {
                reportedAt = now
                onProgress(progressOf(processed, totalBytes, startedNs, now))
            }
            if (!extractor.advance()) break
        }
        val doneNs = System.nanoTime()
        onProgress(Progress(100f, processed, totalBytes, rateOf(processed, startedNs, doneNs)))
        return true
    }

    private fun progressOf(processed: Long, totalBytes: Long, startedNs: Long, nowNs: Long): Progress {
        val percent = if (totalBytes > 0L) {
            (processed.toDouble() / totalBytes * 100.0).toFloat().coerceIn(0f, 100f)
        } else {
            -1f
        }
        return Progress(percent, processed, totalBytes, rateOf(processed, startedNs, nowNs))
    }

    private fun rateOf(processed: Long, startedNs: Long, nowNs: Long): Double {
        val elapsedSeconds = (nowNs - startedNs).coerceAtLeast(1L) / 1_000_000_000.0
        return processed / elapsedSeconds
    }

    private fun selectTrack(extractor: MediaExtractor, predicate: (String) -> Boolean): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (predicate(mime)) return index
        }
        return null
    }
}
