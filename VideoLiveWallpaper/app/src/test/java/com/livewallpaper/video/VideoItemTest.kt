package com.livewallpaper.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoItemTest {

    @Test
    fun `Video ueberlebt das Speichern und Laden`() {
        val item = VideoItem("abc-123", "Sonnenuntergang", "abc-123.mp4", 42_000L, 16f / 9f)
        val restored = VideoItem.fromJson(JSONObject(item.toJson().toString()))
        assertEquals(item, restored)
    }

    @Test
    fun `fehlende Felder ergeben unbekanntes Seitenverhaeltnis`() {
        val restored = VideoItem.fromJson(JSONObject("""{"id":"x","file":"x.mp4"}"""))
        assertEquals("x", restored.id)
        assertEquals(0f, restored.aspect, 0.0001f)
        assertEquals(0L, restored.durationMs)
    }

    @Test
    fun `Skalierungsmodus faellt auf Fuellen zurueck`() {
        assertEquals(ScaleMode.FIT, ScaleMode.fromName("FIT"))
        assertEquals(ScaleMode.CROP, ScaleMode.fromName("QUATSCH"))
        assertEquals(ScaleMode.CROP, ScaleMode.fromName(null))
    }
}
