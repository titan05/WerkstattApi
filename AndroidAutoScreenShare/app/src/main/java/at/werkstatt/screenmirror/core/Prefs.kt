package at.werkstatt.screenmirror.core

import android.content.Context
import androidx.core.content.edit

/** Einstellungen der App - bewusst klein gehalten. */
object Prefs {

    private const val FILE = "car_screen_mirror"
    private const val KEY_PARKED_ONLY = "parked_only"
    private const val KEY_SCALE_MODE = "scale_mode"
    private const val KEY_SHARE_AUDIO = "share_audio"
    private const val KEY_AUDIO_MEDIA_CHANNEL = "audio_media_channel"

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

    /** Ob der Medienton mitgespiegelt werden soll (experimentell); Standard an. */
    fun shareAudio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHARE_AUDIO, true)

    fun setShareAudio(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_SHARE_AUDIO, value) }
    }

    /** true = Ton ueber normalen Medienkanal (Bluetooth), false = ueber Navi-Ansage-Kanal. Standard Medien. */
    fun audioMediaChannel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_MEDIA_CHANNEL, true)

    fun setAudioMediaChannel(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUDIO_MEDIA_CHANNEL, value) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
