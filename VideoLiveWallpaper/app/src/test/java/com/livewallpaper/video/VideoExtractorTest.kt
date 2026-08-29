package com.livewallpaper.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoExtractorTest {

    @Test
    fun `YouTube-ID aus Watch-URL`() {
        assertEquals("abc123", VideoExtractor.parseYouTubeId("https://www.youtube.com/watch?v=abc123"))
        assertEquals("abc123", VideoExtractor.parseYouTubeId("https://youtube.com/watch?v=abc123"))
        assertEquals("abc123", VideoExtractor.parseYouTubeId("https://m.youtube.com/watch?v=abc123"))
        assertEquals("abc123", VideoExtractor.parseYouTubeId("https://music.youtube.com/watch?v=abc123"))
    }

    @Test
    fun `YouTube-ID aus Kurzlink`() {
        assertEquals("dQw4w9WgXcQ", VideoExtractor.parseYouTubeId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `YouTube-ID aus Shorts-URL`() {
        assertEquals("xyz789", VideoExtractor.parseYouTubeId("https://youtube.com/shorts/xyz789"))
    }

    @Test
    fun `YouTube-ID aus Embed-URL`() {
        assertEquals("abc", VideoExtractor.parseYouTubeId("https://youtube.com/embed/abc"))
        assertEquals("abc", VideoExtractor.parseYouTubeId("https://youtube-nocookie.com/embed/abc"))
    }

    @Test
    fun `YouTube-ID aus Live-URL`() {
        assertEquals("live1", VideoExtractor.parseYouTubeId("https://youtube.com/live/live1"))
    }

    @Test
    fun `ungueltige YouTube-URL ergibt null`() {
        assertNull(VideoExtractor.parseYouTubeId("https://beispiel.de/watch?v=abc"))
        assertNull(VideoExtractor.parseYouTubeId("kein-link"))
        assertNull(VideoExtractor.parseYouTubeId("https://youtube.com/"))
    }

    @Test
    fun `Instagram-Shortcode aus Post-URL`() {
        assertEquals("CxYzAbc", VideoExtractor.parseInstagramShortcode("https://www.instagram.com/p/CxYzAbc/"))
        assertEquals("CxYzAbc", VideoExtractor.parseInstagramShortcode("https://instagram.com/p/CxYzAbc"))
    }

    @Test
    fun `Instagram-Shortcode aus Reel-URL`() {
        assertEquals("DAbcXyz", VideoExtractor.parseInstagramShortcode("https://www.instagram.com/reel/DAbcXyz/"))
        assertEquals("DAbcXyz", VideoExtractor.parseInstagramShortcode("https://instagram.com/reels/DAbcXyz/"))
    }

    @Test
    fun `Instagram-Shortcode aus TV-URL`() {
        assertEquals("CtvTest", VideoExtractor.parseInstagramShortcode("https://www.instagram.com/tv/CtvTest/"))
    }

    @Test
    fun `ungueltige Instagram-URL ergibt null`() {
        assertNull(VideoExtractor.parseInstagramShortcode("https://instagram.com/username"))
        assertNull(VideoExtractor.parseInstagramShortcode("https://instagram.com/explore/"))
        assertNull(VideoExtractor.parseInstagramShortcode("kein-link"))
    }
}
