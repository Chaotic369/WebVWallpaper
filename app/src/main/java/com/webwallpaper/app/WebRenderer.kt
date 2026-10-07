package com.webwallpaper.app

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Renders a web page / local HTML into the wallpaper surface.
 *
 * Fast path (default): the WebView lives in a Presentation on a private VirtualDisplay whose output is the wallpaper
 * Surface itself. The page is GPU-composited at the display refresh rate and receives real touch events, so CSS
 * animations, canvas/WebGL, scrolling and swiping behave exactly like in a browser.
 *
 * Fallback: if the device refuses the virtual display, an off-screen software WebView is copied into the surface.
 */
class WebRenderer(
    private val ctx: Context, private val holder: SurfaceHolder,
    private val src: String, private val sw: Int, private val sh: Int
) : Renderer {

    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var settings = Settings()
    private var tx = 0f
    private var ty = 0f
    private var running = false

    // hardware path
    private var vd: VirtualDisplay? = null
    private var pres: WebPresentation? = null
    private var box: FrameLayout? = null
    private var hw = false

    // software fallback
    private val tick = object : Runnable {
        override fun run() {
            if (!running || hw) return
            drawFrame()
            handler.postDelayed(this, settings.webIntervalMs.coerceAtLeast(16L))
        }
    }

    override fun start() {
        hw = try { startHardware() } catch (e: Throwable) { e.printStackTrace(); false }
        if (!hw) { releaseHardware(); startSoftware() }
    }

    // ---------------------------------------------------------------- hardware (virtual display) path

    private inner class WebPresentation(c: Context, d: Display) : Presentation(c, d) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val root = FrameLayout(context)
            root.setBackgroundColor(Color.BLACK)
            val b = FrameLayout(context)
            val v = newWebView(context)
            b.addView(v, FrameLayout.LayoutParams(1, 1))
            root.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web = v
            box = b
        }
    }

    private fun startHardware(): Boolean {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val dpi = ctx.resources.displayMetrics.densityDpi
        val d = dm.createVirtualDisplay(
            "wp-web", sw, sh, dpi, holder.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        ) ?: return false
        vd = d
        val p = WebPresentation(ctx, d.display)
        pres = p
        p.show()
        val v = web ?: return false
        layoutWeb()
        applyGyro()
        v.loadUrl(src)
        return true
    }

    private fun releaseHardware() {
        try { pres?.dismiss() } catch (e: Throwable) { }
        pres = null
        try { vd?.release() } catch (e: Throwable) { }
        vd = null
        web?.let { try { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } catch (e: Throwable) { } }
        web = null
        box = null
    }

    private fun newWebView(c: Context): WebView {
        val v = WebView(c)
        v.setBackgroundColor(Color.BLACK)
        v.isVerticalScrollBarEnabled = false
        v.isHorizontalScrollBarEnabled = false
        v.overScrollMode = View.OVER_SCROLL_NEVER
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // behave like the page does in the browser (same viewport / font handling as Chrome)
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            minimumFontSize = 1
            minimumLogicalFontSize = 1
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request == null) null else LocalServer.intercept(ctx, request)

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!hw) view?.evaluateJavascript(TOUCH_HELPER, null)
                applyFpsCap()
            }
        }
        return v
    }

    /** Page logical size = screen / zoom; the whole page is then scaled by zoom (GPU) so it still fills the screen. */
    private fun layoutWeb() {
        val v = web ?: return
        val z = settings.zoom
        if (hw) {
            val lw = (sw / z).toInt().coerceAtLeast(1)
            val lh = (sh / z).toInt().coerceAtLeast(1)
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = lw; lp.height = lh
            v.layoutParams = lp
            v.pivotX = 0f; v.pivotY = 0f
            v.scaleX = z; v.scaleY = z
        } else {
            val lw = (sw / z).toInt().coerceAtLeast(1)
            val lh = (sh / z).toInt().coerceAtLeast(1)
            v.measure(View.MeasureSpec.makeMeasureSpec(lw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(lh, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, lw, lh)
        }
    }

    private fun applyGyro() {
        val b = box ?: return
        if (settings.gyro) {
            b.pivotX = sw / 2f; b.pivotY = sh / 2f
            b.scaleX = 1.1f; b.scaleY = 1.1f
            b.translationX = tx * sw * 0.04f
            b.translationY = ty * sh * 0.04f
        } else {
            b.scaleX = 1f; b.scaleY = 1f; b.translationX = 0f; b.translationY = 0f
        }
    }

    /** "30 FPS" caps requestAnimationFrame inside the page; every other setting runs at the display's refresh rate. */
    private fun applyFpsCap() {
        if (!hw) return
        val ms = settings.webIntervalMs
        web?.evaluateJavascript(
            "(function(m){if(!window.__wpRaf){var o=window.requestAnimationFrame.bind(window),g=-1e9;" +
            "window.__wpRaf=1;window.requestAnimationFrame=function(cb){return o(function(t){" +
            "if(window.__wpMin&&t!==g&&t-g<window.__wpMin-3){return window.requestAnimationFrame(cb);}g=t;cb(t);});};}" +
            "window.__wpMin=m;})($ms)", null
        )
    }

    // ---------------------------------------------------------------- software fallback path

    private fun startSoftware() {
        val v = newWebView(ctx)
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        web = v
        layoutWeb()
        v.loadUrl(src)
        if (running) handler.post(tick)
    }

    private fun drawFrame() {
        val v = web ?: return
        val c = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return
        try {
            c.drawColor(Color.BLACK)
            c.save()
            if (settings.gyro) {
                c.translate(sw / 2f + tx * sw * 0.04f, sh / 2f + ty * sh * 0.04f)
                c.scale(1.1f, 1.1f)
                c.translate(-sw / 2f, -sh / 2f)
            }
            c.scale(settings.zoom, settings.zoom)
            v.draw(c)
            c.restore()
        } finally {
            try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- Renderer interface

    override fun setRunning(run: Boolean) {
        if (run == running) return
        running = run
        val v = web ?: return
        handler.removeCallbacks(tick)
        if (run) {
            v.onResume(); v.resumeTimers()
            if (!hw) handler.post(tick)
        } else v.onPause()
    }

    override fun setSettings(s: Settings) {
        val zoomChanged = s.zoom != settings.zoom
        settings = s
        if (zoomChanged) layoutWeb()
        applyGyro()
        applyFpsCap()
        if (!hw && !running) drawFrame()
    }

    override fun setTilt(x: Float, y: Float) {
        tx = x; ty = y
        if (hw) handler.post { applyGyro() }
    }

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private fun js(kind: String, x: Float, y: Float) {
        web?.evaluateJavascript("window.__wpTouch&&window.__wpTouch('$kind',$x,$y)", null)
    }

    override fun touch(e: MotionEvent) {
        val v = web ?: return
        if (hw) {
            // real touch events -> native scrolling, swiping, taps, long-press, drag
            val z = settings.zoom
            val m = MotionEvent.obtain(e)
            m.setLocation(e.x / z, e.y / z)
            try { v.dispatchTouchEvent(m) } finally { m.recycle() }
            return
        }
        val d = ctx.resources.displayMetrics.density
        val x = e.x / settings.zoom / d
        val y = e.y / settings.zoom / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; js("down", x, y) }
            MotionEvent.ACTION_MOVE -> js("move", x, y)
            MotionEvent.ACTION_UP -> {
                js("up", x, y)
                if (abs(e.x - downX) < 32 && abs(e.y - downY) < 32 && e.eventTime - downT < 500) js("click", x, y)
            }
            MotionEvent.ACTION_CANCEL -> js("up", x, y)
        }
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
        if (hw || pres != null || vd != null) releaseHardware()
        else { web?.destroy(); web = null }
    }

    companion object {
        private const val TOUCH_HELPER = """
(function(){
  if (window.__wpTouch) return;
  window.__wpTouch = function(kind, x, y) {
    var tgt = (kind === 'down') ? (document.elementFromPoint(x, y) || document.body) : (window.__wpT || document.body);
    if (kind === 'down') window.__wpT = tgt;
    var up = (kind === 'up');
    var base = {bubbles:true, cancelable:true, composed:true, view:window, clientX:x, clientY:y, screenX:x, screenY:y};
    if (kind === 'click') {
      try { tgt.dispatchEvent(new MouseEvent('click', Object.assign({button:0}, base))); if (tgt.focus) tgt.focus(); } catch(e) {}
      return;
    }
    var n = {down:['pointerdown','mousedown','touchstart'], move:['pointermove','mousemove','touchmove'], up:['pointerup','mouseup','touchend']}[kind];
    var b = up ? 0 : 1;
    try { tgt.dispatchEvent(new PointerEvent(n[0], Object.assign({pointerId:1, pointerType:'touch', isPrimary:true, button:0, buttons:b, width:1, height:1}, base))); } catch(e) {}
    try { tgt.dispatchEvent(new MouseEvent(n[1], Object.assign({button:0, buttons:b}, base))); } catch(e) {}
    try {
      var t = new Touch({identifier:1, target:tgt, clientX:x, clientY:y, screenX:x, screenY:y, pageX:x + window.scrollX, pageY:y + window.scrollY});
      var l = up ? [] : [t];
      tgt.dispatchEvent(new TouchEvent(n[2], {bubbles:true, cancelable:true, composed:true, view:window, touches:l, targetTouches:l, changedTouches:[t]}));
    } catch(e) {}
  };
})();
"""
    }
}
