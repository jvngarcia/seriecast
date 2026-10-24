package com.seriecast.app.download

/**
 * Every judgement the single-pass remux makes about a sample: which muxer track it belongs to, what
 * timestamp it gets and whether it is written at all.
 *
 * Kept pure — no `MediaExtractor`, no `MediaMuxer` — because the module's tests are plain JUnit.
 * Two rules matter in practice:
 *
 *  - An MP4 starts at its first video keyframe. Samples before it are dropped, or the first frames
 *    of the episode are undecodable.
 *  - Timestamps must strictly increase per track or `MediaMuxer.writeSampleData` rejects the sample.
 *    Repeated timestamps are dropped; a backwards jump (an MPEG-TS discontinuity) is rebased so the
 *    rest of the episode is not lost.
 */
internal class SampleGate(
    private val videoTrack: Int,
    private val muxerTracks: Map<Int, Int>,
) {
    data class Decision(val muxerIndex: Int, val presentationTimeUs: Long, val keyFrame: Boolean)

    private var originUs: Long? = null
    private var videoStarted = false
    private val lastWrittenUs = HashMap<Int, Long>()
    private val shiftUs = HashMap<Int, Long>()

    fun inspect(sourceTrack: Int, presentationTimeUs: Long, keyFrame: Boolean): Decision? {
        val muxerIndex = muxerTracks[sourceTrack] ?: return null
        if (sourceTrack == videoTrack && !videoStarted) {
            if (!keyFrame) return null
            videoStarted = true
            originUs = presentationTimeUs
        }
        // Audio that precedes the first keyframe is trimmed with it; the MP4 cannot start there.
        val origin = originUs ?: return null
        var pts = presentationTimeUs - origin + (shiftUs[muxerIndex] ?: 0L)
        val last = lastWrittenUs[muxerIndex]
        if (last != null && pts <= last) {
            if (pts == last) return null
            shiftUs[muxerIndex] = (shiftUs[muxerIndex] ?: 0L) + (last + FRAME_STEP_US - pts)
            pts = last + FRAME_STEP_US
        }
        lastWrittenUs[muxerIndex] = pts
        return Decision(muxerIndex, pts, keyFrame)
    }

    private companion object {
        /** One millisecond: enough to keep a rebased track strictly increasing. */
        const val FRAME_STEP_US = 1_000L
    }
}
