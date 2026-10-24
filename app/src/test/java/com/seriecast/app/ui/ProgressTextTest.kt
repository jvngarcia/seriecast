package com.seriecast.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status line is what tells the user whether the job is alive and how long it is going to take,
 * so it is built by a pure function and asserted here instead of being eyeballed in the APK.
 */
class ProgressTextTest {

    // 1 GiB exactly, so the size assertions below read as "1.0 GB" through Storage.humanSize.
    private val sourceBytes = 1_073_741_824L

    @Test
    fun `remux line shows percent, size and the remaining time`() {
        val line = ProgressText.line(
            kind = ProgressText.REMUX,
            percent = 37f,
            downloadedBytes = 400_000_000,
            totalBytes = sourceBytes,
            speedBytesPerSecond = 1_000_000.0,
        )
        assertTrue(line, line.contains("Ajustando el contenedor a MP4…"))
        assertTrue(line, line.contains("37%"))
        assertTrue(line, line.contains("de 1.0 GB"))
        assertTrue("sin restante estimado el usuario no sabe si son minutos u horas", line.contains("quedan ~"))
    }

    @Test
    fun `download line adds the remaining time on top of the speed`() {
        val line = ProgressText.line(
            kind = ProgressText.DOWNLOAD,
            percent = 42f,
            downloadedBytes = 200_000_000,
            totalBytes = sourceBytes,
            speedBytesPerSecond = 2_097_152.0,
        )
        assertTrue(line, line.contains("Descargando 42%"))
        assertTrue(line, line.contains("(2.0 MB/s)"))
        assertTrue(line, line.contains("quedan ~"))
    }

    @Test
    fun `no speed means no estimate, never an invented one`() {
        val line = ProgressText.line(
            kind = ProgressText.DOWNLOAD,
            percent = 42f,
            downloadedBytes = 200_000_000,
            totalBytes = sourceBytes,
            speedBytesPerSecond = 0.0,
        )
        assertTrue(line, line.contains("42%"))
        assertTrue("sin velocidad no se estima", !line.contains("quedan"))
    }

    @Test
    fun `a download without a known total still reports progress`() {
        val line = ProgressText.line(
            kind = ProgressText.DOWNLOAD,
            percent = -1f,
            downloadedBytes = 300_000_000,
            totalBytes = -1,
            speedBytesPerSecond = 0.0,
        )
        assertTrue(line, line.contains("Descargando"))
        assertTrue("no hay porcentaje que mostrar sin total", !line.contains("%"))
        assertTrue("no hay restante que estimar sin total", !line.contains("quedan"))
    }

    @Test
    fun `remaining time reads as minutes`() {
        assertEquals("quedan ~2 min", ProgressText.etaLabel(remainingBytes = 90, speedBytesPerSecond = 1.0))
    }

    @Test
    fun `remaining time under a minute is said plainly`() {
        assertEquals("menos de 1 min", ProgressText.etaLabel(remainingBytes = 45, speedBytesPerSecond = 1.0))
    }

    @Test
    fun `remaining time over an hour keeps the hours`() {
        assertEquals("quedan ~1 h 2 min", ProgressText.etaLabel(remainingBytes = 3_720, speedBytesPerSecond = 1.0))
    }

    @Test
    fun `no estimate without a speed`() {
        assertNull(ProgressText.etaLabel(remainingBytes = 100, speedBytesPerSecond = 0.0))
    }
}
