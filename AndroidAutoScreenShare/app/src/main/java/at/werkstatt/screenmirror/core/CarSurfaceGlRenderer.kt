package at.werkstatt.screenmirror.core

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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

    /** BLACK = Warte-/Pausenzustand (schwarz), MIRROR = Handybild wird gezeichnet. */
    enum class Mode { BLACK, MIRROR }
    @Volatile private var mode = Mode.BLACK

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
            if (hasFrame) drawFrame(updateTexture = false) else clearBlack()
        }
    }

    /** Schwarzbild anzeigen (Warten/Pause/Stopp) - haelt die Surface unter GL-Besitz. */
    fun showBlack() {
        if (released) return
        handler.post {
            mode = Mode.BLACK
            clearBlack()
        }
    }

    private fun clearBlack() {
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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

        program = buildProgram()
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

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

    private fun buildProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
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
    }
}
