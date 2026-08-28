package com.livewallpaper.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Zeichnet den Video-Stream ueber eine SurfaceTexture (externe OES-Textur) auf die
 * Surface des Live-Hintergrunds. Dadurch lassen sich Seitenverhaeltnis, Abdunklung
 * und der Parallax-Versatz frei steuern - anders als beim direkten Rendern des
 * MediaPlayers, das das Video immer verzerrt.
 *
 * Alle GL-Aufrufe laufen auf einem eigenen Thread.
 */
class VideoRenderer(private val listener: Listener) {

    interface Listener {
        /**
         * Wird auf dem GL-Thread aufgerufen, sobald die Ziel-Surface bereit ist.
         * [source] erlaubt es dem Empfaenger, Meldungen eines inzwischen
         * verworfenen Renderers zu ignorieren.
         */
        fun onVideoSurfaceReady(source: VideoRenderer, surface: Surface)
    }

    private val thread = HandlerThread("video-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvpMatrix = 0
    private var uStMatrix = 0
    private var uDim = 0
    private var textureId = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var vertexBuffer: FloatBuffer? = null

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var viewWidth = 1
    private var viewHeight = 1
    private var videoAspect = 0f
    private var scaleMode = ScaleMode.CROP
    private var parallax = true
    private var xOffset = 0.5f
    private var dim = 0f

    private var hasFrame = false
    private var minFrameIntervalNanos = 0L
    private var lastDrawNanos = 0L
    @Volatile
    private var released = false

    // Vollflaechiges Rechteck: x, y, u, v
    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    fun start(target: Surface) {
        handler.post {
            if (released) return@post
            try {
                initEgl(target)
                initGl()
                Matrix.setIdentityM(stMatrix, 0)
                updateMatrix()
                drawFrame()
                videoSurface?.let { listener.onVideoSurfaceReady(this, it) }
            } catch (e: Exception) {
                Log.e(TAG, "GL-Initialisierung fehlgeschlagen", e)
                releaseGl()
            }
        }
    }

    fun setSurfaceSize(width: Int, height: Int) = handler.post {
        if (released || width <= 0 || height <= 0) return@post
        viewWidth = width
        viewHeight = height
        updateMatrix()
        drawFrame()
    }

    fun setVideoAspect(aspect: Float) = handler.post {
        if (released || aspect <= 0f) return@post
        if (videoAspect != aspect) {
            videoAspect = aspect
            updateMatrix()
            drawFrame()
        }
    }

    fun applySettings(settings: Settings) = handler.post {
        if (released) return@post
        scaleMode = settings.scaleMode
        parallax = settings.parallax
        dim = settings.dim / 100f
        minFrameIntervalNanos = PowerRules.minFrameIntervalNanos(settings.maxFps)
        updateMatrix()
        drawFrame()
    }

    fun setXOffset(offset: Float) = handler.post {
        if (released || !parallax) return@post
        val clamped = offset.coerceIn(0f, 1f)
        if (clamped != xOffset) {
            xOffset = clamped
            updateMatrix()
            drawFrame()
        }
    }

    /** Setzt das zuletzt gezeigte Bild zurueck, z.B. beim Videowechsel. */
    fun clearFrame() = handler.post {
        if (released) return@post
        hasFrame = false
        drawFrame()
    }

    fun release() {
        released = true
        handler.post {
            releaseGl()
            thread.quitSafely()
        }
    }

    private fun initEgl(target: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "kein EGL-Display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize fehlgeschlagen" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0
        ) { "keine passende EGL-Konfiguration" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext fehlgeschlagen" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface fehlgeschlagen" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent fehlgeschlagen"
        }
    }

    private fun initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpMatrix = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uDim = GLES20.glGetUniformLocation(program, "uDim")

        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexData)
                position(0)
            }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val texture = SurfaceTexture(textureId)
        texture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = texture
        videoSurface = Surface(texture)
    }

    /**
     * Ein neues Bild liegt bereit. Der Puffer wird immer abgeholt - sonst
     * blockiert der Decoder - gezeichnet wird aber hoechstens mit der
     * eingestellten Bildrate. Jedes ausgelassene Bild spart einen kompletten
     * GPU- und Anzeige-Durchlauf.
     */
    private fun onFrameAvailable() {
        if (released || eglSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(stMatrix)
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage fehlgeschlagen", e)
            return
        }
        hasFrame = true

        val now = System.nanoTime()
        if (minFrameIntervalNanos > 0L && now - lastDrawNanos < minFrameIntervalNanos) return
        lastDrawNanos = now
        drawFrame()
    }

    private fun drawFrame() {
        if (released || eglSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(BG_GREY, BG_GREY, BG_GREY, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (hasFrame && program != 0) {
            val buffer = vertexBuffer
            if (buffer != null) {
                GLES20.glUseProgram(program)

                buffer.position(0)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
                GLES20.glEnableVertexAttribArray(aPosition)

                buffer.position(2)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
                GLES20.glEnableVertexAttribArray(aTexCoord)

                GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)
                GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
                GLES20.glUniform1f(uDim, dim)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aTexCoord)
            }
        }

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Berechnet Skalierung und Versatz des Rechtecks. Das Rechteck deckt bei
     * Skalierung 1/1 exakt den Bildschirm ab; groessere Werte schneiden ab,
     * kleinere erzeugen Raender.
     */
    private fun updateMatrix() {
        val surfaceAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val scale = ScaleMath.scale(videoAspect, surfaceAspect, scaleMode)
        val translateX = ScaleMath.parallax(scale[0], xOffset, parallax)

        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.translateM(mvpMatrix, 0, translateX, 0f, 0f)
        Matrix.scaleM(mvpMatrix, 0, scale[0], scale[1], 1f)
    }

    private fun releaseGl() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        program = 0
        textureId = 0
        hasFrame = false
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertexShader)
        GLES20.glAttachShader(id, fragmentShader)
        GLES20.glLinkProgram(id)

        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            error("Shader-Programm nicht linkbar: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return id
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader nicht kompilierbar: $log")
        }
        return shader
    }

    private companion object {
        const val TAG = "VideoRenderer"
        const val STRIDE = 4 * 4
        const val BG_GREY = 0.04f

        val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uStMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uDim;
            void main() {
                vec4 color = texture2D(sTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb * (1.0 - uDim), 1.0);
            }
        """.trimIndent()
    }
}
