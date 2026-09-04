package at.werkstatt.screenmirror.car

import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import at.werkstatt.screenmirror.core.Prefs
import kotlin.math.abs

/**
 * Pausiert die Spiegelung, sobald sich das Fahrzeug bewegt.
 *
 * Die Geschwindigkeit kommt aus der Car-Hardware-API. Steht sie nicht zur Verfuegung
 * (fehlende Berechtigung, zu alter Host, Fahrzeug liefert keinen Wert), wird die Sperre
 * still deaktiviert - sie darf die App nicht unbrauchbar machen.
 */
class SpeedGuard(
    private val carContext: CarContext,
    private val onDrivingChanged: (Boolean) -> Unit,
) {

    private var carInfo: CarInfo? = null
    private var listener: OnCarDataAvailableListener<Speed>? = null

    fun start() {
        if (!Prefs.parkedOnly(carContext)) {
            onDrivingChanged(false)
            return
        }
        if (ContextCompat.checkSelfPermission(carContext, PERMISSION_CAR_SPEED) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission()
            return
        }
        subscribe()
    }

    fun stop() {
        val current = listener ?: return
        try {
            carInfo?.removeSpeedListener(current)
        } catch (t: Throwable) {
            Log.w(TAG, "removeSpeedListener fehlgeschlagen", t)
        }
        listener = null
        carInfo = null
        onDrivingChanged(false)
    }

    private fun requestPermission() {
        try {
            carContext.requestPermissions(listOf(PERMISSION_CAR_SPEED)) { granted, _ ->
                if (granted.contains(PERMISSION_CAR_SPEED)) {
                    subscribe()
                } else {
                    Log.i(TAG, "Keine Freigabe fuer die Geschwindigkeit - Fahrsperre inaktiv")
                    onDrivingChanged(false)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Berechtigungsabfrage nicht moeglich", t)
            onDrivingChanged(false)
        }
    }

    private fun subscribe() {
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            val speedListener = OnCarDataAvailableListener<Speed> { speed -> onSpeed(speed) }
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
            carInfo = info
            listener = speedListener
        } catch (t: Throwable) {
            // Aeltere Hosts kennen die Hardware-API nicht (RequiresCarApi 3).
            Log.i(TAG, "Geschwindigkeit nicht verfuegbar - Fahrsperre inaktiv", t)
            onDrivingChanged(false)
        }
    }

    private fun onSpeed(speed: Speed) {
        // Der Schalter kann waehrend der Fahrt umgelegt werden, daher jedes Mal neu lesen.
        if (!Prefs.parkedOnly(carContext)) {
            onDrivingChanged(false)
            return
        }
        val value = speed.rawSpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
            ?: speed.displaySpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        if (value == null) {
            // Kein verwertbarer Messwert - lieber weiter spiegeln als dauerhaft blockieren.
            onDrivingChanged(false)
            return
        }
        onDrivingChanged(abs(value) > MOVING_THRESHOLD_MPS)
    }

    companion object {
        private const val TAG = "SpeedGuard"
        private const val PERMISSION_CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"

        /** Rund 2 km/h - deckt Sensorrauschen im Stand ab. */
        private const val MOVING_THRESHOLD_MPS = 0.6f
    }
}
