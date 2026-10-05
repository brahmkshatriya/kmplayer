package dev.brahmkshatriya.kmplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KMMediaSourceTest {
    @Test
    fun detectsHlsByExtension() {
        assertTrue(KMMediaSource("https://example.com/live/master.m3u8?token=1").isHls)
        assertFalse(KMMediaSource("https://example.com/video.mp4").isHls)
    }

    @Test
    fun detectsHlsByMimeType() {
        assertTrue(KMMediaSource("https://example.com/live", "application/vnd.apple.mpegurl").isHls)
    }
}
