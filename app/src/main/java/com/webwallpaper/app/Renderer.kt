package com.webwallpaper.app

import android.view.MotionEvent

interface Renderer {
    fun start()
    fun setRunning(run: Boolean)
    fun setSettings(s: Settings)
    fun setTilt(x: Float, y: Float)
    fun touch(e: MotionEvent)
    fun stop()
}
