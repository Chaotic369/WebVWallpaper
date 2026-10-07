package com.webwallpaper.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

object Store {
    const val HOST = "appassets.local"
    const val DEFAULT_SETTINGS =
        "{\"fps\":\"Default\",\"scale\":\"Center Crop\",\"zoom\":1,\"pause\":true,\"bat\":true,\"batLevel\":20,\"gyro\":false,\"touch\":false}"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("wp", Context.MODE_PRIVATE)
    fun settingsJson(c: Context): String = prefs(c).getString("settings", DEFAULT_SETTINGS) ?: DEFAULT_SETTINGS
    fun filesDir(c: Context): File = File(c.filesDir, "wallpapers").apply { mkdirs() }

    fun urlToFile(c: Context, url: String?): File? {
        if (url == null) return null
        val u = Uri.parse(url)
        if (u.host != HOST) return null
        val p = u.path ?: return null
        if (!p.startsWith("/files/")) return null
        val base = filesDir(c)
        val f = File(base, p.removePrefix("/files/"))
        return if (f.canonicalPath.startsWith(base.canonicalPath) && f.exists()) f else null
    }

    fun fileToUrl(f: File): String = "https://$HOST/files/" + Uri.encode(f.name)
}

data class Settings(
    val fps: String = "Default",
    val scale: String = "Center Crop",
    val zoom: Float = 1f,
    val pause: Boolean = true,
    val bat: Boolean = true,
    val batLevel: Int = 20,
    val gyro: Boolean = false,
    val touch: Boolean = false
) {
    /** min ms between drawn frames for video (0 = every frame) */
    val videoIntervalMs: Long get() = when (fps) { "60 FPS" -> 16L; "30 FPS" -> 33L; else -> 0L }
    /** frame cap for web content in ms (0 = display refresh rate) */
    val webIntervalMs: Long get() = when (fps) { "30 FPS" -> 33L; else -> 0L }

    companion object {
        fun parse(json: String): Settings = try {
            val o = JSONObject(json)
            Settings(
                o.optString("fps", "Default"), o.optString("scale", "Center Crop"),
                o.optDouble("zoom", 1.0).toFloat().coerceAtLeast(0.1f),
                o.optBoolean("pause", true), o.optBoolean("bat", true), o.optInt("batLevel", 20),
                o.optBoolean("gyro", false), o.optBoolean("touch", false)
            )
        } catch (e: Exception) { Settings() }
    }
}

// Serves the bundled UI (assets path) and copied wallpaper files (files path, with Range support) to WebViews.
object LocalServer {
    fun intercept(ctx: Context, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != Store.HOST) return null
        val path = u.path ?: return null
        return try {
            when {
                path.startsWith("/assets/") -> {
                    val name = path.removePrefix("/assets/")
                    WebResourceResponse(mime(name), "utf-8", ctx.assets.open(name))
                }
                path.startsWith("/files/") -> file(ctx, req.url.toString(), req.requestHeaders?.get("Range"))
                else -> null
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun file(ctx: Context, url: String, range: String?): WebResourceResponse {
        val f = Store.urlToFile(ctx, url) ?: throw IllegalStateException("missing")
        val len = f.length()
        val type = mime(f.name)
        val h = mutableMapOf("Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*")
        if (range != null && range.startsWith("bytes=") && len > 0) {
            val p = range.removePrefix("bytes=").split("-")
            val start = p[0].toLongOrNull() ?: 0L
            val end = (p.getOrNull(1)?.toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            val fis = FileInputStream(f)
            fis.channel.position(start)
            h["Content-Range"] = "bytes $start-$end/$len"
            h["Content-Length"] = (end - start + 1).toString()
            return WebResourceResponse(type, null, 206, "Partial Content", h, LimitedStream(fis, end - start + 1))
        }
        h["Content-Length"] = len.toString()
        return WebResourceResponse(type, null, 200, "OK", h, FileInputStream(f))
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = inner.read(); if (r >= 0) left--; return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = inner.read(b, off, minOf(len.toLong(), left).toInt()); if (r > 0) left -= r; return r
        }
        override fun close() { inner.close() }
    }
}

/**
 * Tiny loopback HTTP server that serves the copied wallpaper files with proper Range support, so <video> previews
 * (which need seeking/looping) work reliably inside the app's WebView.
 */
object MediaServer {
    private var server: ServerSocket? = null
    private val token = UUID.randomUUID().toString().replace("-", "").take(16)

    @Synchronized private fun port(ctx: Context): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        val app = ctx.applicationContext
        Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { try { handle(app, c) } catch (e: Exception) { } }.apply { isDaemon = true }.start()
                } catch (e: Exception) { break }
            }
        }.apply { isDaemon = true }.start()
        return s.localPort
    }

    /** http://127.0.0.1 URL for a wallpaper file URL, or null if the url isn't one of ours. */
    fun urlFor(ctx: Context, url: String?): String? {
        val f = Store.urlToFile(ctx, url) ?: return null
        return "http://127.0.0.1:${port(ctx)}/$token/${Uri.encode(f.name)}"
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(ctx: Context, sock: Socket) = sock.use {
        sock.soTimeout = 20000
        val ins = sock.getInputStream()
        val out = BufferedOutputStream(sock.getOutputStream())
        val req = readLine(ins) ?: return@use
        var range: String? = null
        while (true) {
            val l = readLine(ins) ?: break
            if (l.isEmpty()) break
            if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim()
        }
        val parts = req.split(' ')
        if (parts.size < 2) return@use
        val head = parts[0] == "HEAD"
        val path = parts[1].substringBefore('?')
        val prefix = "/$token/"
        val base = Store.filesDir(ctx)
        val f = if (path.startsWith(prefix)) File(base, Uri.decode(path.removePrefix(prefix))) else null
        if (f == null || !f.exists() || !f.canonicalPath.startsWith(base.canonicalPath)) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return@use
        }
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val p = range.removePrefix("bytes=").split("-")
            val a = p[0].trim().toLongOrNull()
            val b = p.getOrNull(1)?.trim()?.toLongOrNull()
            if (a == null && b != null) { start = (len - b).coerceAtLeast(0); }
            else if (a != null) { start = a; if (b != null) end = b.coerceAtMost(len - 1) }
            partial = true
            if (start > end || start >= len) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return@use
            }
        }
        val n = end - start + 1
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ").append(LocalServer.mime(f.name)).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$len\r\n")
        sb.append("Content-Length: $n\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (!head) {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var left = n
                while (left > 0) {
                    val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    left -= r
                }
            }
        }
        out.flush()
    }
}
