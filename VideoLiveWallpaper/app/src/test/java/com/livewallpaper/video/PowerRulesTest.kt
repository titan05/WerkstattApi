package com.livewallpaper.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerRulesTest {

    private val nanosPerSecond = 1_000_000_000L

    @Test
    fun `ohne Grenze wird jedes Bild gezeichnet`() {
        assertEquals(0L, PowerRules.minFrameIntervalNanos(0))
        assertEquals(0L, PowerRules.minFrameIntervalNanos(-5))
    }

    @Test
    fun `ein 30fps Video laeuft bei Grenze 30 ohne Aussetzer`() {
        val limit = PowerRules.minFrameIntervalNanos(30)
        val frameGap = nanosPerSecond / 30
        assertTrue("kein Bild darf verworfen werden", frameGap >= limit)
    }

    @Test
    fun `ein 60fps Video wird bei Grenze 30 halbiert`() {
        val limit = PowerRules.minFrameIntervalNanos(30)
        val frameGap = nanosPerSecond / 60
        // Erstes Bild verworfen, das naechste liegt wieder ueber der Grenze.
        assertTrue("Bild muss verworfen werden", frameGap < limit)
        assertTrue("uebernaechstes Bild muss durch", 2 * frameGap >= limit)
    }

    @Test
    fun `ein 24fps Video bleibt bei Grenze 30 unberuehrt`() {
        val limit = PowerRules.minFrameIntervalNanos(30)
        assertTrue(nanosPerSecond / 24 >= limit)
    }

    @Test
    fun `Energiesparmodus erzwingt das Standbild`() {
        assertTrue(
            PowerRules.stillImageOnly(
                powerSaveOptionEnabled = true,
                powerSaveActive = true,
                thresholdPercent = 0,
                batteryPercent = 90,
                charging = false
            )
        )
    }

    @Test
    fun `abgeschaltete Option ignoriert den Energiesparmodus`() {
        assertFalse(
            PowerRules.stillImageOnly(
                powerSaveOptionEnabled = false,
                powerSaveActive = true,
                thresholdPercent = 0,
                batteryPercent = 90,
                charging = false
            )
        )
    }

    @Test
    fun `unter der Schwelle gibt es nur ein Standbild`() {
        assertTrue(still(batteryPercent = 15, threshold = 15))
        assertTrue(still(batteryPercent = 8, threshold = 15))
        assertFalse(still(batteryPercent = 16, threshold = 15))
    }

    @Test
    fun `am Ladekabel spielt der Akkustand keine Rolle`() {
        assertFalse(still(batteryPercent = 5, threshold = 15, charging = true))
    }

    @Test
    fun `ohne Schwelle oder ohne Akkustand bleibt es beim Video`() {
        assertFalse(still(batteryPercent = 5, threshold = 0))
        assertFalse(still(batteryPercent = -1, threshold = 15))
    }

    private fun still(
        batteryPercent: Int,
        threshold: Int,
        charging: Boolean = false
    ): Boolean = PowerRules.stillImageOnly(
        powerSaveOptionEnabled = true,
        powerSaveActive = false,
        thresholdPercent = threshold,
        batteryPercent = batteryPercent,
        charging = charging
    )
}
