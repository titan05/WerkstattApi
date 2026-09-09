package at.werkstatt.screenmirror.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.werkstatt.screenmirror.R
import at.werkstatt.screenmirror.core.MirrorEngine
import kotlinx.coroutines.launch

/**
 * Der Bildschirm im Auto.
 *
 * Es wird ein [NavigationTemplate] verwendet, weil nur Navigations-Apps vom Host eine
 * eigene Surface bekommen. Auf diese Surface rendert die [MirrorEngine] das Handybild;
 * das Template selbst steuert nur die Actionleiste darueber.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarSurfaceRenderer()
    private val speedGuard = SpeedGuard(carContext) { moving -> MirrorEngine.setDrivingPaused(moving) }

    init {
        lifecycle.addObserver(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Statuswechsel (Freigabe gestartet/beendet, Fahrsperre) in die Actionleiste spiegeln.
                MirrorEngine.state.collect { invalidate() }
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        MirrorEngine.attach(carContext.applicationContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }

    override fun onStart(owner: LifecycleOwner) {
        speedGuard.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        speedGuard.stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        MirrorEngine.onCarSurfaceDestroyed()
    }

    override fun onGetTemplate(): Template {
        val status = when (MirrorEngine.state.value.phase) {
            MirrorEngine.Phase.MIRRORING -> R.string.car_status_mirroring
            MirrorEngine.Phase.PAUSED_WHILE_DRIVING -> R.string.car_status_paused
            else -> R.string.car_status_waiting
        }

        val scaleMode = MirrorEngine.state.value.scaleMode
        val scaleTitle = when (scaleMode) {
            MirrorEngine.ScaleMode.FILL -> R.string.car_mode_fill
            MirrorEngine.ScaleMode.FIT -> R.string.car_mode_fit
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(scaleTitle))
                    .setOnClickListener {
                        MirrorEngine.toggleScaleMode()
                        val toast = when (MirrorEngine.state.value.scaleMode) {
                            MirrorEngine.ScaleMode.FILL -> R.string.car_mode_fill_toast
                            MirrorEngine.ScaleMode.FIT -> R.string.car_mode_fit_toast
                        }
                        CarToast.makeText(carContext, toast, CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(status))
                    .setOnClickListener {
                        CarToast.makeText(carContext, R.string.car_toast_hint, CarToast.LENGTH_LONG).show()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}
