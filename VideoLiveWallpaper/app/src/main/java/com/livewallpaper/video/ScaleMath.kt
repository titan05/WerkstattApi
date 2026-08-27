package com.livewallpaper.video

/**
 * Geometrie des Videorechtecks - bewusst frei von Android-Abhaengigkeiten,
 * damit sie sich mit normalen Unit-Tests pruefen laesst.
 *
 * Das Rechteck deckt bei Skalierung 1/1 exakt den Bildschirm ab. Werte > 1
 * ragen ueber den Rand hinaus (Beschnitt), Werte < 1 lassen Raender frei.
 */
object ScaleMath {

    /** Liefert [scaleX, scaleY] fuer das Video-Rechteck. */
    fun scale(videoAspect: Float, surfaceAspect: Float, mode: ScaleMode): FloatArray {
        if (videoAspect <= 0f || surfaceAspect <= 0f || mode == ScaleMode.STRETCH) {
            return floatArrayOf(1f, 1f)
        }
        val ratio = videoAspect / surfaceAspect
        return when (mode) {
            // Fuellen: die kleinere Achse wird ueberdehnt, bis nichts frei bleibt.
            ScaleMode.CROP -> if (ratio > 1f) floatArrayOf(ratio, 1f) else floatArrayOf(1f, 1f / ratio)
            // Einpassen: die groessere Achse wird verkleinert, bis alles sichtbar ist.
            ScaleMode.FIT -> if (ratio > 1f) floatArrayOf(1f, 1f / ratio) else floatArrayOf(ratio, 1f)
            ScaleMode.STRETCH -> floatArrayOf(1f, 1f)
        }
    }

    /**
     * Horizontaler Versatz beim Wischen zwischen Startbildschirmen.
     * [xOffset] 0 = ganz links, 1 = ganz rechts.
     */
    fun parallax(scaleX: Float, xOffset: Float, enabled: Boolean): Float {
        if (!enabled || scaleX <= 1f) return 0f
        val clamped = xOffset.coerceIn(0f, 1f)
        return (scaleX - 1f) * (1f - 2f * clamped)
    }
}
