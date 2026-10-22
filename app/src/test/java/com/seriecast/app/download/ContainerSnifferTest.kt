package com.seriecast.app.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Container detection is the guard between "yt-dlp left MPEG-TS bytes in a file named .mp4" and a
 * television showing "formato no soportado". It must trust magic bytes only, never the extension.
 */
class ContainerSnifferTest {

    private fun temp(bytes: ByteArray, extension: String = ".bin"): File {
        val file = Files.createTempFile("sniff-", extension).toFile()
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `detects mp4 from the ftyp box at offset 4`() {
        val bytes = ByteArray(64)
        "ftypisom".toByteArray().copyInto(bytes, 4)
        assertEquals(Container.MP4, ContainerSniffer.detect(temp(bytes)))
    }

    @Test
    fun `detects mpeg-ts from the sync byte pattern`() {
        val bytes = ByteArray(400)
        bytes[0] = 0x47
        bytes[188] = 0x47
        assertEquals(Container.MPEG_TS, ContainerSniffer.detect(temp(bytes)))
    }

    @Test
    fun `detects matroska from the ebml header`() {
        val bytes = ByteArray(64)
        bytes[0] = 0x1A
        bytes[1] = 0x45
        bytes[2] = 0xDF.toByte()
        bytes[3] = 0xA3.toByte()
        assertEquals(Container.MATROSKA, ContainerSniffer.detect(temp(bytes)))
    }

    @Test
    fun `a single 0x47 byte is not enough to claim mpeg-ts`() {
        val bytes = ByteArray(400)
        bytes[0] = 0x47 // no sync byte at 188
        assertEquals(Container.UNKNOWN, ContainerSniffer.detect(temp(bytes)))
    }

    @Test
    fun `reports unknown for short or unrecognised files`() {
        assertEquals(Container.UNKNOWN, ContainerSniffer.detect(temp(ByteArray(4))))
        assertEquals(Container.UNKNOWN, ContainerSniffer.detect(temp(ByteArray(400) { 0x7F })))
    }

    @Test
    fun `detects ts bytes even when the file is named mp4`() {
        val bytes = ByteArray(400)
        bytes[0] = 0x47
        bytes[188] = 0x47
        // The extension lies on purpose: this is what yt-dlp's native HLS downloader produces.
        assertEquals(Container.MPEG_TS, ContainerSniffer.detect(temp(bytes, ".mp4")))
    }
}
