package com.seriecast.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sniffer decides what the user is allowed to download. Two failure modes matter:
 *
 *  - a real stream that never shows up because its URL carries no file extension, and
 *  - an asset (a script, a poster, a subtitle) offered as if it were a video.
 *
 * Every assertion here goes through the public `offer`/`candidates` surface, never the classifier.
 */
class StreamSnifferTest {

    private fun sniff(vararg urls: String): List<SniffedStream> {
        val sniffer = StreamSniffer()
        urls.forEach { sniffer.offer(it, cookie = null) }
        return sniffer.candidates.value
    }

    @Test
    fun `captures the extensions the app already supported`() {
        val streams = sniff(
            "https://cdn.example.com/series/ep1/master.m3u8?token=abc",
            "https://cdn.example.com/series/ep1/manifest.mpd",
            "https://cdn.example.com/series/ep1/episode.mp4",
        )

        assertEquals(setOf(StreamKind.HLS, StreamKind.DASH, StreamKind.PROGRESSIVE), streams.map { it.kind }.toSet())
        assertEquals(3, streams.size)
    }

    @Test
    fun `captures an hls url whose path has no extension`() {
        val streams = sniff("https://cdn.example.com/playlist?token=abc123")

        assertEquals(1, streams.size)
        assertEquals(StreamKind.HLS, streams.single().kind)
    }

    @Test
    fun `captures an hls url that only names hls in the path`() {
        val streams = sniff("https://cdn.example.com/hls/abc123/720")

        assertEquals(1, streams.size)
        assertEquals(StreamKind.HLS, streams.single().kind)
    }

    @Test
    fun `keeps ignoring assets that mention hls or playlist in their name`() {
        val streams = sniff(
            "https://cdn.example.com/assets/hls.min.js",
            "https://cdn.example.com/assets/playlist.css",
            "https://cdn.example.com/assets/hls-poster.jpg",
            "https://cdn.example.com/api/playlist.json",
            "https://cdn.example.com/segments/chunk-0001.ts",
        )

        assertTrue("expected no candidates, got ${streams.map { it.url }}", streams.isEmpty())
    }

    @Test
    fun `ignores blob and data urls`() {
        val streams = sniff(
            "blob:https://site.example/6f2a",
            "data:application/vnd.apple.mpegurl;base64,AAAA",
        )

        assertTrue(streams.isEmpty())
    }
}
