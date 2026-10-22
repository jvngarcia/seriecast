package com.seriecast.app.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Value formatting and URL encoding that a television depends on. */
class MediaEntryTest {

    @Test
    fun `duration uses the didl hh mm ss mmm form`() {
        assertEquals("00:42:00.000", MediaEntry.formatDuration(2_520_000L))
        assertEquals("00:00:05.500", MediaEntry.formatDuration(5_500L))
        assertEquals("01:01:01.001", MediaEntry.formatDuration(3_661_001L))
        assertEquals("00:00:00.000", MediaEntry.formatDuration(0L))
    }

    @Test
    fun `media paths survive a url encode round trip`() {
        val name = "Serie - S01E02 - Piloto & cañón.mp4"
        val encoded = MediaEntry.encodePath(name)
        assertFalse("un espacio crudo rompe la URL del televisor", encoded.contains(' '))
        assertFalse("un & crudo rompe el DIDL", encoded.contains('&'))
        assertEquals(name, MediaEntry.decodePath(encoded))
    }

    @Test
    fun `mime type comes from the extension, case insensitively`() {
        assertEquals("video/mp4", MediaEntry.mimeTypeOf("a.mp4"))
        assertEquals("video/mp4", MediaEntry.mimeTypeOf("a.M4V"))
        assertEquals("video/x-matroska", MediaEntry.mimeTypeOf("a.MKV"))
        assertEquals("video/mp2t", MediaEntry.mimeTypeOf("a.ts"))
        assertEquals("video/webm", MediaEntry.mimeTypeOf("a.webm"))
    }

    @Test
    fun `object id is stable for the same file name`() {
        val a = MediaEntry.stableId(java.io.File("/tmp/x/Serie - S01E02.mp4"))
        val b = MediaEntry.stableId(java.io.File("/otra/ruta/Serie - S01E02.mp4"))
        val c = MediaEntry.stableId(java.io.File("/tmp/x/Serie - S01E03.mp4"))
        assertEquals("el id no depende de la ruta", a, b)
        assertFalse("distinto episodio, distinto id", a == c)
    }
}
