package at.werkstatt.screenmirror.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import at.werkstatt.screenmirror.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Haelt die beiden Enden der Spiegelung zusammen:
 *
 *  * die [MediaProjection] vom Handy (aus dem [at.werkstatt.screenmirror.ProjectionService])
 *  * die Surface vom Autodisplay (aus der Car App Session)
 *
 * Sobald die Auto-Surface da ist, uebernimmt ein [CarSurfaceGlRenderer] deren Besitz und haelt
 * ihn fuer die gesamte Lebensdauer. Der Renderer zeichnet den Handybildschirm
 * seitenverhaeltnis-korrekt (FILL/FIT) bzw. im Warte-/Pausenzustand ein Schwarzbild. Nur wenn der
 * GL-Renderer nicht startet, fallen wir auf die alte, formatfuellend-verzerrende Direktspiegelung
 * plus Canvas-Platzhalter zurueck.
 *
 * Alle Zustandsaenderungen laufen ueber den Main-Thread.
 */
object MirrorEngine {

    private const val TAG = "MirrorEngine"
    private const val VIRTUAL_DISPLAY_NAME = "CarScreenMirror"
    private const val DEFAULT_DPI = 160

    enum class Phase {
        IDLE,
        WAITING_FOR_CAR,
        WAITING_FOR_PHONE,
        MIRRORING,
        PAUSED_WHILE_DRIVING,
    }

    /** Wie der Handyinhalt auf die (meist breitere) Autoflaeche gelegt wird. */
    enum class ScaleMode {
        /** Fuellt die Flaeche aus; ueberstehende Raender werden abgeschnitten. */
        FILL,

        /** Zeigt den ganzen Handybildschirm; laesst ggf. Raender frei. */
        FIT,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val carSurfaceReady: Boolean = false,
        val projectionActive: Boolean = false,
        val scaleMode: ScaleMode = ScaleMode.FILL,
    )

    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Wird gesetzt, wenn die Systemfreigabe von aussen beendet wurde (z.B. Stop im Systemdialog). */
    @Volatile
    var onProjectionEnded: (() -> Unit)? = null

    private var appContext: Context? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = DEFAULT_DPI
    private var visibleArea: Rect? = null

    // GL-Compositor besitzt die Auto-Surface fuer deren gesamte Lebensdauer (wenn verfuegbar).
    private var glRenderer: CarSurfaceGlRenderer? = null
    private var glActive = false
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var sourceDpi = DEFAULT_DPI

    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayWidth = 0
    private var virtualDisplayHeight = 0

    private var drivingPaused = false

