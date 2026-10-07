package com.webwallpaper.app

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.*
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pickVideo = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            textZoom = 100          // previews must match what the wallpaper / browser shows
            minimumFontSize = 1
            minimumLogicalFontSize = 1
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request == null) null else LocalServer.intercept(this@MainActivity, request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val accept = p?.acceptTypes?.joinToString(",") ?: ""
                pickVideo = accept.contains("video")
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (pickVideo) "video/*" else "*/*"
                }
                return try { startActivityForResult(i, 1); true } catch (e: Exception) {
                    fileCb = null; cb?.onReceiveValue(null); false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://${Store.HOST}/assets/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val cb = fileCb ?: return
        fileCb = null
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) { cb.onReceiveValue(null); return }
        Thread {
            try {
                var name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (!name.contains('.')) name += if (pickVideo) ".mp4" else ".html"
                val dst = File(Store.filesDir(this), "${System.currentTimeMillis()}_$name")
                contentResolver.openInputStream(uri)!!.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                val url = Store.fileToUrl(dst)
                runOnUiThread {
                    web.evaluateJavascript("window.__pendingNative='$url';", null)
                    cb.onReceiveValue(arrayOf(uri))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not import file", Toast.LENGTH_SHORT).show()
                    cb.onReceiveValue(null)
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        var n = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i) ?: n
            }
        }
        return n
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.nativeBack && window.nativeBack()") { r ->
            if (r != "true") finish()
        }
    }

    private fun applyTheme(dark: Boolean) {
        window.statusBarColor = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
        val f = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility =
            if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    inner class Bridge {
        @JavascriptInterface fun getState(): String = Store.settingsJson(this@MainActivity)

        @JavascriptInterface fun saveSettings(json: String) {
            Store.prefs(this@MainActivity).edit().putString("settings", json).apply()
        }

        /** Range-capable loopback URL for a copied wallpaper file ("" if not applicable) – used for <video> previews. */
        @JavascriptInterface fun mediaUrl(url: String): String = try { MediaServer.urlFor(this@MainActivity, url) ?: "" } catch (e: Exception) { "" }

        @JavascriptInterface fun setTheme(dark: Boolean) { runOnUiThread { applyTheme(dark) } }

        @JavascriptInterface fun clearCache() {
            val p = Store.prefs(this@MainActivity)
            val keep = listOf("home_src", "lock_src").mapNotNull { Store.urlToFile(this@MainActivity, p.getString(it, null))?.name }
            Store.filesDir(this@MainActivity).listFiles()?.forEach { if (it.name !in keep) it.delete() }
            runOnUiThread { web.clearCache(true) }
        }

        @JavascriptInterface fun applyWallpaper(type: String, url: String, target: String, settings: String): String {
            val e = Store.prefs(this@MainActivity).edit()
            e.putString("settings", settings)
            val home = target == "Home Screen" || target == "Both"
            val lock = target == "Lock Screen" || target == "Both"
            if (home) e.putString("home_type", type).putString("home_src", url)
            if (lock) e.putString("lock_type", type).putString("lock_src", url)
            e.putLong("rev", System.currentTimeMillis())
            e.apply()

            val wm = WallpaperManager.getInstance(this@MainActivity)
            val homeActive = wm.wallpaperInfo?.packageName == packageName
            val lockActive = if (Build.VERSION.SDK_INT >= 34)
                wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.packageName == packageName else homeActive
            val needSet = (home && !homeActive) || (lock && !lockActive)
            if (!needSet) return "Applied"
            runOnUiThread {
                val comp = ComponentName(this@MainActivity, LiveWallpaperService::class.java)
                try {
                    startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp))
                } catch (ex: Exception) {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                }
            }
            return "Tap \"Set wallpaper\" to finish"
        }
    }
}
