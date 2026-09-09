package at.werkstatt.screenmirror.core

import android.content.Context
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
 *  * die [MediaProjection] vom Handy (kommt aus dem [at.werkstatt.screenmirror.ProjectionService])
 *  * die Surface vom Autodisplay (kommt aus der Car App Session)
 *
 * Sobald beide vorhanden sind, wird ein [VirtualDisplay] erzeugt, das den Handybildschirm
 * direkt in die Surface des Autos rendert. Faellt eines der beiden Enden weg, wird das
 * VirtualDisplay wieder freigegeben und auf der Autoflaeche ein Hinweistext gezeichnet.
 *
 * Alle Zustandsaenderungen laufen ueber den Main-Thread, damit sich Surface-Besitz
 * (VirtualDisplay vs. lockCanvas) nicht ueberschneidet.
 */
object MirrorEngine {

    private const val TAG = "MirrorEngine"
    private const val VIRTUAL_DISPLAY_NAME = "CarScreenMirror"
    private const val DEFAULT_DPI = 160

    enum class Phase {
        /** Weder Handy-Freigabe noch Auto verbunden. */
        IDLE,

        /** Freigabe laeuft, aber Android Auto zeigt die App gerade nicht an. */
        WAITING_FOR_CAR,

        /** Auto ist da, aber am Handy wurde die Freigabe noch nicht gestartet. */
        WAITING_FOR_PHONE,

        /** Es wird gespiegelt. */
        MIRRORING,

        /** Alles bereit, aber das Fahrzeug bewegt sich und die Sperre ist aktiv. */
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

    /** Wird gesetzt, wenn die Systemfreigabe von aussen beendet wurde (z.B. Stop-Button im Systemdialog). */
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

    private var virtualDisplay: VirtualDisplay? = null
    private var glRenderer: CarSurfaceGlRenderer? = null
    private var virtualDisplayWidth = 0
    private var virtualDisplayHeight = 0

    private var drivingPaused = false

    @Volatile
    private var scaleMode = ScaleMode.FILL

    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
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
        // Der Host kann eine neue Surface liefern; das alte VirtualDisplay haelt sonst die alte fest.
        releaseVirtualDisplay()
        surface = newSurface
        surfaceWidth = width
        surfaceHeight = height
        surfaceDpi = if (dpi > 0) dpi else DEFAULT_DPI
        Log.i(TAG, "Car-Surface verfuegbar: ${width}x$height @ ${surfaceDpi}dpi")
        sync()
    }

    fun onCarSurfaceDestroyed() = onMain {
        releaseVirtualDisplay()
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
        visibleArea = null
        sync()
    }

    fun onVisibleAreaChanged(area: Rect) = onMain {
        visibleArea = Rect(area)
        if (virtualDisplay == null) drawPlaceholder()
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
            releaseVirtualDisplay()
            drawPlaceholder()
        }
        publish()
    }

    private fun ensureVirtualDisplay(mediaProjection: MediaProjection, target: Surface) {
        // Laeuft bereits fuer genau diese Zielgroesse? Dann nichts tun.
        if (virtualDisplay != null && virtualDisplayWidth == surfaceWidth && virtualDisplayHeight == surfaceHeight) {
            return
        }
        // Groesse oder Surface hat sich geaendert -> sauber neu aufbauen.
        releaseVirtualDisplay()

        val (sourceW, sourceH, sourceDensity) = phoneDisplaySize()

        // GL-Compositor rendert den Handyinhalt seitenverhaeltnis-korrekt auf die Autoflaeche.
        // Faellt er aus, wird direkt (gestreckt) gespiegelt, damit ueberhaupt ein Bild ankommt.
        val renderer = CarSurfaceGlRenderer(
            outputSurface = target,
            outputWidth = surfaceWidth,
            outputHeight = surfaceHeight,
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            scaleMode = scaleMode,
        )
        val glReady = try {
            renderer.start()
        } catch (t: Throwable) {
            Log.e(TAG, "GL-Renderer-Start fehlgeschlagen", t)
            false
        }

        val vdSurface = (renderer.inputSurface.takeIf { glReady }) ?: target
        val vdWidth = if (glReady) sourceW else surfaceWidth
        val vdHeight = if (glReady) sourceH else surfaceHeight
        val vdDensity = if (glReady) sourceDensity else surfaceDpi

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
        }

        if (display == null) {
            renderer.release()
            return
        }

        virtualDisplay = display
        glRenderer = renderer.takeIf { glReady }
        if (!glReady) renderer.release()
        virtualDisplayWidth = surfaceWidth
        virtualDisplayHeight = surfaceHeight
        Log.i(TAG, "Spiegelung gestartet (Auto ${surfaceWidth}x$surfaceHeight, Quelle ${sourceW}x$sourceH, GL=$glReady, Modus=$scaleMode)")
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.let {
            try {
                it.surface = null
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "VirtualDisplay konnte nicht sauber freigegeben werden", t)
            }
        }
        virtualDisplay = null
        glRenderer?.let {
            try {
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "GL-Renderer konnte nicht sauber freigegeben werden", t)
            }
        }
        glRenderer = null
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
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

    private fun releaseProjection() {
        val current = projection ?: return
        releaseVirtualDisplay()
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

    /** Zeichnet den Hinweistext auf die Autoflaeche, solange nicht gespiegelt wird. */
    private fun drawPlaceholder() {
        val target = surface ?: return
        if (!target.isValid) return
        val canvas = try {
            target.lockCanvas(null)
        } catch (t: Throwable) {
            // Direkt nach dem Freigeben eines VirtualDisplay kann das kurzzeitig fehlschlagen.
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

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(Runnable { block() })
    }
}