    @Volatile
    private var scaleMode = ScaleMode.FILL

    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Prefs.migrate(context)
            scaleMode = Prefs.scaleMode(context)
        }
    }

    /** Schaltet zwischen Fuellen und Einpassen um (Aktion in der Auto-Actionleiste). */
    fun toggleScaleMode() = onMain {
        scaleMode = if (scaleMode == ScaleMode.FILL) ScaleMode.FIT else ScaleMode.FILL
        appContext?.let { Prefs.setScaleMode(it, scaleMode) }
        glRenderer?.setScaleMode(scaleMode)
        Log.i(TAG, "Skalierungsmodus: $scaleMode")
        publish()
    }

    // ---------------------------------------------------------------- Handy-Seite

    fun onProjectionStarted(mediaProjection: MediaProjection) = onMain {
        releaseProjection()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onMain {
                    Log.i(TAG, "MediaProjection wurde vom System beendet")
                    releaseProjection()
                    sync()
                    onProjectionEnded?.invoke()
                }
            }
        }
        // Ab Android 14 muss der Callback vor createVirtualDisplay registriert sein.
        mediaProjection.registerCallback(callback, main)
        projection = mediaProjection
        projectionCallback = callback
        Log.i(TAG, "MediaProjection aktiv")
        sync()
    }

    fun onProjectionStopped() = onMain {
        releaseProjection()
        sync()
    }

    // ------------------------------------------------------------------ Auto-Seite

    fun onCarSurfaceAvailable(newSurface: Surface?, width: Int, height: Int, dpi: Int) = onMain {
        // Alten Zustand fuer diese (evtl. neue) Surface komplett abbauen.
        releaseVirtualDisplayOnly()
        releaseGlRenderer()

        surface = newSurface
        surfaceWidth = width
        surfaceHeight = height
        surfaceDpi = if (dpi > 0) dpi else DEFAULT_DPI
        Log.i(TAG, "Car-Surface verfuegbar: ${width}x$height @ ${surfaceDpi}dpi")

        // GL-Renderer sofort erzeugen, damit die Surface NIE per lockCanvas belegt wird
        // (sonst schlaegt eglCreateWindowSurface fehl und die Spiegelung bliebe schwarz).
        if (newSurface != null && newSurface.isValid && width > 0 && height > 0) {
            val (pw, ph, pdpi) = phoneDisplaySize()
            sourceWidth = pw
            sourceHeight = ph
            sourceDpi = pdpi
            val renderer = CarSurfaceGlRenderer(newSurface, width, height, pw, ph, scaleMode)
            glRenderer = if (renderer.start()) {
                renderer
            } else {
                renderer.release()
                null
            }
            glActive = glRenderer != null
            Log.i(TAG, "GL-Renderer aktiv=$glActive (Quelle ${pw}x$ph)")
        }
        sync()
    }

    fun onCarSurfaceDestroyed() = onMain {
        releaseVirtualDisplayOnly()
        releaseGlRenderer()
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
        visibleArea = null
        sync()
    }

    fun onVisibleAreaChanged(area: Rect) = onMain {
        visibleArea = Rect(area)
        // Nur im Fallback (ohne GL) zeichnen wir den Platzhalter per Canvas.
        if (!glActive && virtualDisplay == null) drawPlaceholder()
    }

    /** Wird von der Geschwindigkeitssperre aufgerufen. */
    fun setDrivingPaused(paused: Boolean) = onMain {
        if (drivingPaused == paused) return@onMain
        drivingPaused = paused
        Log.i(TAG, "Fahrsperre: $paused")
        sync()
    }

    // -------------------------------------------------------------------- Interna

    private fun sync() {
        val currentSurface = surface
        val currentProjection = projection
        val canMirror = currentSurface != null &&
            currentSurface.isValid &&
            currentProjection != null &&
            !drivingPaused &&
            surfaceWidth > 0 &&
            surfaceHeight > 0

        if (canMirror) {
            ensureVirtualDisplay(currentProjection!!, currentSurface!!)
        } else {
            releaseVirtualDisplayOnly()
            if (glActive) {
                glRenderer?.setIdleOverlay(buildIdleOverlay())
                glRenderer?.showBlack()
            } else {
                drawPlaceholder()
            }
        }
        publish()
    }

    private fun ensureVirtualDisplay(mediaProjection: MediaProjection, target: Surface) {
        if (virtualDisplay != null && virtualDisplayWidth == surfaceWidth && virtualDisplayHeight == surfaceHeight) {
            if (glActive) glRenderer?.showMirror()
            return
        }
        releaseVirtualDisplayOnly()

        // Bei aktivem GL rendert das VirtualDisplay in die Zwischen-Surface (Handygroesse, 1:1),
        // sonst direkt auf die Autoflaeche (formatfuellend/verzerrt - Fallback).
        val vdSurface: Surface
        val vdWidth: Int
        val vdHeight: Int
        val vdDensity: Int
        if (glActive) {
            val input = glRenderer?.inputSurface
            if (input == null) {
                Log.w(TAG, "GL-InputSurface fehlt trotz aktivem Renderer")
                return
            }
            vdSurface = input
            vdWidth = sourceWidth
            vdHeight = sourceHeight
            vdDensity = sourceDpi
        } else {
            vdSurface = target
            vdWidth = surfaceWidth
            vdHeight = surfaceHeight
            vdDensity = surfaceDpi
        }

        val display = try {
            mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                vdWidth,
                vdHeight,
                vdDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                vdSurface,
                null,
                main,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay fehlgeschlagen", t)
            null
        } ?: return

        virtualDisplay = display
        virtualDisplayWidth = surfaceWidth
        virtualDisplayHeight = surfaceHeight
        if (glActive) glRenderer?.showMirror()
        Log.i(TAG, "Spiegelung gestartet (Auto ${surfaceWidth}x$surfaceHeight, Quelle ${vdWidth}x$vdHeight, GL=$glActive, Modus=$scaleMode)")
    }

    private fun releaseVirtualDisplayOnly() {
        virtualDisplay?.let {
            try {
                it.surface = null
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "VirtualDisplay konnte nicht sauber freigegeben werden", t)
            }
        }
        virtualDisplay = null
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
    }

    private fun releaseGlRenderer() {
        glRenderer?.let {
            try {
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "GL-Renderer konnte nicht sauber freigegeben werden", t)
            }
        }
        glRenderer = null
        glActive = false
    }

    private fun releaseProjection() {
        val current = projection ?: return
        releaseVirtualDisplayOnly()
        projectionCallback?.let {
            try {
                current.unregisterCallback(it)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterCallback fehlgeschlagen", t)
            }
        }
        try {
            current.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaProjection.stop fehlgeschlagen", t)
        }
        projection = null
        projectionCallback = null
    }

    /** Reale Groesse/Dichte des Handy-Standarddisplays; faellt notfalls auf die Autoflaeche zurueck. */
    private fun phoneDisplaySize(): Triple<Int, Int, Int> {
        val context = appContext
        if (context != null) {
            try {
                val dm = context.getSystemService(DisplayManager::class.java)
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                        val density = if (metrics.densityDpi > 0) metrics.densityDpi else surfaceDpi
                        return Triple(metrics.widthPixels, metrics.heightPixels, density)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Handy-Displaygroesse nicht ermittelbar", t)
            }
        }
        return Triple(surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1), surfaceDpi)
    }

    private fun publish() {
        val phase = when {
            virtualDisplay != null -> Phase.MIRRORING
            projection != null && surface != null && drivingPaused -> Phase.PAUSED_WHILE_DRIVING
            projection != null -> Phase.WAITING_FOR_CAR
            surface != null -> Phase.WAITING_FOR_PHONE
            else -> Phase.IDLE
        }
        _state.value = State(
            phase = phase,
            carSurfaceReady = surface != null,
            projectionActive = projection != null,
            scaleMode = scaleMode,
        )
    }

    /** Platzhaltertext per Canvas - nur im Fallback ohne GL. Mit GL besitzt der Renderer die Surface. */
    private fun drawPlaceholder() {
        if (glActive) return
        val target = surface ?: return
        if (!target.isValid) return
        val canvas = try {
            target.lockCanvas(null)
        } catch (t: Throwable) {
            Log.w(TAG, "lockCanvas nicht moeglich", t)
            null
        } ?: return

        try {
            canvas.drawColor(Color.BLACK)
            val context = appContext ?: return
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, canvas.width, canvas.height)
            val scale = surfaceDpi / DEFAULT_DPI.toFloat()

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 26f * scale
                isFakeBoldText = true
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                textAlign = Paint.Align.CENTER
                textSize = 16f * scale
            }

            val (titleRes, subtitleRes) = placeholderTexts()
            val centerX = area.exactCenterX()
            val centerY = area.exactCenterY()
            canvas.drawText(context.getString(titleRes), centerX, centerY, titlePaint)
            canvas.drawText(context.getString(subtitleRes), centerX, centerY + 30f * scale, subtitlePaint)
        } finally {
            try {
                target.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.w(TAG, "unlockCanvasAndPost fehlgeschlagen", t)
            }
        }
    }

    private fun placeholderTexts(): Pair<Int, Int> = when {
        drivingPaused -> R.string.car_paused_title to R.string.car_paused_subtitle
        projection == null -> R.string.car_idle_title to R.string.car_idle_subtitle
        else -> R.string.car_connecting_title to R.string.car_connecting_subtitle
    }

    /** Baut die Hinweistext-Ueberlagerung (App-Name + Status) fuer den GL-Leerlauf. */
    private fun buildIdleOverlay(): Bitmap? {
        val context = appContext ?: return null
        val w = surfaceWidth
        val h = surfaceHeight
        if (w <= 1 || h <= 1) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val scale = surfaceDpi / DEFAULT_DPI.toFloat()
            val shadow = Color.argb(170, 0, 0, 0)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 30f * scale
                isFakeBoldText = true
                setShadowLayer(8f, 0f, 2f, shadow)
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(235, 255, 255, 255)
                textAlign = Paint.Align.CENTER
                textSize = 17f * scale
                setShadowLayer(6f, 0f, 2f, shadow)
            }

            val (titleRes, subtitleRes) = placeholderTexts()
            val cx = w / 2f
            val cy = h / 2f
            canvas.drawText(context.getString(R.string.app_name), cx, cy - 20f * scale, titlePaint)
            canvas.drawText(context.getString(titleRes), cx, cy + 18f * scale, subtitlePaint)
            canvas.drawText(context.getString(subtitleRes), cx, cy + 44f * scale, subtitlePaint)
            bmp
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay-Bitmap fehlgeschlagen", t)
            null
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(Runnable { block() })
    }
}
