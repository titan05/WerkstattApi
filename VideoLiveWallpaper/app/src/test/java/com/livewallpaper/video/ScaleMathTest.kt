package com.livewallpaper.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleMathTest {

    private val phone = 1080f / 2340f // typisches Hochformat-Display

    @Test
    fun `fuellen laesst bei Querformat-Video keine Raender`() {
        val scale = ScaleMath.scale(16f / 9f, phone, ScaleMode.CROP)
        assertTrue("horizontal muss ueberstehen", scale[0] > 1f)
        assertEquals(1f, scale[1], 0.0001f)
        assertEquals(16f / 9f, onScreenAspect(scale, phone), 0.0001f)
    }

    @Test
    fun `fuellen streckt ein 9 zu 16 Video auf einem schmaleren Display seitlich`() {
        // 9:16 (0,5625) ist breiter als ein 9:19,5-Display (0,4615) - es wird seitlich beschnitten.
        val videoAspect = 9f / 16f
        val scale = ScaleMath.scale(videoAspect, phone, ScaleMode.CROP)
        assertTrue("horizontal muss ueberstehen", scale[0] > 1f)
        assertEquals(1f, scale[1], 0.0001f)
        assertEquals(videoAspect, onScreenAspect(scale, phone), 0.0001f)
    }

    @Test
    fun `fuellen laesst bei sehr schmalem Video keine Raender`() {
        // 9:21 (0,4286) ist schmaler als das Display - der Ueberstand liegt oben und unten.
        val videoAspect = 9f / 21f
        val scale = ScaleMath.scale(videoAspect, phone, ScaleMode.CROP)
        assertEquals(1f, scale[0], 0.0001f)
        assertTrue("vertikal muss ueberstehen", scale[1] > 1f)
        assertEquals(videoAspect, onScreenAspect(scale, phone), 0.0001f)
    }

    @Test
    fun `fuellen deckt fuer jedes Format die volle Flaeche ab`() {
        listOf(16f / 9f, 4f / 3f, 1f, 9f / 16f, 9f / 21f, 0.3f).forEach { videoAspect ->
            val scale = ScaleMath.scale(videoAspect, phone, ScaleMode.CROP)
            assertTrue("Rand in x bei $videoAspect", scale[0] >= 0.9999f)
            assertTrue("Rand in y bei $videoAspect", scale[1] >= 0.9999f)
            assertEquals(videoAspect, onScreenAspect(scale, phone), 0.0001f)
        }
    }

    @Test
    fun `einpassen bleibt immer innerhalb des Bildschirms`() {
        listOf(16f / 9f, 4f / 3f, 1f, 9f / 16f, 9f / 21f).forEach { videoAspect ->
            val scale = ScaleMath.scale(videoAspect, phone, ScaleMode.FIT)
            assertTrue("kein Ueberstand in x", scale[0] <= 1.0001f)
            assertTrue("kein Ueberstand in y", scale[1] <= 1.0001f)
            assertEquals(videoAspect, onScreenAspect(scale, phone), 0.0001f)
        }
    }

    @Test
    fun `strecken nutzt immer die volle Flaeche`() {
        val scale = ScaleMath.scale(16f / 9f, phone, ScaleMode.STRETCH)
        assertEquals(1f, scale[0], 0.0001f)
        assertEquals(1f, scale[1], 0.0001f)
    }

    @Test
    fun `unbekanntes Seitenverhaeltnis faellt auf Vollbild zurueck`() {
        val scale = ScaleMath.scale(0f, phone, ScaleMode.CROP)
        assertEquals(1f, scale[0], 0.0001f)
        assertEquals(1f, scale[1], 0.0001f)
    }

    @Test
    fun `parallax bleibt innerhalb des Ueberstands`() {
        val scale = ScaleMath.scale(16f / 9f, phone, ScaleMode.CROP)
        val overflow = scale[0] - 1f
        val left = ScaleMath.parallax(scale[0], 0f, true)
        val middle = ScaleMath.parallax(scale[0], 0.5f, true)
        val right = ScaleMath.parallax(scale[0], 1f, true)

        assertEquals(overflow, left, 0.0001f)
        assertEquals(0f, middle, 0.0001f)
        assertEquals(-overflow, right, 0.0001f)
    }

    @Test
    fun `parallax ist ohne Ueberstand oder abgeschaltet immer null`() {
        assertEquals(0f, ScaleMath.parallax(1f, 0f, true), 0.0001f)
        assertEquals(0f, ScaleMath.parallax(1.5f, 0f, false), 0.0001f)
    }

    @Test
    fun `parallax begrenzt Werte ausserhalb des Bereichs`() {
        val value = ScaleMath.parallax(1.5f, 5f, true)
        assertEquals(-0.5f, value, 0.0001f)
    }

    /** Seitenverhaeltnis, mit dem das Rechteck tatsaechlich auf dem Schirm landet. */
    private fun onScreenAspect(scale: FloatArray, surfaceAspect: Float): Float =
        scale[0] / scale[1] * surfaceAspect
}
