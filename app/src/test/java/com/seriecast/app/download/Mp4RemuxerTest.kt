package com.seriecast.app.download

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression test for a data-loss bug found on a device: a downloaded MPEG-TS stream arrives in a
 * file already named `.mp4`, so `"$baseName.mp4"` resolved to the source. MediaMuxer truncated the
 * file being read by MediaExtractor, the remux threw, and the error path deleted the only copy of
 * the episode.
 */
class Mp4RemuxerTest {

    private fun tempDir(): File = Files.createTempDirectory("seriecast-remux").toFile()

    @Test
    fun `remux target is never the file being read, even for a misleading mp4 name`() {
        val dir = tempDir()
        try {
            // Exactly what yt-dlp's native HLS downloader produces: TS bytes named .mp4.
            val source = File(dir, "x36xhzz.mp4")
            source.writeBytes(ByteArray(1024))
            val target = Mp4Remuxer.remuxTarget(source, "x36xhzz")
            assertNotEquals(
                "muxear sobre el archivo que se lee lo trunca y destruye la descarga",
                source.canonicalPath,
                target.canonicalPath,
            )
            assertTrue("el destino tiene que seguir siendo MP4", target.name.endsWith(".mp4"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `remux target differs from an mkv source too`() {
        val dir = tempDir()
        try {
            val source = File(dir, "episodio.mkv")
            source.writeBytes(ByteArray(64))
            val target = Mp4Remuxer.remuxTarget(source, "episodio")
            assertNotEquals(source.canonicalPath, target.canonicalPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `mp4 that is already mp4 is not remuxed`() {
        val dir = tempDir()
        try {
            val source = File(dir, "Serie - S01E02 - Piloto.mp4")
            val bytes = ByteArray(256)
            "ftypisom".toByteArray().copyInto(bytes, 4)
            source.writeBytes(bytes)
            val result = Mp4Remuxer.ensureMp4(source, "Serie - S01E02 - Piloto")
            assertTrue("un MP4 valido no se toca", !result.remuxed)
            assertTrue("el archivo tiene que seguir existiendo", result.file.exists())
            assertTrue(result.note == null)
        } finally {
            dir.deleteRecursively()
        }
    }
}
