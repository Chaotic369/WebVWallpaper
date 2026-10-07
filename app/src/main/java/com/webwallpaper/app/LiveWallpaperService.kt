package com.webwallpaper.app

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WpEngine()

    inner class WpEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
        private val ctx: Context = this@LiveWallpaperService
        private val prefs = Store.prefs(ctx)
        private var settings = Settings.parse(Store.settingsJson(ctx))
        private var renderer: Renderer? = null
        private var holder: SurfaceHolder? = null
        private var w = 0
        private var h = 0
        private var visible = false
        private var batPct = 100
        private var charging = false
        private var sensorOn = false
        private var lastTouch = 0L
        private var bx = 0f
        private var by = 0f
        private var hasBase = false
        private var gx = 0f
        private var gy = 0f
        private val ui = Handler(Looper.getMainLooper())
        private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        private val batReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { readBattery(i); updateRunning() }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
            readBattery(ctx.registerReceiver(batReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try { ctx.unregisterReceiver(batReceiver) } catch (e: Exception) { }
            sensors(false)
            stopRenderer()
            super.onDestroy()
        }

        override fun onSurfaceCreated(sh: SurfaceHolder) { super.onSurfaceCreated(sh); holder = sh }

        override fun onSurfaceChanged(sh: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(sh, format, width, height)
            holder = sh; w = width; h = height
            loadContent()
        }

        override fun onSurfaceDestroyed(sh: SurfaceHolder) {
            stopRenderer(); holder = null
            super.onSurfaceDestroyed(sh)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            updateRunning()
        }

        override fun onTouchEvent(event: MotionEvent) {
            lastTouch = SystemClock.uptimeMillis()
            if (!settings.touch) renderer?.touch(event)   // pass-through OFF = wallpaper receives touches
            super.onTouchEvent(event)
        }

        // Some launchers only forward taps as commands instead of raw touch events
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && !settings.touch && SystemClock.uptimeMillis() - lastTouch > 600) {
                val r = renderer
                if (r != null) {
                    val t = SystemClock.uptimeMillis()
                    val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                    r.touch(d); d.recycle()
                    // release a moment later so the page sees a real tap (down -> up with a gap)
                    ui.postDelayed({
                        val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                        renderer?.touch(u); u.recycle()
                    }, 60)
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            when {
                key == "settings" -> {
                    settings = Settings.parse(Store.settingsJson(ctx))
                    renderer?.setSettings(settings)
                    updateRunning()
                }
                key == "rev" -> loadContent()
            }
        }

        private fun flavor(): String =
            if (Build.VERSION.SDK_INT >= 34) {
                val f = wallpaperFlags
                if ((f and WallpaperManager.FLAG_LOCK) != 0 && (f and WallpaperManager.FLAG_SYSTEM) == 0) "lock" else "home"
            } else "home"

        private fun loadContent() {
            stopRenderer()
            val hld = holder ?: return
            if (w <= 0 || h <= 0) return
            val mine = flavor()
            val other = if (mine == "home") "lock" else "home"
            var type = prefs.getString("${mine}_type", null)
            var src = prefs.getString("${mine}_src", null)
            if (src.isNullOrEmpty()) {
                type = prefs.getString("${other}_type", null)
                src = prefs.getString("${other}_src", null)
            }
            if (src.isNullOrEmpty()) { drawBlack(); return }

            val r: Renderer? = if (type == "video") {
                val f = Store.urlToFile(ctx, src)
                if (f == null) null else VideoRenderer(hld, f.absolutePath)
            } else WebRenderer(ctx, hld, src, w, h)
            if (r == null) { drawBlack(); return }
            renderer = r
            r.setSettings(settings)
            r.start()
            updateRunning()
        }

        private fun drawBlack() {
            val hld = holder ?: return
            try {
                val c = hld.lockCanvas() ?: return
                c.drawColor(Color.BLACK)
                hld.unlockCanvasAndPost(c)
            } catch (e: Exception) { }
        }

        private fun stopRenderer() { renderer?.stop(); renderer = null; sensors(false) }

        private fun readBattery(i: Intent?) {
            if (i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batPct = level * 100 / scale
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }

        private fun updateRunning() {
            val r = renderer ?: return
            val batteryPaused = settings.bat && !charging && batPct <= settings.batLevel
            val run = holder != null && (visible || !settings.pause) && !batteryPaused
            r.setRunning(run)
            sensors(run && settings.gyro)
        }

        private fun sensors(on: Boolean) {
            if (on == sensorOn) return
            sensorOn = on
            if (on) {
                hasBase = false
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            } else sm.unregisterListener(this)
        }

        override fun onSensorChanged(e: SensorEvent) {
            gx = gx * 0.9f + e.values[0] * 0.1f
            gy = gy * 0.9f + e.values[1] * 0.1f
            if (!hasBase) { bx = e.values[0]; by = e.values[1]; gx = bx; gy = by; hasBase = true }
            val x = ((bx - gx) / 4f).coerceIn(-1f, 1f)
            val y = ((gy - by) / 4f).coerceIn(-1f, 1f)
            renderer?.setTilt(x, y)
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
