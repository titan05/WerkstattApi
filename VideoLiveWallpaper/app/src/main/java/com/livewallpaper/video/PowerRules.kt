package com.livewallpaper.video

/**
 * Entscheidungen rund um den Stromverbrauch - bewusst frei von
 * Android-Abhaengigkeiten, damit sie sich mit Unit-Tests pruefen lassen.
 */
object PowerRules {

    /** Kleine Toleranz, damit ein Video mit genau der Grenzbildrate nicht stockt. */
    private const val TOLERANCE_NANOS = 3_000_000L

    /**
     * Mindestabstand zwischen zwei gezeichneten Bildern in Nanosekunden.
     * 0 bedeutet: jedes gelieferte Bild wird gezeichnet.
     *
     * Die Toleranz sorgt dafuer, dass ein 30-fps-Video bei Grenze 30 nicht
     * durch minimale Schwankungen jedes zweite Bild verliert.
     */
    fun minFrameIntervalNanos(maxFps: Int): Long {
        if (maxFps <= 0) return 0L
        val interval = 1_000_000_000L / maxFps
        return (interval - TOLERANCE_NANOS).coerceAtLeast(0L)
    }

    /**
     * Ob statt der Wiedergabe nur ein Standbild gezeigt werden soll.
     *
     * @param batteryPercent Akkustand 0..100, negativ wenn unbekannt.
     */
    fun stillImageOnly(
        powerSaveOptionEnabled: Boolean,
        powerSaveActive: Boolean,
        thresholdPercent: Int,
        batteryPercent: Int,
        charging: Boolean
    ): Boolean {
        if (powerSaveOptionEnabled && powerSaveActive) return true
        // Am Ladekabel ist der Akkustand egal.
        if (charging || thresholdPercent <= 0 || batteryPercent < 0) return false
        return batteryPercent <= thresholdPercent
    }
}
