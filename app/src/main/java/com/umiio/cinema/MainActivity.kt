package com.umiio.cinema

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var idleContainer: LinearLayout
    private lateinit var clockText: TextView
    private lateinit var ipUrlText: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var overlayToast: TextView
    private lateinit var cursorView: CursorOverlayView

    private var player: ExoPlayer? = null
    private var server: RemoteServer? = null
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var currentMode = "idle"
    private var currentUrl = ""
    @Volatile private var detectedStreamUrl = ""
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var customWebView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var cursorX = 640f
    private var cursorY = 360f

    private val adKeywords = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "an.yandex.ru", "yandex.ru/ads", "adfox.ru", "adriver.ru",
        "mgid.com", "marketgid", "adskeeper", "betweendigital",
        "popads", "propellerads", "exoclick", "juicyads", "trafficjunky",
        "vast", "vpaid", "adsystem", "adnxs.com", "taboola", "outbrain"
    )

    private val clockRunnable = object : Runnable {
        override fun run() {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            clockText.text = fmt.format(Date())
            handler.postDelayed(this, 10_000)
        }
    }

    private val hideToastRunnable = Runnable {
        overlayToast.visibility = View.GONE
    }

    private val hideCursorRunnable = Runnable {
        cursorView.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemUI()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        rootContainer = findViewById(R.id.rootContainer)
        idleContainer = findViewById(R.id.idleContainer)
        clockText = findViewById(R.id.clockText)
        ipUrlText = findViewById(R.id.ipUrlText)
        qrImageView = findViewById(R.id.qrImageView)
        playerView = findViewById(R.id.playerView)
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        overlayToast = findViewById(R.id.overlayToast)

        cursorView = CursorOverlayView(this)
        rootContainer.addView(
            cursorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setupWebView()
        setupPlayer()
        setupNetworkAndQr()

        handler.post(clockRunnable)
    }

    private fun setupNetworkAndQr() {
        val ip = getLocalIpAddress()
        val url = "http://$ip:8080"
        ipUrlText.text = url
        QrUtils.generateQr(url, 460)?.let { qrImageView.setImageBitmap(it) }

        try {
            server = RemoteServer(this, 8080).apply { start() }
        } catch (e: Exception) {
            ipUrlText.text = "Ошибка порта 8080: ${e.message}"
        }
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    showToast("Ошибка потока: ${error.message}")
                }
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val lower = reqUrl.lowercase(Locale.ROOT)

                if ((lower.contains(".m3u8") || lower.contains(".mp4")) &&
                    !lower.contains("ads") &&
                    !lower.contains("banner") &&
                    !lower.contains("preroll")
                ) {
                    detectedStreamUrl = reqUrl
                }

                if (adKeywords.any { lower.contains(it) }) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customWebView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customWebView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customWebView?.let { fullscreenContainer.removeView(it) }
                customWebView = null
                customViewCallback?.onCustomViewHidden()
                fullscreenContainer.visibility = View.GONE
                if (currentMode == "web") {
                    webView.visibility = View.VISIBLE
                }
            }
        }
    }

    fun openMedia(rawUrl: String, mode: String) {
        var url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) {
                "https://$url"
            } else {
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8")
            }
        }

        currentUrl = url
        detectedStreamUrl = ""
        exitWebFullscreenIfNeeded()

        val lower = url.lowercase(Locale.ROOT)
        val isDirectStream = lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".mkv") ||
                lower.contains(".webm") ||
                lower.contains(".ts")

        val resolvedMode = when (mode) {
            "native" -> "native"
            "web" -> "web"
            else -> if (isDirectStream) "native" else "web"
        }

        if (resolvedMode == "native") {
            currentMode = "native"
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.visibility = View.GONE
            idleContainer.visibility = View.GONE
            playerView.visibility = View.VISIBLE

            player?.apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
            showToast("Запуск в нативном плеере")
        } else {
            currentMode = "web"
            player?.stop()
            playerView.visibility = View.GONE
            idleContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(url)
            showToast("Открываю в браузере (AdBlock вкл)")
        }
    }

    fun handleControl(action: String, value: String) {
        when (action) {
            "toggle" -> {
                if (currentMode == "native") {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                } else if (currentMode == "web") {
                    webView.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}})();",
                        null
                    )
                }
            }
            "play" -> {
                if (currentMode == "native") player?.play()
                else if (currentMode == "web") webView.evaluateJavascript("document.querySelector('video')?.play()", null)
            }
            "pause" -> {
                if (currentMode == "native") player?.pause()
                else if (currentMode == "web") webView.evaluateJavascript("document.querySelector('video')?.pause()", null)
            }
            "seekRel" -> {
                val deltaSec = value.toLongOrNull() ?: 0L
                if (currentMode == "native") {
                    player?.let {
                        val target = (it.currentPosition + deltaSec * 1000L).coerceAtLeast(0L)
                        it.seekTo(target)
                    }
                } else if (currentMode == "web") {
                    webView.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.currentTime=Math.max(0,v.currentTime+($deltaSec));}})();",
                        null
                    )
                }
                showToast(if (deltaSec > 0) "+${deltaSec}с" else "${deltaSec}с")
            }
            "seekTo" -> {
                val posMs = value.toLongOrNull() ?: return
                if (currentMode == "native") {
                    player?.seekTo(posMs)
                } else if (currentMode == "web") {
                    val posSec = posMs / 1000.0
                    webView.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.currentTime=$posSec;}})();",
                        null
                    )
                }
            }
            "volUp" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                showToast("Громкость: ${(cur * 100) / max}%")
            }
            "volDown" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                showToast("Громкость: ${(cur * 100) / max}%")
            }
            "mute" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                showToast("Звук переключен")
            }
            "resize" -> {
                if (currentMode == "native") {
                    currentResizeMode = if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                        showToast("Масштаб: Заполнение экрана")
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        showToast("Масштаб: Оригинал")
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    playerView.resizeMode = currentResizeMode
                } else if (currentMode == "web") {
                    webView.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){if(v.requestFullscreen)v.requestFullscreen();else if(v.webkitEnterFullscreen)v.webkitEnterFullscreen();}})();",
                        null
                    )
                }
            }
            "injectStream" -> {
                if (detectedStreamUrl.isNotEmpty()) {
                    openMedia(detectedStreamUrl, "native")
                }
            }
            "back" -> {
                if (customWebView != null) {
                    exitWebFullscreenIfNeeded()
                } else if (currentMode == "web" && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    goHomeIdle()
                }
            }
            "home" -> {
                goHomeIdle()
            }
            "settings" -> {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    fun handleMouse(action: String, dx: Float, dy: Float, text: String) {
        val w = rootContainer.width.takeIf { it > 0 }?.toFloat() ?: 1280f
        val h = rootContainer.height.takeIf { it > 0 }?.toFloat() ?: 720f

        when (action) {
            "move" -> {
                cursorX = (cursorX + dx).coerceIn(0f, w)
                cursorY = (cursorY + dy).coerceIn(0f, h)
                cursorView.updatePosition(cursorX, cursorY)
                revealCursorTemporarily()
            }
            "click" -> {
                revealCursorTemporarily()
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
                val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
                window.decorView.dispatchTouchEvent(down)
                window.decorView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
            "scroll" -> {
                if (currentMode == "web") {
                    webView.scrollBy(0, dy.toInt())
                }
            }
            "text" -> {
                if (currentMode == "web" && text.isNotEmpty()) {
                    val escaped = JSONObject.quote(text)
                    val js = """
                        (function() {
                            var el = document.activeElement;
                            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                                if (el.isContentEditable) {
                                    el.innerText = $escaped;
                                } else {
                                    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
                                    if (nativeSetter && el.tagName === 'INPUT') {
                                        nativeSetter.call(el, $escaped);
                                    } else {
                                        el.value = $escaped;
                                    }
                                }
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                el.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                    showToast("Текст отправлен")
                }
            }
        }
    }

    private fun revealCursorTemporarily() {
        cursorView.visibility = View.VISIBLE
        handler.removeCallbacks(hideCursorRunnable)
        handler.postDelayed(hideCursorRunnable, 4500)
    }

    private fun exitWebFullscreenIfNeeded() {
        if (customWebView != null) {
            fullscreenContainer.removeAllViews()
            customWebView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            fullscreenContainer.visibility = View.GONE
        }
    }

    private fun goHomeIdle() {
        exitWebFullscreenIfNeeded()
        player?.stop()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        playerView.visibility = View.GONE
        webView.visibility = View.GONE
        idleContainer.visibility = View.VISIBLE
        currentMode = "idle"
        currentUrl = ""
        detectedStreamUrl = ""
        setupNetworkAndQr()
    }

    fun getStatusJson(): String {
        val obj = JSONObject()
        obj.put("mode", currentMode)
        obj.put("url", currentUrl)
        obj.put("detectedStream", detectedStreamUrl)

        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        obj.put("volume", curVol)
        obj.put("maxVolume", maxVol)

        if (currentMode == "native") {
            var isPlaying = false
            var pos = 0L
            var dur = 0L
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread {
                player?.let {
                    isPlaying = it.isPlaying
                    pos = it.currentPosition.coerceAtLeast(0L)
                    dur = it.duration.coerceAtLeast(0L)
                }
                latch.countDown()
            }
            try {
                latch.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
            obj.put("playing", isPlaying)
            obj.put("position", pos)
            obj.put("duration", dur)
        } else {
            obj.put("playing", currentMode == "web")
            obj.put("position", 0)
            obj.put("duration", 0)
        }
        return obj.toString()
    }

    private fun showToast(msg: String) {
        overlayToast.text = msg
        overlayToast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToastRunnable)
        handler.postDelayed(hideToastRunnable, 2200)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        @Suppress("DEPRECATION")
        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiMgr?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        }
        return "192.168.1.100"
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        player?.release()
        webView.destroy()
        super.onDestroy()
    }

    inner class CursorOverlayView(context: Context) : View(context) {
        private var cx = 640f
        private var cy = 360f
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E50914")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val path = Path()

        init {
            visibility = GONE
            isClickable = false
            isFocusable = false
        }

        fun updatePosition(x: Float, y: Float) {
            cx = x
            cy = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx, cy + 34f)
            path.lineTo(cx + 10f, cy + 25f)
            path.lineTo(cx + 22f, cy + 25f)
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }
}
