package com.seriecast.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-pass remux reads both tracks in file order; every judgement about what to write lives
 * here so it can be tested without MediaExtractor/MediaMuxer (the repo has no Robolectric).
 */
class SampleGateTest {

    // Source track 0 is video and goes to muxer track 0; source track 1 is audio, muxer track 1.
    private fun gate() = SampleGate(videoTrack = 0, muxerTracks = mapOf(0 to 0, 1 to 1))

    @Test
    fun `video samples before the first keyframe are dropped`() {
        val gate = gate()
        assertNull("un frame no clave antes del primer keyframe no se puede escribir", gate.inspect(0, 0, keyFrame = false))
        assertNull(gate.inspect(0, 40_000, keyFrame = false))
    }

    @Test
    fun `the first keyframe starts the timeline at zero`() {
        val gate = gate()
        val decision = gate.inspect(0, 1_400_000, keyFrame = true)
        assertEquals("el MP4 arranca en 0, no en el PTS absoluto del transporte", 0L, decision!!.presentationTimeUs)
        assertEquals(0, decision.muxerIndex)
    }

    @Test
    fun `audio before the first video keyframe is dropped`() {
        val gate = gate()
        assertNull("el audio anterior al primer keyframe se recorta con él", gate.inspect(1, 1_300_000, keyFrame = true))
    }

    @Test
    fun `audio after the keyframe keeps its offset on the shared timeline`() {
        val gate = gate()
        gate.inspect(0, 1_400_000, keyFrame = true)
        val audio = gate.inspect(1, 1_440_000, keyFrame = true)
        assertEquals("el audio conserva su distancia con el video", 40_000L, audio!!.presentationTimeUs)
        assertEquals(1, audio.muxerIndex)
    }

    @Test
    fun `tracks the muxer does not carry are ignored`() {
        val gate = SampleGate(videoTrack = 0, muxerTracks = mapOf(0 to 0))
        assertNull("una pista sin destino no se escribe", gate.inspect(2, 5_000, keyFrame = true))
    }

    @Test
    fun `a repeated timestamp on the same track is dropped instead of failing the muxer`() {
        val gate = gate()
        gate.inspect(0, 0, keyFrame = true)
        gate.inspect(0, 40_000, keyFrame = false)
        assertNull("un PTS repetido en la misma pista es lo que hace fallar a MediaMuxer", gate.inspect(0, 40_000, keyFrame = false))
    }

    @Test
    fun `a timestamp restart is rebased instead of truncating the rest of the file`() {
        val gate = gate()
        gate.inspect(0, 0, keyFrame = true)
        gate.inspect(0, 40_000, keyFrame = false)
        // A TS discontinuity sends the PTS back near zero; dropping it would cut the episode.
        val rebased = gate.inspect(0, 100, keyFrame = true)
        assertTrue("después de un salto hacia atrás la pista sigue avanzando", rebased!!.presentationTimeUs > 40_000L)
    }

    @Test
    fun `the keyframe flag survives the gate`() {
        val gate = gate()
        assertTrue(gate.inspect(0, 0, keyFrame = true)!!.keyFrame)
    }
}
