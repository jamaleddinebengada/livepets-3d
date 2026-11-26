package com.livepets3d.app.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.TextureView
import com.livepets3d.app.anim.PetController

/**
 * Transparent TextureView that renders the 3D pet on its own EGL / GLES 3.0 thread.
 * TextureView (instead of GLSurfaceView) composes like a normal view, so the pet can be
 * layered under 2D effects and works inside translucent overlay windows.
 */
class PetSurfaceView(context: Context, val controller: PetController) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: RenderThread? = null
    var targetFps: Int = 60

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        thread = RenderThread(st, width, height).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private inner class RenderThread(private val st: SurfaceTexture, @Volatile var w: Int, @Volatile var h: Int) : Thread("PetGL") {
        @Volatile private var running = true
        @Volatile private var sizeChanged = true
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surf: EGLSurface = EGL14.EGL_NO_SURFACE

        fun resize(nw: Int, nh: Int) { w = nw; h = nh; sizeChanged = true }

        fun finish() {
            running = false
            try { join(800) } catch (_: InterruptedException) {}
        }

        override fun run() {
            try {
                if (!initEgl()) return
                val renderer = PetRenderer(controller)
                renderer.init()
                var last = System.nanoTime()
                while (running) {
                    if (controller.paused) { sleep(120); last = System.nanoTime(); continue }
                    if (sizeChanged) { sizeChanged = false; renderer.resize(w, h) }
                    val now = System.nanoTime()
                    val dt = (now - last) / 1e9f
                    last = now
                    renderer.frame(dt)
                    if (!EGL14.eglSwapBuffers(display, surf)) {
                        Log.w("LivePetsGL", "swap failed 0x" + Integer.toHexString(EGL14.eglGetError()))
                        break
                    }
                    val fps = if (controller.batterySaver) 24 else targetFps
                    val budget = 1_000_000_000L / fps
                    val spent = System.nanoTime() - now
                    if (spent < budget) sleep((budget - spent) / 1_000_000L, ((budget - spent) % 1_000_000L).toInt())
                }
            } catch (e: Throwable) {
                Log.e("LivePetsGL", "render thread", e)
            } finally {
                releaseEgl()
            }
        }

        private fun choose(samples: Int): EGLConfig? {
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL14.EGL_SAMPLES, samples,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0)) return null
            return if (num[0] > 0) configs[0] else null
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) return false
            val cfg = choose(4) ?: choose(0) ?: return false
            ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return false
            surf = EGL14.eglCreateWindowSurface(display, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) return false
            return EGL14.eglMakeCurrent(display, surf, surf, ctx)
        }

        private fun releaseEgl() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                // Note: no eglTerminate — the default display is shared by every pet view in the process.
            }
            display = EGL14.EGL_NO_DISPLAY
        }
    }
}
