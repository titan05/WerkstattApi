package at.werkstatt.screenmirror.car

import android.graphics.Rect
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import at.werkstatt.screenmirror.core.MirrorEngine

/**
 * Duenne Bruecke zwischen den Surface-Callbacks des Hosts und der [MirrorEngine].
 * Der Host ruft diese Methoden auf dem Main-Thread auf.
 */
class CarSurfaceRenderer : SurfaceCallback {

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        MirrorEngine.onCarSurfaceAvailable(
            surfaceContainer.surface,
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
        )
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        MirrorEngine.onVisibleAreaChanged(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        // Wird nicht gesondert behandelt - die sichtbare Flaeche genuegt uns.
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        MirrorEngine.onCarSurfaceDestroyed()
    }
}
