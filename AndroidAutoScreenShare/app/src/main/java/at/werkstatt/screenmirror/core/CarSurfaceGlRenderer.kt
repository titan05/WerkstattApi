package at.werkstatt.screenmirror.core

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Rendert den gespiegelten Handybildschirm **seitenverhaeltnis-korrekt** auf die Auto-Surface.
 *
 * Warum ueberhaupt OpenGL? Ein [android.hardware.display.VirtualDisplay] mit AUTO_MIRROR streckt
 * den Handyinhalt stur auf die Zielgroesse - bei Hochformat-Handy auf Breitbild-Autoscreen wirkt
 * das "zusammengequetscht". Deshalb rendert das VirtualDisplay hier zuerst in eine
 * [SurfaceTexture] ([inputSurface]) und diese Textur wird dann per GL mit dem korrekten Rechteck
 * auf die Auto-Surface gezeichnet:
 *
 *  * [ScaleMode.FILL] - fuellt die Flaeche, schneidet ueberstehende Raender ab
 *  * [ScaleMode.FIT]  - zeigt alles, laesst ggf. Raender frei
 *
 * Der gesamte GL-Zustand lebt auf einem eigenen Thread; [start] blockiert, bis [inputSurface]
 * bereitsteht, damit der Aufrufer sofort das VirtualDisplay daran haengen kann.
 */
