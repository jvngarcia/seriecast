package com.seriecast.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A remux interrupted halfway leaves a multi-gigabyte MP4 with no `moov` index. Listing it as an
 * episode is the failure mode this guards: the library offers it and the television refuses it.
 */
class MediaFilesTest {

    @Test
    fun `a half-written remux output is a temporary, never an episode`() {
        val name = "doctora x - S01E05 - 5.remux.mp4"
        assertTrue("el temporal del remux tiene que reconocerse", MediaFiles.isTemporary(name))
        assertTrue("un temporal no se lista como episodio", !MediaFiles.isPlayable(name))
    }

    @Test
    fun `a finished mp4 is still playable`() {
        assertTrue(MediaFiles.isPlayable("doctora x - S01E05 - 5.mp4"))
    }

    @Test
    fun `download temporaries are still temporaries`() {
        assertTrue(MediaFiles.isTemporary("episodio.mp4.part"))
        assertTrue(MediaFiles.isTemporary("episodio.ytdl"))
        assertTrue(MediaFiles.isTemporary("episodio.tmp"))
        assertTrue(MediaFiles.isTemporary("episodio.part-frag12"))
    }

    @Test
    fun `matroska stays playable so a failed remux is still watchable`() {
        assertTrue(MediaFiles.isPlayable("episodio.mkv"))
    }
}
