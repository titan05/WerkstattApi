package at.werkstatt.screenmirror.core

import android.content.Context
import androidx.core.content.edit

/** Einstellungen der App - bewusst klein gehalten. */
object Prefs {

    private const val FILE = "car_screen_mirror"
    private const val KEY_PARKED_ONLY = "parked_only"
    private const val KEY_SCALE_MODE = "scale_mode"
    private const val KEY_SHARE_AUDIO = "share_audio"
    private const val KEY_MIGRATED_AUDIO_DEFAULT = "migrated_audio_default_v10"

    /** Wenn aktiv, wird die Spiegelung pausiert, sobald sich das Fahrzeug bewegt. */
    fun parkedOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARKED_ONLY, true)

    fun setParkedOnly(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_PARKED_ONLY, value) }
    }

    /** Skalierungsmodus fuer die Autoflaeche; Standard ist Fuellen. */
    fun scaleMode(context: Context): MirrorEngine.ScaleMode =
        runCatching {
            MirrorEngine.ScaleMode.valueOf(
                prefs(context).getString(KEY_SCALE_MODE, null) ?: MirrorEngine.ScaleMode.FILL.name
            )
        }.getOrDefault(MirrorEngine.ScaleMode.FILL)

    fun setScaleMode(context: Context, mode: MirrorEngine.ScaleMode) {
        prefs(context).edit { putString(KEY_SCALE_MODE, mode.name) }
    }

    /**
     * Ob die App den Medienton mitschneiden und ueber den Navi-Ansage-Kanal ans Auto senden soll.
     * Standard AUS: spielt das Auto den Ton schon selbst per Bluetooth ab, wuerde der Mitschnitt
     * ihn doppeln. Nur einschalten, wenn ueber Bluetooth gar kein Ton kommt.
     */
    fun shareAudio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHARE_AUDIO, false)

    fun setShareAudio(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_SHARE_AUDIO, value) }
    }

    /**
     * Einmalige Umstellung auf den neuen Standard: Ton-Mitschnitt aus. Wer zuvor die (damals
     * standardmaessig aktive) Option anhatte, bekam sonst nach dem Update weiter doppelten Ton,
     * weil das Auto den Bluetooth-Ton bereits selbst abspielt.
     */
    fun migrate(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_MIGRATED_AUDIO_DEFAULT, false)) {
            p.edit {
                putBoolean(KEY_SHARE_AUDIO, false)
                putBoolean(KEY_MIGRATED_AUDIO_DEFAULT, true)
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
