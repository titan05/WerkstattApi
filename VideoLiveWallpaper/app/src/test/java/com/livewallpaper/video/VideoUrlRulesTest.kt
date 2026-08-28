package com.livewallpaper.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUrlRulesTest {

    @Test
    fun `YouTube-Adressen werden als Portal erkannt`() {
        listOf(
            "https://www.youtube.com/watch?v=abc123",
            "https://youtu.be/abc123",
            "http://m.youtube.com/watch?v=abc123",
            "https://music.youtube.com/watch?v=abc123",
            "youtube.com/watch?v=abc123"
        ).forEach { url ->
            val result = VideoUrlRules.check(url)
            assertTrue("$url sollte als Portal erkannt werden", result is UrlCheck.Streaming)
            assertEquals("YouTube", (result as UrlCheck.Streaming).service)
        }
    }

    @Test
    fun `weitere Portale werden mit Namen gemeldet`() {
        assertEquals("Vimeo", service("https://vimeo.com/123456"))
        assertEquals("TikTok", service("https://www.tiktok.com/@name/video/1"))
        assertEquals("Instagram", service("https://instagram.com/reel/xyz"))
        assertEquals("X", service("https://x.com/name/status/1"))
    }

    @Test
    fun `direkte Videoadresse wird angenommen`() {
        val result = VideoUrlRules.check("https://beispiel.de/clips/sonne.mp4")
        assertTrue(result is UrlCheck.Ok)
        assertEquals("https://beispiel.de/clips/sonne.mp4", (result as UrlCheck.Ok).url)
    }

    @Test
    fun `fehlendes Schema wird zu https ergaenzt`() {
        val result = VideoUrlRules.check("beispiel.de/clips/sonne.mp4")
        assertEquals("https://beispiel.de/clips/sonne.mp4", (result as UrlCheck.Ok).url)
    }

    @Test
    fun `unverschluesselte Adressen werden abgelehnt`() {
        assertTrue(VideoUrlRules.check("http://beispiel.de/clip.mp4") is UrlCheck.Cleartext)
    }

    @Test
    fun `leere und unsinnige Eingaben werden abgefangen`() {
        assertTrue(VideoUrlRules.check("   ") is UrlCheck.Empty)
        assertTrue(VideoUrlRules.check("ftp://beispiel.de/clip.mp4") is UrlCheck.Invalid)
        assertTrue(VideoUrlRules.check("kein link") is UrlCheck.Invalid)
    }

    @Test
    fun `Dateiendung stammt aus der Adresse`() {
        assertEquals("webm", VideoUrlRules.extensionFor("https://a.de/b.webm", "video/mp4"))
        assertEquals("mp4", VideoUrlRules.extensionFor("https://a.de/b", "video/mp4"))
        assertEquals("mov", VideoUrlRules.extensionFor("https://a.de/b", "video/quicktime"))
        assertEquals("mkv", VideoUrlRules.extensionFor("https://a.de/b", "video/x-matroska"))
    }

    @Test
    fun `unbekannter Typ faellt auf mp4 zurueck`() {
        assertEquals("mp4", VideoUrlRules.extensionFor("https://a.de/b", null))
        assertEquals("mp4", VideoUrlRules.extensionFor("https://a.de/b?x=1", "application/octet-stream"))
    }

    @Test
    fun `Videodateien werden an der Endung erkannt`() {
        assertTrue(VideoUrlRules.looksLikeVideoFile("https://a.de/clip.mp4"))
        assertTrue(VideoUrlRules.looksLikeVideoFile("https://a.de/pfad/clip.MKV"))
        assertFalse(VideoUrlRules.looksLikeVideoFile("https://a.de/seite.html"))
        assertFalse(VideoUrlRules.looksLikeVideoFile("https://a.de/"))
    }

    @Test
    fun `Dateiname wird aus der Adresse gelesen`() {
        assertEquals("clip.mp4", VideoUrlRules.fileNameFrom("https://a.de/pfad/clip.mp4"))
        assertEquals(null, VideoUrlRules.fileNameFrom("https://a.de/"))
    }

    private fun service(url: String): String? =
        (VideoUrlRules.check(url) as? UrlCheck.Streaming)?.service
}
