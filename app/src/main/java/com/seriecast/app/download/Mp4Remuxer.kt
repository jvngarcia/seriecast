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
 * Documented limits: AC-3/DTS/Opus audio cannot go into MP4 this way, and HEVC output depends on the
 * television. When the remux fails the original file is kept, never deleted.
 */
object Mp4Remuxer {

    data class Result(val file: File, val remuxed: Boolean, val note: String?)

    private const val TAG = "Mp4Remuxer"
    private const val BUFFER_SIZE = 4 * 1024 * 1024

    fun ensureMp4(source: File, baseName: String): Result {
        return when (ContainerSniffer.detect(source)) {
            Container.MP4 -> Result(normalizeExtension(source, baseName), false, null)
            Container.MPEG_TS, Container.MATROSKA -> remuxOrKeep(source, baseName)
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

    private fun remuxOrKeep(source: File, baseName: String): Result {
        val temp = remuxTarget(source, baseName)
        val desired = File(source.parentFile, "$baseName.mp4")
        if (temp.exists()) temp.delete()
        return try {
            remux(source, temp)
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
            keepOriginal(source, "No se pudo remuxear a MP4: ${t.message ?: t.javaClass.simpleName}")
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

    private fun remux(source: File, target: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.absolutePath)
            val videoTrack = selectTrack(extractor) { it.startsWith("video/") }
                ?: error("sin pista de video")
            // AAC is what every DLNA television decodes; fall back to any audio track.
            val audioTrack = selectTrack(extractor) { it == MediaFormat.MIMETYPE_AUDIO_AAC }
                ?: selectTrack(extractor) { it.startsWith("audio/") }

            val videoFormat = extractor.getTrackFormat(videoTrack)
            val videoIndex = muxer.addTrack(videoFormat)
            val audioIndex = audioTrack?.let { muxer.addTrack(extractor.getTrackFormat(it)) } ?: -1

            muxer.start()
            copyTrack(extractor, videoTrack, muxer, videoIndex)
            if (audioTrack != null && audioIndex >= 0) {
                copyTrack(extractor, audioTrack, muxer, audioIndex)
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, predicate: (String) -> Boolean): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (predicate(mime)) return index
        }
        return null
    }

    private fun copyTrack(extractor: MediaExtractor, trackIndex: Int, muxer: MediaMuxer, muxerIndex: Int) {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(muxerIndex, buffer, info)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(trackIndex)
    }
}
