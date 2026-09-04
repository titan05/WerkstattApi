package at.werkstatt.screenmirror.core

import android.content.Context
import androidx.core.content.edit

/** Einstellungen der App - bewusst klein gehalten. */
object Prefs {

    private const val FILE = "car_screen_mirror"
    private const val KEY_PARKED_ONLY = "parked_only"

    /** Wenn aktiv, wird die Spiegelung pausiert, sobald sich das Fahrzeug bewegt. */
    fun parkedOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARKED_ONLY, true)

    fun setParkedOnly(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_PARKED_ONLY, value) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