class CarSurfaceGlRenderer(
    private val outputSurface: Surface,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    @Volatile private var scaleMode: MirrorEngine.ScaleMode,
) {

    private val thread = HandlerThread("CarMirrorGl")
    private lateinit var handler: Handler

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var textureId = 0

    private var surfaceTexture: SurfaceTexture? = null

    /** Zieloberflaeche fuer das VirtualDisplay - erst nach erfolgreichem [start] gueltig. */
    var inputSurface: Surface? = null
        private set

    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    @Volatile private var released = false

    /** BLACK = Warte-/Pausenzustand (animierter Farbverlauf), MIRROR = Handybild wird gezeichnet. */
    enum class Mode { BLACK, MIRROR }
    @Volatile private var mode = Mode.BLACK

    // Animierter RGB-Leerlauf-Hintergrund, solange nicht gespiegelt wird.
    private var gradientProgram = 0
    private var gradPositionLoc = 0
    private var gradResLoc = 0
    private var gradTimeLoc = 0
    private var startNanos = 0L
    private val fullQuad: FloatBuffer = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )

    // Textueberlagerung (Hinweistext) fuer den Leerlauf.
    private var overlayProgram = 0
    private var overlayPositionLoc = 0
    private var overlayTexCoordLoc = 0
    private var overlaySamplerLoc = 0
    private var overlayTextureId = 0
    @Volatile private var overlayReady = false
    private val overlayQuad: FloatBuffer = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    // GLUtils laedt die Bitmap mit (0,0) oben-links -> V hier spiegeln.
    private val overlayTexCoords: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )
    )

    // Leerlauf-Animation vsync-genau ueber den Choreographer (fluessig statt ruckelnd).
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val idleFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released || mode != Mode.BLACK) return
            drawIdle()
            choreographer.postFrameCallback(this)
        }
    }

    private val texCoords: FloatBuffer = floatBuffer(
        // s, t, 0, 1  (wird mit der SurfaceTexture-Transformationsmatrix multipliziert)
        floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )
    )
    private val positions: FloatBuffer = floatBuffer(FloatArray(16))

    /** Startet den GL-Thread und initialisiert EGL. Gibt true zurueck, wenn [inputSurface] bereit ist. */
    fun start(): Boolean {
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = try {
                initGl()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "GL-Init fehlgeschlagen", t)
                false
            }
            latch.countDown()
        }
        latch.await()
        if (!ok) release()
        return ok
    }

    fun setScaleMode(newMode: MirrorEngine.ScaleMode) {
        scaleMode = newMode
        if (released) return
        handler.post {
            updatePositions()
            if (mode == Mode.MIRROR && hasFrame) drawFrame(updateTexture = false)
        }
    }

    /** Handybild anzeigen (sobald Frames ueber [inputSurface] eintreffen). */
    fun showMirror() {
        if (released) return
        handler.post {
            mode = Mode.MIRROR
            choreographer.removeFrameCallback(idleFrameCallback)
            if (hasFrame) drawFrame(updateTexture = false) else clearBlack()
        }
    }

    /** Animierten RGB-Leerlauf anzeigen (Warten/Pause/Stopp) - haelt die Surface unter GL-Besitz. */
    fun showBlack() {
        if (released) return
        handler.post {
            mode = Mode.BLACK
            choreographer.removeFrameCallback(idleFrameCallback)
            choreographer.postFrameCallback(idleFrameCallback)
        }
    }

    /** Setzt/aktualisiert die Hinweistext-Ueberlagerung fuer den Leerlauf (null = keine). */
    fun setIdleOverlay(bitmap: Bitmap?) {
        if (released) return
        handler.post { uploadOverlay(bitmap) }
    }

    private fun uploadOverlay(bitmap: Bitmap?) {
        if (overlayTextureId == 0 || bitmap == null || bitmap.isRecycled) {
            overlayReady = false
            return
        }
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            overlayReady = true
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay-Upload fehlgeschlagen", t)
            overlayReady = false
        }
    }

    private fun clearBlack() {
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /** Ein Frame des animierten RGB-Leerlaufs (Hintergrund + Hinweistext). */
    private fun drawIdle() {
        if (released) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (gradientProgram != 0) {
            val time = (System.nanoTime() - startNanos) / 1_000_000_000f
            GLES20.glUseProgram(gradientProgram)
            fullQuad.position(0)
            GLES20.glEnableVertexAttribArray(gradPositionLoc)
            GLES20.glVertexAttribPointer(gradPositionLoc, 2, GLES20.GL_FLOAT, false, 0, fullQuad)
            GLES20.glUniform2f(gradResLoc, outputWidth.toFloat(), outputHeight.toFloat())
            GLES20.glUniform1f(gradTimeLoc, time)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(gradPositionLoc)
        }

        if (overlayReady && overlayProgram != 0) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(overlayProgram)
            overlayQuad.position(0)
            GLES20.glEnableVertexAttribArray(overlayPositionLoc)
            GLES20.glVertexAttribPointer(overlayPositionLoc, 2, GLES20.GL_FLOAT, false, 0, overlayQuad)
            overlayTexCoords.position(0)
            GLES20.glEnableVertexAttribArray(overlayTexCoordLoc)
            GLES20.glVertexAttribPointer(overlayTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, overlayTexCoords)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLES20.glUniform1i(overlaySamplerLoc, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(overlayPositionLoc)
            GLES20.glDisableVertexAttribArray(overlayTexCoordLoc)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        // handler kann fehlen, falls start() nie lief.
        if (this::handler.isInitialized) {
            handler.post {
                try {
                    choreographer.removeFrameCallback(idleFrameCallback)
                    releaseGl()
                } catch (t: Throwable) {
                    Log.w(TAG, "GL-Freigabe fehlgeschlagen", t)
                } finally {
                    latch.countDown()
                }
            }
        } else {
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ GL-Thread

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "Keine passende EGL-Konfiguration"
        }
        val config = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext fehlgeschlagen" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        // Wichtig: eglMakeCurrent kann mit EGL_NO_SURFACE "surfaceless" erfolgreich sein - dann
        // ginge die Ausgabe ins Leere. Deshalb hier explizit auf eine echte Window-Surface pruefen.
        require(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface lieferte EGL_NO_SURFACE (Surface evtl. schon anderweitig belegt)"
        }
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent fehlgeschlagen" }

        program = buildProgramFrom(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

        // Optionaler Leerlauf-Farbverlauf; scheitert er, bleibt der Leerlauf einfach schwarz.
        startNanos = System.nanoTime()
        gradientProgram = try {
            buildProgramFrom(GRADIENT_VERTEX_SHADER, GRADIENT_FRAGMENT_SHADER)
        } catch (t: Throwable) {
            Log.w(TAG, "Gradient-Programm nicht verfuegbar - Leerlauf bleibt schwarz", t)
            0
        }
        if (gradientProgram != 0) {
            gradPositionLoc = GLES20.glGetAttribLocation(gradientProgram, "aPosition")
            gradResLoc = GLES20.glGetUniformLocation(gradientProgram, "uRes")
            gradTimeLoc = GLES20.glGetUniformLocation(gradientProgram, "uTime")
        }

        // Overlay-Programm + Textur fuer den Hinweistext (optional).
        overlayProgram = try {
            buildProgramFrom(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay-Programm nicht verfuegbar", t)
            0
        }
        if (overlayProgram != 0) {
            overlayPositionLoc = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
            overlayTexCoordLoc = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
            overlaySamplerLoc = GLES20.glGetUniformLocation(overlayProgram, "uTex")
            val ot = IntArray(1)
            GLES20.glGenTextures(1, ot, 0)
            overlayTextureId = ot[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(sourceWidth.coerceAtLeast(1), sourceHeight.coerceAtLeast(1))
        st.setOnFrameAvailableListener({
            if (!released) handler.post { drawFrame(updateTexture = true) }
        }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)

        updatePositions()

        // Erste (leere) Ausgabe schwarz, damit keine alten Pixel stehen bleiben.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawFrame(updateTexture: Boolean) {
        if (released) return
        val st = surfaceTexture ?: return
        if (updateTexture) {
            try {
                // Immer abholen, damit die Buffer-Queue nicht blockiert - auch im BLACK-Modus.
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
                hasFrame = true
            } catch (t: Throwable) {
                Log.w(TAG, "updateTexImage fehlgeschlagen", t)
                return
            }
        }
        // Im Warte-/Pausenzustand wird das Handybild bewusst nicht gezeichnet.
        if (mode != Mode.MIRROR) return
        if (!hasFrame) return

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        positions.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 4, GLES20.GL_FLOAT, false, 0, positions)

        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 4, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Berechnet die Eckpunkte des Quads in Normalized Device Coordinates so, dass das
     * Quellbild sein Seitenverhaeltnis behaelt (FIT = einpassen, FILL = ausfuellen/beschneiden).
     */
    private fun updatePositions() {
        val outW = outputWidth.toFloat()
        val outH = outputHeight.toFloat()
        val srcW = sourceWidth.coerceAtLeast(1).toFloat()
        val srcH = sourceHeight.coerceAtLeast(1).toFloat()

        val scale = when (scaleMode) {
            MirrorEngine.ScaleMode.FIT -> minOf(outW / srcW, outH / srcH)
            MirrorEngine.ScaleMode.FILL -> maxOf(outW / srcW, outH / srcH)
        }
        // Halbe Breite/Hoehe des angezeigten Bildes in NDC (Viewport ist -1..1).
        val ndcX = (srcW * scale) / outW
        val ndcY = (srcH * scale) / outH

        val verts = floatArrayOf(
            -ndcX, -ndcY, 0f, 1f,   // unten links
            ndcX, -ndcY, 0f, 1f,   // unten rechts
            -ndcX, ndcY, 0f, 1f,   // oben links
            ndcX, ndcY, 0f, 1f,   // oben rechts
        )
        positions.clear()
        positions.put(verts)
        positions.position(0)
    }

    private fun releaseGl() {
        inputSurface?.release()
        inputSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgramFrom(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertex)
        GLES20.glAttachShader(prog, fragment)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) { "Programm-Link fehlgeschlagen: " + GLES20.glGetProgramInfoLog(prog) }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) { "Shader-Compile fehlgeschlagen: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    companion object {
        private const val TAG = "CarMirrorGl"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val GRADIENT_VERTEX_SHADER = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // Sternenhimmel: ruhiger, sehr langsam driftender Nebel + dichtes, durchgehend
        // sichtbares Sternenfeld; einzelne Sterne blitzen zufaellig immer wieder hell auf.
        private const val GRADIENT_FRAGMENT_SHADER = """
            precision highp float;
            uniform vec2 uRes;
            uniform float uTime;
            float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float noise(vec2 p){
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }
            float fbm(vec2 p){
                float v = 0.0, a = 0.5;
                for (int i = 0; i < 5; i++){ v += a * noise(p); p *= 2.03; a *= 0.5; }
                return v;
            }
            // Ein Stern je Rasterzelle. Immer sichtbar (Grundhelligkeit + leichtes Flimmern);
            // einzelne blitzen zufaellig kurz hell auf (glint), je Stern anders getaktet.
            float stars(vec2 sv, float density, float seed, float thresh){
                vec2 g = sv * density;
                vec2 cell = floor(g);
                vec2 f = fract(g);
                if (hash(cell + seed) < thresh) return 0.0;
                vec2 pos = vec2(hash(cell + seed + 1.3), hash(cell + seed + 2.7));
                float d = length(f - pos);
                float size = mix(0.010, 0.045, hash(cell + seed + 4.1));
                float core = smoothstep(size, 0.0, d);
                float bright = mix(0.40, 1.0, hash(cell + seed + 3.3));
                float speed = mix(0.4, 2.0, hash(cell + seed + 5.5));
                float phase = hash(cell + seed + 6.9) * 6.2831;
                float shimmer = 0.12 * sin(uTime * speed + phase);
                float gsp = mix(0.20, 0.8, hash(cell + seed + 9.4));
                float gph = hash(cell + seed + 8.2) * 6.2831;
                float glint = pow(max(0.0, sin(uTime * gsp + gph)), 44.0);
                return core * bright * (0.85 + shimmer + glint * 1.8);
            }
            void main() {
                vec2 uv = gl_FragCoord.xy / uRes;
                float aspect = uRes.x / uRes.y;
                vec2 sv = vec2(uv.x * aspect, uv.y);
                float t = uTime * 0.010;
                float n = fbm(sv * 2.0 + vec2(t, t * 0.5));
                float n2 = fbm(sv * 3.2 - vec2(t * 0.6, t * 0.4));
                vec3 col = vec3(0.006, 0.008, 0.024);
                col += mix(vec3(0.03, 0.02, 0.10), vec3(0.06, 0.05, 0.20), n) * 0.65;
                col += vec3(0.18, 0.06, 0.24) * smoothstep(0.64, 1.0, n2) * 0.45;
                col += vec3(0.0, 0.07, 0.15) * smoothstep(0.62, 1.0, n) * 0.30;
                float s = 0.0;
                s += stars(sv, 48.0, 37.0, 0.70);
                s += stars(sv, 30.0, 11.0, 0.55);
                s += stars(sv, 15.0, 71.0, 0.86) * 1.4;
                col += vec3(0.92, 0.95, 1.0) * s;
                gl_FragColor = vec4(col, 1.0);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTexCoord);
            }
        """
    }
}
