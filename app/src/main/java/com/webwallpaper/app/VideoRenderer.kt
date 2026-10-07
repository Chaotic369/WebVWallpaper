package com.webwallpaper.app

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plays a video through MediaPlayer -> SurfaceTexture -> OpenGL so Crop / Fit / Stretch and parallax work. */
class VideoRenderer(private val holder: SurfaceHolder, private val path: String) :
    Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("wp-video").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var settings = Settings()
    @Volatile private var tx = 0f
    @Volatile private var ty = 0f
    @Volatile private var released = false
    private var wantRun = false
    private var prepared = false
    private var userPaused = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastTap = 0L

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTexMat = 0
    private var st: SurfaceTexture? = null
    private var surf: Surface? = null
    private var player: MediaPlayer? = null
    private var vw = 0
    private var vh = 0
    private var lastDraw = 0L
    private val texMat = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    override fun start() { handler.post { try { initGl(); initPlayer() } catch (e: Exception) { e.printStackTrace() } } }

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attr, 0, cfgs, 0, 1, n, 0)
        eglCtx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglCtx)

        val vs = "attribute vec4 aPos; attribute vec2 aTex; uniform mat4 uMvp; uniform mat4 uTexMat; varying vec2 vTex;" +
            "void main(){ gl_Position = uMvp * aPos; vTex = (uTexMat * vec4(aTex,0.0,1.0)).xy; }"
        val fs = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex;" +
            "uniform samplerExternalOES sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun initPlayer() {
        val tex = SurfaceTexture(texId)
        tex.setOnFrameAvailableListener(this, handler)
        st = tex
        val s = Surface(tex)
        surf = s
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(path)
        mp.setSurface(s)
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        mp.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; draw(false) }
        mp.setOnPreparedListener { prepared = true; if (wantRun && !userPaused) it.start() }
        mp.setOnErrorListener { _, _, _ -> true }
        mp.prepareAsync()
    }

    override fun onFrameAvailable(t: SurfaceTexture?) { draw(true) }

    private fun draw(newFrame: Boolean) {
        if (released || display == EGL14.EGL_NO_DISPLAY || st == null) return
        try {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglCtx)
            if (newFrame) st?.updateTexImage()
            st?.getTransformMatrix(texMat)
            if (newFrame) {
                val iv = settings.videoIntervalMs
                val now = SystemClock.uptimeMillis()
                if (iv > 0 && now - lastDraw < iv - 4) return
                lastDraw = now
            }
            val dim = IntArray(1)
            EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, dim, 0); val w = dim[0]
            EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, dim, 0); val h = dim[0]
            if (w <= 0 || h <= 0) return
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (vw > 0 && vh > 0) {
                var sx = 1f
                var sy = 1f
                if (settings.scale != "Stretch") {
                    val a = w.toFloat() / vw
                    val b = h.toFloat() / vh
                    val s = if (settings.scale == "Fit") minOf(a, b) else maxOf(a, b)
                    sx = vw * s / w; sy = vh * s / h
                }
                var ox = 0f
                var oy = 0f
                if (settings.gyro) { sx *= 1.12f; sy *= 1.12f; ox = tx * 0.1f; oy = ty * 0.1f }
                val mvp = floatArrayOf(sx, 0f, 0f, 0f, 0f, sy, 0f, 0f, 0f, 0f, 1f, 0f, ox, oy, 0f, 1f)
                GLES20.glUseProgram(program)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                quad.position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(aPos)
                quad.position(2)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun applyPlay() {
        val mp = player ?: return
        val run = wantRun && !userPaused
        try {
            if (run && prepared && !mp.isPlaying) mp.start()
            else if (!run && prepared && mp.isPlaying) mp.pause()
        } catch (e: Exception) { }
    }

    override fun setRunning(run: Boolean) {
        handler.post { wantRun = run; applyPlay() }
    }

    override fun setSettings(s: Settings) { settings = s; handler.post { draw(false) } }
    override fun setTilt(x: Float, y: Float) { tx = x; ty = y; if (!wantRun) handler.post { draw(false) } }
    /** Double-tap the wallpaper to pause / resume the video (only when touch pass-through is off). */
    override fun touch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime }
            MotionEvent.ACTION_UP -> {
                val tap = Math.abs(e.x - downX) < 32 && Math.abs(e.y - downY) < 32 && e.eventTime - downT < 500
                if (tap) {
                    if (e.eventTime - lastTap < 400) {
                        lastTap = 0L
                        handler.post { userPaused = !userPaused; applyPlay() }
                    } else lastTap = e.eventTime
                }
            }
        }
    }

    override fun stop() {
        val latch = CountDownLatch(1)
        handler.post {
            released = true
            try { player?.release() } catch (e: Exception) { }
            try { surf?.release() } catch (e: Exception) { }
            try { st?.release() } catch (e: Exception) { }
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, eglCtx)
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
            latch.countDown()
            thread.quitSafely()
        }
        latch.await(2, TimeUnit.SECONDS)
    }
}
