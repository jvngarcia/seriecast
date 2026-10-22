package com.seriecast.app.download

import java.io.File

enum class Container { MP4, MPEG_TS, MATROSKA, UNKNOWN }

/**
 * Detects the real container from magic bytes instead of trusting the extension.
 *
 * This matters because when ffmpeg is absent yt-dlp uses its native HLS downloader and can leave
 * MPEG-TS bytes inside a file named `.mp4`. Feeding that to a television produces "formato no
 * soportado" with no other clue.
 */
object ContainerSniffer {

    fun detect(file: File): Container {
        val head = ByteArray(400)
        val read = runCatching {
            file.inputStream().use { it.read(head) }
        }.getOrDefault(-1)
        if (read < 12) return Container.UNKNOWN

        // MP4/MOV: 'ftyp' box at offset 4.
        if (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            return Container.MP4
        }

        // MPEG-TS: 0x47 sync byte repeating every 188 bytes.
        if (read > 200 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) {
            return Container.MPEG_TS
        }

        // Matroska/WebM EBML header.
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) {
            return Container.MATROSKA
        }

        return Container.UNKNOWN
    }
}
