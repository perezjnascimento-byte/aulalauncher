package com.aura.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_READ_CALENDAR = 1001
        private const val REQ_ACTIVITY_RECOGNITION = 1002
        private const val ICON_SIZE_PX = 96
        private const val CRASH_PREFS = "aura_crash_guard"
        private const val CRASH_KEY = "last_crash_trace"
        private const val CRASH_TIME_KEY = "last_crash_time"
        private const val USER_PREFS_NAME = "aura_user_prefs"
        private const val ASSET_APP_HOST = "appassets.androidplatform.net"
        private const val MAX_HTTP_BODY_BYTES = 1024 * 1024

        private val BUILTIN_FEED_HOSTS = setOf(
            "api.open-meteo.com",
            "geocoding-api.open-meteo.com",
            "openlibrary.org",
            "covers.openlibrary.org",
            "itunes.apple.com",
            "pt.wikipedia.org",
            "en.wikipedia.org",
            "economia.awesomeapi.com.br",
            "api.frankfurter.app",
            "news.google.com",
            "g1.globo.com",
            "ge.globo.com",
            "agenciabrasil.ebc.com.br",
            "tecnoblog.net",
            "canaltech.com.br",
            "www.theenemy.com.br",
            "www.youtube.com",
            "youtube.com"
        )
    }

    private var webView: WebView? = null
    private var rootContainer: FrameLayout? = null
    private var splashOverlayView: ViewGroup? = null
    private var splashDismissed: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpExecutor = Executors.newFixedThreadPool(4)
    private val allowedExtraFeedHosts: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private var bundledWebAppActive: Boolean = false
    private var auraEntryUrl: String = "https://ais-pre-jd7xmhrwywshavy6m2tnrs-576801925631.us-west2.run.app/"
    private var officialOriginUri: Uri = Uri.parse(auraEntryUrl)
    private var assetLoader: WebViewAssetLoader? = null

    @Volatile
    private var currentMainFrameUrl: String = ""

    @Volatile
    private var inOfflineFallback: Boolean = false

    @Volatile
    private var cachedInstalledAppsJson: String? = null

    @Volatile
    private var pendingShortcutTarget: String? = null

    private val iconMemoryCache = object : LruCache<String, String>(140) {}
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var photoPickerMultipleLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var photoPickerSingleLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var genericFilePickerLauncher: ActivityResultLauncher<Intent>? = null

    private var sensorManager: SensorManager? = null
    private var stepSensorListener: SensorEventListener? = null
    private var stepSensorRegistered: Boolean = false
    private val meetingUrlRegex = Regex(
        """https://(meet\.google\.com|(?:[a-z0-9-]+\.)?zoom\.us|teams\.microsoft\.com|meet\.jit\.si)/[^\s"'<>]+""",
        RegexOption.IGNORE_CASE
    )

    private fun hasBundledWebAssets(): Boolean {
        return try {
            val rootAssets = assets.list("") ?: return false
            rootAssets.contains("index.html")
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashShield()
        super.onCreate(null)
        pendingShortcutTarget = extractShortcutTarget(intent)

        bundledWebAppActive = hasBundledWebAssets()
        auraEntryUrl = if (bundledWebAppActive) {
            "https://appassets.androidplatform.net/index.html"
        } else {
            "https://ais-pre-jd7xmhrwywshavy6m2tnrs-576801925631.us-west2.run.app/"
        }
        officialOriginUri = Uri.parse(auraEntryUrl)
        assetLoader = if (bundledWebAppActive) {
            WebViewAssetLoader.Builder()
                .setDomain(ASSET_APP_HOST)
                .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
                .build()
        } else {
            null
        }

        try {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
        } catch (_: Throwable) {
            // Mantém compatibilidade mesmo em temas personalizados One UI
        }

        try {
            registerActivityLaunchers()
        } catch (t: Throwable) {
            showNativeRecoveryScreen(
                title = "Falha ao registrar seletores",
                subtitle = "Toque em recarregar para reiniciar a tela.",
                detail = "${t.javaClass.simpleName}: ${t.message}"
            )
            return
        }

        val previousCrash = consumeRecentCrashTrace()
        if (previousCrash != null) {
            showNativeRecoveryScreen(
                title = "A Aura se protegeu de um fechamento",
                subtitle = "Toque em recarregar para abrir a interface novamente. Se persistir, copie este detalhe técnico.",
                detail = previousCrash
            )
            return
        }

        mountWebViewSafely()

        try {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = webView
                    if (current != null && current.canGoBack()) {
                        current.goBack()
                    } else {
                        dispatchHomeToWeb()
                    }
                }
            })
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun installCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(CRASH_KEY, "${throwable.javaClass.simpleName}: ${throwable.message}\n${sw.toString().take(1800)}")
                    .putLong(CRASH_TIME_KEY, System.currentTimeMillis())
                    .commit()
            } catch (_: Throwable) {
                // ignore
            }
            if (thread == Looper.getMainLooper().thread) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun consumeRecentCrashTrace(): String? {
        return try {
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val trace = prefs.getString(CRASH_KEY, null)
            val at = prefs.getLong(CRASH_TIME_KEY, 0L)
            if (!trace.isNullOrEmpty()) {
                prefs.edit().remove(CRASH_KEY).remove(CRASH_TIME_KEY).apply()
                if (System.currentTimeMillis() - at < 2 * 60_000L) {
                    return trace
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun registerActivityLaunchers() {
        photoPickerMultipleLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(24)
        ) { uris ->
            val cb = filePathCallback
            filePathCallback = null
            try {
                if (uris.isNullOrEmpty()) cb?.onReceiveValue(null)
                else cb?.onReceiveValue(uris.toTypedArray())
            } catch (_: Throwable) {
                // ignore
            }
        }

        photoPickerSingleLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            val cb = filePathCallback
            filePathCallback = null
            try {
                if (uri == null) cb?.onReceiveValue(null)
                else cb?.onReceiveValue(arrayOf(uri))
            } catch (_: Throwable) {
                // ignore
            }
        }

        genericFilePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            try {
                if (result.resultCode != RESULT_OK || result.data == null) {
                    cb?.onReceiveValue(null)
                    return@registerForActivityResult
                }
                val data = result.data!!
                val collected = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { collected.add(it) }
                    }
                }
                if (collected.isEmpty()) {
                    data.data?.let { collected.add(it) }
                }
                cb?.onReceiveValue(if (collected.isEmpty()) null else collected.toTypedArray())
            } catch (_: Throwable) {
                cb?.onReceiveValue(null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun mountWebViewSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val webPkg = WebView.getCurrentWebViewPackage()
                if (webPkg == null) {
                    showNativeRecoveryScreen(
                        title = "Android System WebView indisponível",
                        subtitle = "Ative ou atualize o Android System WebView na Play Store para carregar a interface da Aura.",
                        detail = "WebView.getCurrentWebViewPackage() retornou null."
                    )
                    return
                }
            }

            destroyWebViewSafely()
            inOfflineFallback = false
            currentMainFrameUrl = auraEntryUrl

            val wv = WebView(this).apply {
                setBackgroundColor(Color.parseColor("#15120F"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                val ua = settings.userAgentString.orEmpty()
                if (ua.contains("; wv)")) {
                    settings.userAgentString = ua.replace("; wv)", ")")
                }
                addJavascriptInterface(AuraNativeBridgeImpl(), "AuraNativeBridge")

                try {
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(this, true)
                } catch (_: Throwable) {
                    // ignore
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = filePathCallback

                        val acceptTypes = fileChooserParams?.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
                        val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                        return try {
                            if (acceptTypes.contains("image")) {
                                val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                val launcher = if (allowMultiple) photoPickerMultipleLauncher else photoPickerSingleLauncher
                                if (launcher == null) {
                                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                                    this@MainActivity.filePathCallback = null
                                    return false
                                }
                                launcher.launch(req)
                                true
                            } else {
                                val launcher = genericFilePickerLauncher
                                if (launcher == null) {
                                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                                    this@MainActivity.filePathCallback = null
                                    return false
                                }
                                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                                if (allowMultiple) {
                                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                                launcher.launch(intent)
                                true
                            }
                        } catch (_: Throwable) {
                            this@MainActivity.filePathCallback?.onReceiveValue(null)
                            this@MainActivity.filePathCallback = null
                            false
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val loader = assetLoader ?: return super.shouldInterceptRequest(view, request)
                        val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                        if (uri.host.equals(ASSET_APP_HOST, ignoreCase = true)) {
                            val assetResp = loader.shouldInterceptRequest(uri)
                            if (assetResp != null) return assetResp
                            if (request.isForMainFrame) {
                                return loader.shouldInterceptRequest(
                                    Uri.parse("https://${ASSET_APP_HOST}/index.html")
                                )
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (!url.isNullOrEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            currentMainFrameUrl = url
                            inOfflineFallback = false
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!url.isNullOrEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            currentMainFrameUrl = url
                            try {
                                CookieManager.getInstance().flush()
                            } catch (_: Throwable) {
                                // ignore
                            }
                            if (isOfficialOrigin(Uri.parse(url))) {
                                dispatchPendingShortcutIfReady()
                            }
                            mainHandler.postDelayed({ fadeOutNativeSplash() }, 1800L)
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        if (!url.isNullOrEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            currentMainFrameUrl = url
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()

                        if (scheme == "about" || scheme == "data" || scheme == "blob") {
                            return false
                        }

                        if (isOfficialOrigin(uri)) {
                            inOfflineFallback = false
                            currentMainFrameUrl = uri.toString()
                            return false
                        }

                        if (isTrustedAuthOrigin(uri)) {
                            inOfflineFallback = false
                            return false
                        }

                        // Esquemas nativos (tel:, sms:, mailto:, whatsapp:, spotify:, intent:, etc.)
                        if (scheme != "http" && scheme != "https") {
                            openExternally(uri)
                            return true
                        }

                        // Links externos HTTP/HTTPS: só sai da Aura se o usuário clicou no quadro principal
                        if (request.isForMainFrame) {
                            if (request.hasGesture()) {
                                openExternally(uri)
                                return true
                            }
                            showRedirectBlockedCard(uri.toString())
                            return true
                        }

                        return false
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame != true) return
                        if (bundledWebAppActive) return
                        inOfflineFallback = true
                        fadeOutNativeSplash()
                        showOfflineFallbackCard(view)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mountWebViewSafely()
                            }
                        }
                        return true
                    }
                }
            }

            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#15120F"))
            }
            container.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            val splash = buildNativeSplashOverlay()
            splashDismissed = false
            splashOverlayView = splash
            container.addView(
                splash,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            rootContainer = container
            webView = wv
            setContentView(container)
            wv.loadUrl(auraEntryUrl)
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            showNativeRecoveryScreen(
                title = "Não foi possível iniciar o WebView",
                subtitle = "Verifique se o Android System WebView está ativo no seu Galaxy S26 e tente novamente.",
                detail = "${t.javaClass.simpleName}: ${t.message}\n${sw.toString().take(1200)}"
            )
        }
    }

    private fun buildNativeSplashOverlay(): ViewGroup {
        fun dp(v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#15120F"))
            isClickable = true
            isFocusable = true
        }

        val ringIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }
        val ringLp = LinearLayout.LayoutParams(dp(92), dp(92))
        overlay.addView(ringIcon, ringLp)

        val titleView = TextView(this).apply {
            text = "Aura"
            textSize = 28f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(Color.argb(235, 0xEF, 0xE8, 0xDF))
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val titleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        titleLp.topMargin = dp(8)
        overlay.addView(titleView, titleLp)

        val subView = TextView(this).apply {
            text = "Seu espaço em calma"
            textSize = 14f
            setTextColor(Color.argb(180, 0xE9, 0xA0, 0x6B))
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val subLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        subLp.topMargin = dp(4)
        overlay.addView(subView, subLp)

        ringIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420L).start()
        titleView.animate().alpha(1f).setStartDelay(120L).setDuration(380L).start()
        subView.animate().alpha(1f).setStartDelay(240L).setDuration(380L).start()
        return overlay
    }

    private fun fadeOutNativeSplash() {
        if (splashDismissed) return
        splashDismissed = true
        val overlay = splashOverlayView ?: return
        overlay.animate()
            .alpha(0f)
            .setDuration(340L)
            .withEndAction {
                overlay.visibility = View.GONE
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                splashOverlayView = null
            }
            .start()
    }

    private fun showOfflineFallbackCard(view: WebView?) {
        val offlineHtml = """
            <!doctype html>
            <html lang="pt-BR">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <style>
                  body {
                    margin: 0;
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    background: #15120F;
                    color: rgba(239, 232, 223, 0.92);
                    font-family: sans-serif;
                    padding: 24px;
                    text-align: center;
                  }
                  .card {
                    max-width: 320px;
                    padding: 28px 24px;
                    border-radius: 28px;
                    background: rgba(239, 232, 223, 0.06);
                    border: 1px solid rgba(239, 232, 223, 0.08);
                  }
                  .ring {
                    width: 32px;
                    height: 32px;
                    margin: 0 auto 16px;
                    border-radius: 999px;
                    border: 1.5px solid rgba(233, 160, 107, 0.7);
                  }
                  p { font-size: 15px; line-height: 1.5; color: rgba(239, 232, 223, 0.68); margin: 8px 0 20px; }
                  button {
                    border: 1px solid rgba(233, 160, 107, 0.45);
                    background: rgba(239, 232, 223, 0.08);
                    color: rgba(239, 232, 223, 0.92);
                    border-radius: 999px;
                    padding: 11px 22px;
                    font-size: 14px;
                  }
                </style>
              </head>
              <body>
                <div class="card">
                  <div class="ring"></div>
                  <strong>Aura aguardando conexão</strong>
                  <p>Conecte-se à internet para carregar a interface da Aura pela primeira vez.</p>
                  <button onclick="window.location.href='${auraEntryUrl}'">Tentar novamente</button>
                </div>
              </body>
            </html>
        """.trimIndent()
        view?.loadDataWithBaseURL(auraEntryUrl, offlineHtml, "text/html", "UTF-8", null)
    }

    private fun showRedirectBlockedCard(targetUrl: String) {
        inOfflineFallback = true
        val safeTarget = targetUrl.replace('"', ' ').replace('<', ' ').replace('>', ' ')
        val blockedHost = try {
            Uri.parse(targetUrl).host.orEmpty().replace("<", "").replace(">", "")
        } catch (_: Throwable) {
            ""
        }
        val html = """
            <!doctype html>
            <html lang="pt-BR">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <style>
                  body {
                    margin: 0;
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    background: #15120F;
                    color: rgba(239, 232, 223, 0.92);
                    font-family: sans-serif;
                    padding: 24px;
                    text-align: center;
                  }
                  .card {
                    max-width: 320px;
                    padding: 28px 24px;
                    border-radius: 28px;
                    background: rgba(239, 232, 223, 0.06);
                    border: 1px solid rgba(239, 232, 223, 0.08);
                  }
                  p { font-size: 14px; line-height: 1.5; color: rgba(239, 232, 223, 0.68); margin: 8px 0 18px; word-break: break-word; }
                  .actions { display: flex; flex-direction: column; gap: 10px; }
                  button, a.btn {
                    display: block;
                    text-decoration: none;
                    border: 1px solid rgba(233, 160, 107, 0.45);
                    background: rgba(239, 232, 223, 0.08);
                    color: rgba(239, 232, 223, 0.92);
                    border-radius: 999px;
                    padding: 11px 22px;
                    font-size: 14px;
                  }
                  a.btn {
                    border-color: rgba(239, 232, 223, 0.16);
                    color: rgba(239, 232, 223, 0.72);
                  }
                </style>
              </head>
              <body>
                <div class="card">
                  <strong>Redirecionamento detectado</strong>
                  <p>O endereço oficial redirecionou para fora da origem da Aura (${blockedHost}).</p>
                  <div class="actions">
                    <button onclick="window.location.href='${auraEntryUrl}'">Voltar para a Aura</button>
                    <a class="btn" href="${safeTarget}">Abrir no navegador</a>
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()
        webView?.loadDataWithBaseURL(auraEntryUrl, html, "text/html", "UTF-8", null)
    }

    private fun showNativeRecoveryScreen(title: String, subtitle: String, detail: String) {
        try {
            fun dp(v: Int): Int = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                resources.displayMetrics
            ).toInt()

            val bgMain = Color.rgb(0x15, 0x12, 0x0F)
            val bgCard = Color.rgb(0x1F, 0x1A, 0x16)
            val textPrimary = Color.argb(235, 0xEF, 0xE8, 0xDF)
            val textSecondary = Color.argb(163, 0xEF, 0xE8, 0xDF)
            val textMuted = Color.argb(133, 0xEF, 0xE8, 0xDF)
            val strokeSoft = Color.argb(41, 0xEF, 0xE8, 0xDF)
            val btnFill = Color.argb(24, 0xEF, 0xE8, 0xDF)
            val amberStroke = Color.argb(115, 0xE9, 0xA0, 0x6B)

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(bgMain)
                setPadding(dp(24), dp(48), dp(24), dp(32))
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(24), dp(22), dp(22))
                background = GradientDrawable().apply {
                    setColor(bgCard)
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(1), strokeSoft)
                }
            }

            val titleView = TextView(this).apply {
                text = title
                textSize = 20f
                setTypeface(Typeface.SERIF, Typeface.NORMAL)
                setTextColor(textPrimary)
            }

            val subView = TextView(this).apply {
                text = subtitle
                textSize = 14f
                setTextColor(textSecondary)
                setPadding(0, dp(10), 0, dp(16))
            }

            val reloadBtn = Button(this).apply {
                text = "Recarregar Aura"
                isAllCaps = false
                setTextColor(textPrimary)
                background = GradientDrawable().apply {
                    setColor(btnFill)
                    cornerRadius = dp(99).toFloat()
                    setStroke(dp(1), amberStroke)
                }
                setOnClickListener {
                    mountWebViewSafely()
                }
            }

            val launcherBtn = Button(this).apply {
                text = "Trocar app de início do S26"
                isAllCaps = false
                setTextColor(textSecondary)
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(99).toFloat()
                    setStroke(dp(1), strokeSoft)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(10)
                layoutParams = lp
                setOnClickListener {
                    openHomePickerSettings()
                }
            }

            val detailScroll = ScrollView(this).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(140)
                )
                lp.topMargin = dp(16)
                layoutParams = lp
                addView(TextView(this@MainActivity).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(textMuted)
                })
            }

            card.addView(titleView)
            card.addView(subView)
            card.addView(reloadBtn)
            card.addView(launcherBtn)
            card.addView(detailScroll)
            root.addView(card)
            setContentView(root)
        } catch (_: Throwable) {
            // fallback mínimo garantido
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shortcutTarget = extractShortcutTarget(intent)
        if (shortcutTarget != null) {
            pendingShortcutTarget = shortcutTarget
            if (requireOfficialOrigin()) {
                dispatchPendingShortcutIfReady()
            }
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            dispatchHomeToWeb()
        }
    }

    override fun onResume() {
        super.onResume()
        cachedInstalledAppsJson = null
        registerStepSensorIfPermitted()
        try {
            webView?.onResume()
            webView?.resumeTimers()
        } catch (_: Throwable) {
            // ignore
        }
    }

    override fun onPause() {
        unregisterStepSensor()
        try {
            webView?.onPause()
            webView?.pauseTimers()
        } catch (_: Throwable) {
            // ignore
        }
        super.onPause()
    }

    override fun onDestroy() {
        unregisterStepSensor()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            httpExecutor.shutdownNow()
        } catch (_: Throwable) {
            // ignore
        }
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        splashOverlayView = null
        rootContainer = null
        destroyWebViewSafely()
        super.onDestroy()
    }

    private fun destroyWebViewSafely() {
        try {
            webView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
        } catch (_: Throwable) {
            // ignore
        } finally {
            webView = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_CALENDAR) {
            webView?.post {
                webView?.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('aura-calendar-permission'))",
                    null
                )
            }
        } else if (requestCode == REQ_ACTIVITY_RECOGNITION) {
            if (hasActivityPermissionInternal()) {
                registerStepSensorIfPermitted()
            }
            webView?.post {
                webView?.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('aura-activity-permission'))",
                    null
                )
            }
        }
    }

    private fun extractShortcutTarget(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) return "settings"
        val data = intent.data ?: return null
        if (data.scheme?.equals("aura", ignoreCase = true) == true) {
            return when (data.host?.lowercase(Locale.US)) {
                "settings" -> "settings"
                "fundo" -> "fundo"
                else -> null
            }
        }
        return null
    }

    private fun dispatchPendingShortcutIfReady() {
        val target = pendingShortcutTarget ?: return
        pendingShortcutTarget = null
        val eventName = if (target == "fundo") "aura-open-fundo" else "aura-open-settings"
        webView?.post {
            webView?.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('${eventName}'))",
                null
            )
        }
    }

    private fun dispatchHomeToWeb() {
        webView?.post {
            webView?.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('aura-home-pressed'))",
                null
            )
        }
    }

    private fun effectivePort(uri: Uri): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme?.lowercase(Locale.US)) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun isOfficialOrigin(uri: Uri?): Boolean {
        if (uri == null) return false
        val actualScheme = uri.scheme ?: return false
        val actualHost = uri.host ?: return false
        if (bundledWebAppActive &&
            actualScheme.equals("https", ignoreCase = true) &&
            actualHost.equals(ASSET_APP_HOST, ignoreCase = true)
        ) {
            return true
        }
        val expectedScheme = officialOriginUri.scheme ?: return false
        val expectedHost = officialOriginUri.host ?: return false
        return actualScheme.equals(expectedScheme, ignoreCase = true) &&
            actualHost.equals(expectedHost, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(officialOriginUri)
    }

    private fun isTrustedAuthOrigin(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val officialHost = officialOriginUri.host?.lowercase(Locale.US).orEmpty()
        val devSibling = if (officialHost.startsWith("ais-pre-")) {
            officialHost.replaceFirst("ais-pre-", "ais-dev-")
        } else {
            ""
        }
        if (devSibling.isNotEmpty() && host == devSibling) return true
        return host == "google.com" ||
            host.endsWith(".google.com") ||
            host.contains(".google.") ||
            host.endsWith(".googleusercontent.com") ||
            host.endsWith(".gstatic.com") ||
            host.endsWith(".googleapis.com") ||
            host.endsWith(".youtube.com") ||
            host == "g.co" ||
            host == "goo.gl"
    }

    private fun requireOfficialOrigin(): Boolean {
        if (inOfflineFallback) return false
        return try {
            val urlToCheck = if (Looper.myLooper() == Looper.getMainLooper()) {
                webView?.url ?: currentMainFrameUrl
            } else {
                currentMainFrameUrl
            }
            if (urlToCheck.isEmpty()) return false
            isOfficialOrigin(Uri.parse(urlToCheck))
        } catch (_: Throwable) {
            false
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Throwable) {
            // Sem app correspondente no aparelho
        }
    }

    private fun openHomePickerSettings(): Boolean {
        return try {
            startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Throwable) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun hasReadCalendar(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun todayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun hasActivityPermissionInternal(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
    }

    private fun registerStepSensorIfPermitted() {
        if (stepSensorRegistered || !hasActivityPermissionInternal()) return
        try {
            val sm = sensorManager ?: (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            sensorManager = sm
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val values = event?.values ?: return
                    if (values.isEmpty()) return
                    val totalSinceBoot = values[0].toLong()
                    if (totalSinceBoot < 0L) return
                    try {
                        val dayKey = todayDateKey()
                        val baselineKey = "steps_baseline_" + dayKey
                        val todayKey = "steps_today_" + dayKey
                        val prefs = getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
                        val savedBaseline = prefs.getLong(baselineKey, -1L)
                        val effectiveBaseline = if (savedBaseline < 0L || totalSinceBoot < savedBaseline) {
                            prefs.edit().putLong(baselineKey, totalSinceBoot).apply()
                            totalSinceBoot
                        } else {
                            savedBaseline
                        }
                        val todaySteps = maxOf(0L, totalSinceBoot - effectiveBaseline).toInt()
                        prefs.edit().putInt(todayKey, todaySteps).apply()
                    } catch (_: Throwable) {
                        // ignore step persistence failure
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    // no-op
                }
            }
            val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            if (ok) {
                stepSensorListener = listener
                stepSensorRegistered = true
            }
        } catch (_: Throwable) {
            // no hardware step counter or permission denied
        }
    }

    private fun unregisterStepSensor() {
        if (!stepSensorRegistered) return
        try {
            val listener = stepSensorListener
            if (listener != null) {
                sensorManager?.unregisterListener(listener)
            }
        } catch (_: Throwable) {
            // ignore
        } finally {
            stepSensorListener = null
            stepSensorRegistered = false
        }
    }

    private fun extractMeetingUrl(location: String, description: String): String {
        val sources = listOf(location, description)
        for (src in sources) {
            if (src.isEmpty()) continue
            val found = meetingUrlRegex.find(src)?.value?.trimEnd('.', ',', ';', ')', ']')
            if (!found.isNullOrEmpty()) return found
        }
        return ""
    }

    private fun showCalendarRationaleAndRequest() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                AlertDialog.Builder(this)
                    .setTitle("Acesso à Agenda")
                    .setMessage(
                        "A Aura lê apenas os compromissos de hoje e de amanhã diretamente no seu aparelho para exibir na tela Hoje e manter sua linha do tempo atualizada."
                    )
                    .setPositiveButton("Permitir") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.READ_CALENDAR),
                            REQ_READ_CALENDAR
                        )
                    }
                    .setNegativeButton("Agora não", null)
                    .show()
            } catch (_: Throwable) {
                // ignore dialog error
            }
        }
    }

    private fun showDndPermissionExplanation() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                AlertDialog.Builder(this)
                    .setTitle("Permissão para Não Perturbe")
                    .setMessage(
                        "Para ativar e desativar o modo Não Perturbe pela tela Foco, ative o acesso para a Aura nas configurações de Notificações do sistema."
                    )
                    .setPositiveButton("Abrir configurações") { _, _ ->
                        try {
                            startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Throwable) {
                            // ignore
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun classifyApp(pkg: String, label: String, appInfo: ApplicationInfo?): String {
        val lowerPkg = pkg.lowercase(Locale.US)
        val lowerLabel = label.lowercase(Locale.US)

        if (lowerPkg.contains("spotify") ||
            lowerPkg.contains("music") ||
            lowerPkg.contains("youtube") ||
            lowerPkg.contains("deezer") ||
            lowerPkg.contains("tidal") ||
            lowerPkg.contains("soundcloud") ||
            lowerPkg.contains("shazam") ||
            lowerPkg.contains("netflix") ||
            lowerPkg.contains("primevideo") ||
            lowerPkg.contains("amazon.avod") ||
            lowerPkg.contains("disney") ||
            lowerPkg.contains("globoplay") ||
            lowerPkg.contains("stremio") ||
            lowerPkg.contains("plex") ||
            lowerPkg.contains("vlc") ||
            lowerPkg.contains("videos") ||
            lowerPkg.contains("mubi") ||
            lowerPkg.contains("podcast")
        ) {
            return "musica"
        }

        if (lowerPkg.contains("kindle") ||
            lowerPkg.contains("books") ||
            lowerPkg.contains("audible") ||
            lowerPkg.contains("kobo") ||
            lowerPkg.contains("scribd") ||
            lowerPkg.contains("calm") ||
            lowerPkg.contains("headspace") ||
            lowerPkg.contains("medit") ||
            lowerPkg.contains("forest") ||
            lowerPkg.contains("notion") ||
            lowerPkg.contains("obsidian") ||
            lowerPkg.contains("todoist") ||
            lowerPkg.contains("ticktick") ||
            lowerPkg.contains("duolingo") ||
            lowerPkg.contains("pocket") ||
            lowerPkg.contains("medium") ||
            lowerPkg.contains("bible")
        ) {
            return "leitura_mindset"
        }

        if (lowerPkg.contains("shealth") ||
            lowerPkg.contains("health") ||
            lowerPkg.contains("fitness") ||
            lowerPkg.contains("strava") ||
            lowerPkg.contains("wearable") ||
            lowerPkg.contains("watchmanager") ||
            lowerPkg.contains("garmin") ||
            lowerPkg.contains("fitbit") ||
            lowerPkg.contains("nike") ||
            lowerPkg.contains("adidas") ||
            lowerPkg.contains("gympass") ||
            lowerPkg.contains("wellhub") ||
            lowerPkg.contains("totalpass") ||
            lowerPkg.contains("smartfit") ||
            lowerPkg.contains("myfitnesspal")
        ) {
            return "saude"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO -> return "musica"
                ApplicationInfo.CATEGORY_NEWS -> return "leitura_mindset"
            }
        }

        if (lowerLabel.contains("música") || lowerLabel.contains("musica") || lowerLabel.contains("rádio")) {
            return "musica"
        }

        return "essenciais"
    }

    private fun getAppIconBase64Cached(pkg: String, pm: PackageManager): String? {
        iconMemoryCache.get(pkg)?.let { return it }

        val iconDir = try {
            File(cacheDir, "app_icons_v2").apply { mkdirs() }
        } catch (_: Throwable) {
            null
        }
        val safeName = pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cacheFile = iconDir?.let { File(it, "$safeName.b64") }

        if (cacheFile != null && cacheFile.exists()) {
            val cached = try {
                cacheFile.readText()
            } catch (_: Throwable) {
                ""
            }
            if (cached.startsWith("data:image/png;base64,")) {
                iconMemoryCache.put(pkg, cached)
                return cached
            }
        }

        var createdBitmap: Bitmap? = null
        return try {
            val drawable: Drawable = pm.getApplicationIcon(pkg)
            val finalBitmap: Bitmap
            if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                val src = drawable.bitmap
                if (src.width == ICON_SIZE_PX && src.height == ICON_SIZE_PX && src.config == Bitmap.Config.ARGB_8888) {
                    finalBitmap = src
                } else {
                    val scaled = Bitmap.createScaledBitmap(src, ICON_SIZE_PX, ICON_SIZE_PX, true)
                    if (scaled !== src) {
                        createdBitmap = scaled
                    }
                    finalBitmap = scaled
                }
            } else {
                val created = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
                createdBitmap = created
                val canvas = Canvas(created)
                drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
                drawable.draw(canvas)
                finalBitmap = created
            }

            val baos = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 88, baos)

            val dataUrl = "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            iconMemoryCache.put(pkg, dataUrl)
            try {
                cacheFile?.writeText(dataUrl)
            } catch (_: Throwable) {
                // ignore disk write error
            }
            dataUrl
        } catch (_: Throwable) {
            null
        } finally {
            try {
                if (createdBitmap != null && !createdBitmap.isRecycled) {
                    createdBitmap.recycle()
                }
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun triggerVibration(pattern: String) {
        val vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            if (!vibrator.hasVibrator()) return
            when (pattern) {
                "pouso" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
                "respiro" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 12, 65, 16),
                                intArrayOf(0, 90, 0, 130),
                                -1
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 12, 65, 16), -1)
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
        } catch (_: Throwable) {
            // ignore vibration error
        }
    }

    inner class AuraNativeBridgeImpl {

        @JavascriptInterface
        fun isLauncherMode(): Boolean = requireOfficialOrigin()

        @JavascriptInterface
        fun getAppVersion(): String {
            if (!requireOfficialOrigin()) return ""
            return try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
                info.versionName ?: "2.0"
            } catch (_: Throwable) {
                "2.0"
            }
        }

        @JavascriptInterface
        fun tick() {
            if (!requireOfficialOrigin()) return
            triggerVibration("tique")
        }

        @JavascriptInterface
        fun haptic(pattern: String) {
            if (!requireOfficialOrigin()) return
            triggerVibration(pattern)
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            if (!requireOfficialOrigin()) return "[]"
            cachedInstalledAppsJson?.let { return it }

            return try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }

                val arr = JSONArray()
                val seen = HashSet<String>()
                for (info in resolveInfos) {
                    try {
                        val actInfo = info.activityInfo ?: continue
                        val pkg = actInfo.packageName ?: continue
                        val activityName = actInfo.name ?: continue
                        if (pkg == packageName || !seen.add(pkg)) continue
                        val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                        if (label.isEmpty()) continue
                        val appInfo = actInfo.applicationInfo
                        val isSystemApp = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val cat = classifyApp(pkg, label, appInfo)
                        val rawCat = resolveRawAppCategory(appInfo)

                        arr.put(JSONObject().apply {
                            put("packageName", pkg)
                            put("activityName", activityName)
                            put("label", label)
                            put("category", cat)
                            put("rawCategory", rawCat)
                            put("isSystemApp", isSystemApp)
                        })
                    } catch (_: Throwable) {
                        // ignora entrada inválida sem interromper a listagem
                    }
                }
                val json = arr.toString()
                cachedInstalledAppsJson = json
                json
            } catch (_: Throwable) {
                "[]"
            }
        }

        @JavascriptInterface
        fun getAppIcon(pkg: String): String {
            if (!requireOfficialOrigin() || pkg.isBlank()) return ""
            return getAppIconBase64Cached(pkg.trim(), packageManager).orEmpty()
        }

        @JavascriptInterface
        fun getAppIconsBatch(packagesJson: String): String {
            if (!requireOfficialOrigin() || packagesJson.isBlank()) return "{}"
            val out = JSONObject()
            try {
                val pm = packageManager
                val arr = JSONArray(packagesJson)
                val limit = minOf(arr.length(), 24)
                for (i in 0 until limit) {
                    val pkg = arr.optString(i).trim()
                    if (pkg.isEmpty()) continue
                    val icon = getAppIconBase64Cached(pkg, pm)
                    if (!icon.isNullOrEmpty()) {
                        out.put(pkg, icon)
                    }
                }
            } catch (_: Throwable) {
                // ignore malformed batch
            }
            return out.toString()
        }

        @JavascriptInterface
        fun launchPackage(pkgOrComponent: String): Boolean {
            if (!requireOfficialOrigin() || pkgOrComponent.isBlank()) return false
            val pm = packageManager
            val trimmed = pkgOrComponent.trim()

            try {
                if (trimmed.contains("/")) {
                    val parts = trimmed.split("/", limit = 2)
                    val pkg = parts[0]
                    val cls = parts[1]
                    if (pkg.isNotEmpty() && cls.isNotEmpty()) {
                        val compIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            component = ComponentName(pkg, cls)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        }
                        if (compIntent.resolveActivity(pm) != null) {
                            startActivity(compIntent)
                            return true
                        }
                    }
                }

                val pkg = trimmed.substringBefore("/")
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    return true
                }

                val query = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
                val resolved = pm.queryIntentActivities(query, 0).firstOrNull()
                if (resolved?.activityInfo != null) {
                    val fallback = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    startActivity(fallback)
                    return true
                }
            } catch (_: Throwable) {
                return false
            }
            return false
        }

        @JavascriptInterface
        fun launchSystemAction(actionType: String): Boolean {
            if (!requireOfficialOrigin()) return false
            val candidates = when (actionType) {
                "phone" -> listOf(Intent(Intent.ACTION_DIAL))
                "messages" -> listOf(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                    Intent(Intent.ACTION_SENDTO, Uri.parse("sms:"))
                )
                "camera" -> listOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                "gallery" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY))
                "calendar" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
                "new_event" -> listOf(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI))
                "contacts" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS))
                "calculator" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR))
                "clock", "show_alarms" -> listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                "set_timer" -> listOf(Intent(AlarmClock.ACTION_SET_TIMER))
                "set_alarm" -> listOf(Intent(AlarmClock.ACTION_SET_ALARM))
                "browser" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER))
                "email" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL))
                "maps" -> listOf(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS),
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
                )
                "music" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC))
                "files" -> listOf(
                    packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles"),
                    packageManager.getLaunchIntentForPackage("com.google.android.apps.nbu.files"),
                    packageManager.getLaunchIntentForPackage("com.google.android.documentsui")
                )
                "settings" -> listOf(Intent(Settings.ACTION_SETTINGS))
                "home_picker" -> return openHomePickerSettings()
                else -> {
                    if (actionType == "open_map" || actionType.startsWith("open_map:")) {
                        val rawQuery = if (actionType.contains(":")) actionType.substringAfter(":") else ""
                        val enc = if (rawQuery.isBlank()) "" else URLEncoder.encode(rawQuery.trim(), "UTF-8")
                        listOf(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${enc}")))
                    } else {
                        emptyList()
                    }
                }
            }

            for (intent in candidates) {
                if (intent == null) continue
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return true
                } catch (_: Throwable) {
                    // tenta próximo candidato
                }
            }
            return false
        }

        @JavascriptInterface
        fun openLauncherSettings(): Boolean {
            if (!requireOfficialOrigin()) return false
            return openHomePickerSettings()
        }

        @JavascriptInterface
        fun hasCalendarPermission(): Boolean {
            if (!requireOfficialOrigin()) return false
            return hasReadCalendar()
        }

        @JavascriptInterface
        fun requestCalendarPermission() {
            if (!requireOfficialOrigin()) return
            if (!hasReadCalendar()) {
                showCalendarRationaleAndRequest()
            }
        }

        @JavascriptInterface
        fun getCalendarEvents(inicioMs: Long, fimMs: Long): String {
            if (!requireOfficialOrigin()) {
                return JSONObject().put("permissionGranted", false).put("events", JSONArray()).toString()
            }
            if (!hasReadCalendar()) {
                return JSONObject().put("permissionGranted", false).put("events", JSONArray()).toString()
            }

            val arr = JSONArray()
            try {
                val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(uriBuilder, inicioMs)
                ContentUris.appendId(uriBuilder, fimMs)

                val projection = arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DESCRIPTION
                )

                contentResolver.query(
                    uriBuilder.build(),
                    projection,
                    null,
                    null,
                    CalendarContract.Instances.BEGIN + " ASC"
                )?.use { cursor ->
                    val idxId = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                    val idxTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val idxBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val idxEnd = cursor.getColumnIndex(CalendarContract.Instances.END)
                    val idxLoc = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                    val idxAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                    val idxDesc = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)

                    while (cursor.moveToNext()) {
                        val allDay = if (idxAllDay >= 0) cursor.getInt(idxAllDay) == 1 else false
                        if (allDay) continue
                        val startMs = if (idxBegin >= 0) cursor.getLong(idxBegin) else 0L
                        val endMs = if (idxEnd >= 0) cursor.getLong(idxEnd) else startMs + 45 * 60_000L
                        val title = (if (idxTitle >= 0) cursor.getString(idxTitle) else null)?.trim().orEmpty()
                        if (title.isEmpty()) continue
                        val locStr = (if (idxLoc >= 0) cursor.getString(idxLoc) else null)?.trim().orEmpty()
                        val descStr = (if (idxDesc >= 0) cursor.getString(idxDesc) else null)?.trim().orEmpty()
                        val meetUrl = extractMeetingUrl(locStr, descStr)

                        arr.put(JSONObject().apply {
                            put("id", if (idxId >= 0) "cal-${cursor.getLong(idxId)}-$startMs" else "cal-$startMs")
                            put("title", title)
                            put("startMs", startMs)
                            put("endMs", endMs)
                            put("location", locStr)
                            if (meetUrl.isNotEmpty()) {
                                put("meetUrl", meetUrl)
                            }
                        })
                    }
                }
            } catch (_: Throwable) {
                // retorna lista vazia em erro de consulta
            }

            return JSONObject()
                .put("permissionGranted", true)
                .put("events", arr)
                .toString()
        }

        @JavascriptInterface
        fun hasDoNotDisturbPermission(): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
                nm.isNotificationPolicyAccessGranted
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun setDoNotDisturb(enabled: Boolean): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
                if (!nm.isNotificationPolicyAccessGranted) {
                    showDndPermissionExplanation()
                    return false
                }
                nm.setInterruptionFilter(
                    if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun getNextSystemAlarm(): String {
            if (!requireOfficialOrigin()) return ""
            return try {
                val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return ""
                val next = am.nextAlarmClock ?: return ""
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                JSONObject().apply {
                    put("triggerAtMs", next.triggerTime)
                    put("timeLabel", fmt.format(Date(next.triggerTime)))
                }.toString()
            } catch (_: Throwable) {
                ""
            }
        }

        @JavascriptInterface
        fun getRecentMediaAndBooks(): String {
            if (!requireOfficialOrigin()) return "{}"
            return try {
                JSONObject().apply {
                    AuraNotificationListener.lastMediaJson?.let { put("media", it) }
                    AuraNotificationListener.lastBookJson?.let { put("book", it) }
                }.toString()
            } catch (_: Throwable) {
                "{}"
            }
        }

        @JavascriptInterface
        fun getDeviceNotifications(): String {
            if (!requireOfficialOrigin()) return "[]"
            return AuraNotificationListener.getRecentNotificationsJson()
        }

        @JavascriptInterface
        fun isNotificationAccessGranted(): Boolean {
            if (!requireOfficialOrigin()) return false
            if (AuraNotificationListener.isListenerConnected) return true
            return try {
                val enabled = Settings.Secure.getString(
                    contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                val cn = ComponentName(this@MainActivity, AuraNotificationListener::class.java)
                val full = cn.flattenToString()
                val shortName = cn.flattenToShortString()
                enabled.split(":").any {
                    it.equals(full, ignoreCase = true) ||
                        it.equals(shortName, ignoreCase = true) ||
                        it.startsWith("$packageName/")
                }
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun openNotificationAccessSettings(): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun findActiveMediaController(): MediaController? {
            if (!isNotificationAccessGranted()) return null
            return try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
                val listener = ComponentName(this@MainActivity, AuraNotificationListener::class.java)
                val sessions = msm.getActiveSessions(listener)
                if (sessions.isEmpty()) return null
                sessions.firstOrNull { ctrl ->
                    val state = ctrl.playbackState?.state
                    state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                } ?: sessions.firstOrNull { ctrl ->
                    ctrl.playbackState?.state == PlaybackState.STATE_PAUSED ||
                        ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true
                } ?: sessions.firstOrNull()
            } catch (_: Throwable) {
                null
            }
        }

        @JavascriptInterface
        fun getMediaState(): String {
            if (!requireOfficialOrigin()) return "{}"
            if (!isNotificationAccessGranted()) return "{}"
            return try {
                val ctrl = findActiveMediaController() ?: return "{}"
                val meta = ctrl.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                val artist = (
                    meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                )?.trim().orEmpty()
                val durationMs = maxOf(0L, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                val pb = ctrl.playbackState
                val stateCode = pb?.state ?: PlaybackState.STATE_NONE
                val isPlaying = stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING
                var positionMs = maxOf(0L, pb?.position ?: 0L)
                if (pb != null && stateCode == PlaybackState.STATE_PLAYING && pb.lastPositionUpdateTime > 0L) {
                    val elapsed = maxOf(0L, SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime)
                    val speed = if (pb.playbackSpeed > 0f) pb.playbackSpeed else 1f
                    positionMs = maxOf(0L, positionMs + (elapsed * speed).toLong())
                }
                if (durationMs > 0L) {
                    positionMs = minOf(durationMs, positionMs)
                }
                val pkgName = ctrl.packageName.orEmpty()
                if (title.isEmpty() && pkgName.isEmpty()) return "{}"

                val out = JSONObject()
                out.put("title", title)
                out.put("artist", artist)
                out.put("isPlaying", isPlaying)
                out.put("positionMs", positionMs)
                out.put("durationMs", durationMs)
                out.put("packageName", pkgName)
                out.toString()
            } catch (_: Throwable) {
                "{}"
            }
        }

        @JavascriptInterface
        fun mediaControl(action: String): Boolean {
            if (!requireOfficialOrigin()) return false
            if (!isNotificationAccessGranted()) return false
            return try {
                val ctrl = findActiveMediaController() ?: return false
                val controls = ctrl.transportControls
                val clean = action.trim().lowercase(Locale.US)
                when {
                    clean == "play_pause" -> {
                        val state = ctrl.playbackState?.state
                        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                            controls.pause()
                        } else {
                            controls.play()
                        }
                        true
                    }
                    clean == "next" -> {
                        controls.skipToNext()
                        true
                    }
                    clean == "previous" -> {
                        controls.skipToPrevious()
                        true
                    }
                    clean == "pause" -> {
                        controls.pause()
                        true
                    }
                    clean == "play" -> {
                        controls.play()
                        true
                    }
                    clean.startsWith("seek:") -> {
                        val targetMs = clean.substringAfter("seek:").toLongOrNull() ?: return false
                        controls.seekTo(maxOf(0L, targetMs))
                        true
                    }
                    else -> false
                }
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun mediaSeek(posMs: Long): Boolean {
            if (!requireOfficialOrigin()) return false
            if (!isNotificationAccessGranted()) return false
            return try {
                val ctrl = findActiveMediaController() ?: return false
                ctrl.transportControls.seekTo(maxOf(0L, posMs))
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun openMap(query: String): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                val cleanQuery = query.trim()
                val encoded = if (cleanQuery.isEmpty()) "" else URLEncoder.encode(cleanQuery, "UTF-8")
                val mapIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=${encoded}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(mapIntent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun openAppInfo(targetPackage: String): Boolean {
            if (!requireOfficialOrigin() || targetPackage.isBlank()) return false
            return try {
                val infoIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", targetPackage.trim(), null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(infoIntent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun openPlayStore(targetPackage: String): Boolean {
            if (!requireOfficialOrigin() || targetPackage.isBlank()) return false
            val encodedPkg = URLEncoder.encode(targetPackage.trim(), "UTF-8")
            return try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${encodedPkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=${encodedPkg}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }

        @JavascriptInterface
        fun hasUsageStatsPermission(): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun openUsageAccessSettings(): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun getAppUsageStats(): String {
            if (!requireOfficialOrigin()) return "{}"
            if (!hasUsageStatsPermission()) return "{}"
            return try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return "{}"
                val endMs = System.currentTimeMillis()
                val startMs = endMs - 7L * 24L * 60L * 60L * 1000L
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs) ?: return "{}"
                val totalTimeMap = HashMap<String, Long>()
                val lastUsedMap = HashMap<String, Long>()
                for (entry in stats) {
                    val pkg = entry.packageName ?: continue
                    if (pkg == packageName) continue
                    val fg = entry.totalTimeInForeground
                    if (fg > 0L) {
                        totalTimeMap[pkg] = (totalTimeMap[pkg] ?: 0L) + fg
                    }
                    val prevLast = lastUsedMap[pkg] ?: 0L
                    if (entry.lastTimeUsed > prevLast) {
                        lastUsedMap[pkg] = entry.lastTimeUsed
                    }
                }
                val mostUsedArr = JSONArray()
                totalTimeMap.entries
                    .sortedByDescending { it.value }
                    .take(32)
                    .forEach { mostUsedArr.put(it.key) }

                val recentArr = JSONArray()
                lastUsedMap.entries
                    .filter { it.value > 0L }
                    .sortedByDescending { it.value }
                    .take(12)
                    .forEach { recentArr.put(it.key) }

                JSONObject()
                    .put("mostUsed", mostUsedArr)
                    .put("recent", recentArr)
                    .toString()
            } catch (_: Throwable) {
                "{}"
            }
        }

        @JavascriptInterface
        fun hasActivityPermission(): Boolean {
            if (!requireOfficialOrigin()) return false
            return hasActivityPermissionInternal()
        }

        @JavascriptInterface
        fun requestActivityPermission() {
            if (!requireOfficialOrigin()) return
            if (hasActivityPermissionInternal()) {
                registerStepSensorIfPermitted()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    try {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                            REQ_ACTIVITY_RECOGNITION
                        )
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
            }
        }

        @JavascriptInterface
        fun createAlarmAt(hour: Int, minute: Int, label: String): Boolean {
            if (!requireOfficialOrigin()) return false
            val safeHour = hour.coerceIn(0, 23)
            val safeMin = minute.coerceIn(0, 59)
            val cleanLabel = label.trim().ifEmpty { "Aura" }
            return try {
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, safeHour)
                    putExtra(AlarmClock.EXTRA_MINUTES, safeMin)
                    putExtra(AlarmClock.EXTRA_MESSAGE, cleanLabel)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(alarmIntent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun getHealthSummary(): String {
            if (!requireOfficialOrigin()) return "{}"
            return try {
                val out = JSONObject()
                if (hasActivityPermissionInternal()) {
                    val prefs = getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
                    val todayKey = "steps_today_" + todayDateKey()
                    if (prefs.contains(todayKey)) {
                        val steps = prefs.getInt(todayKey, -1)
                        if (steps >= 0) {
                            out.put("steps", steps)
                        }
                    }
                }
                out.toString()
            } catch (_: Throwable) {
                "{}"
            }
        }

        @JavascriptInterface
        fun getAppPref(key: String): String {
            if (!requireOfficialOrigin() || key.isBlank()) return ""
            return try {
                getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE).getString(key.trim(), "") ?: ""
            } catch (_: Throwable) {
                ""
            }
        }

        @JavascriptInterface
        fun setAppPref(key: String, value: String): Boolean {
            if (!requireOfficialOrigin() || key.isBlank()) return false
            return try {
                getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(key.trim(), value)
                    .apply()
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun removeAppPref(key: String): Boolean {
            if (!requireOfficialOrigin() || key.isBlank()) return false
            return try {
                getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .remove(key.trim())
                    .apply()
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun clearAllAppPrefs(): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun uninstallPackage(targetPackage: String): Boolean {
            if (!requireOfficialOrigin() || targetPackage.isBlank()) return false
            val cleanPkg = targetPackage.trim()
            if (cleanPkg == packageName) return false
            return try {
                cachedInstalledAppsJson = null
                val delIntent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", cleanPkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(delIntent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun isDefaultLauncher(): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                }
                res?.activityInfo?.packageName == packageName
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun isStandaloneApk(): Boolean {
            if (!requireOfficialOrigin()) return false
            return bundledWebAppActive
        }

        @JavascriptInterface
        fun notifyAppReady() {
            if (!requireOfficialOrigin()) return
            runOnUiThread {
                fadeOutNativeSplash()
            }
        }

        @JavascriptInterface
        fun addAllowedFeedDomain(hostOrUrl: String): Boolean {
            if (!requireOfficialOrigin() || hostOrUrl.isBlank()) return false
            return try {
                val trimmed = hostOrUrl.trim()
                val host = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                    Uri.parse(trimmed).host.orEmpty()
                } else {
                    trimmed.substringBefore('/').substringBefore(':')
                }.lowercase(Locale.US)
                if (!isPublicHostnameSafe(host)) return false
                if (allowedExtraFeedHosts.size >= 24 && !allowedExtraFeedHosts.contains(host)) {
                    return false
                }
                allowedExtraFeedHosts.add(host)
                true
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun httpGet(rawUrl: String): String {
            if (!requireOfficialOrigin()) {
                return JSONObject().put("ok", false).put("status", 403).toString()
            }
            return performAllowlistedHttpsGet(rawUrl)
        }

        @JavascriptInterface
        fun httpGetAsync(requestId: String, rawUrl: String) {
            if (requestId.isBlank()) return
            if (!requireOfficialOrigin()) {
                val denied = JSONObject().put("ok", false).put("status", 403).toString()
                dispatchHttpResult(requestId, denied)
                return
            }
            httpExecutor.execute {
                val resultJson = performAllowlistedHttpsGet(rawUrl)
                dispatchHttpResult(requestId, resultJson)
            }
        }
    }

    private fun dispatchHttpResult(requestId: String, payloadJson: String) {
        val safeId = JSONObject.quote(requestId)
        val safePayload = JSONObject.quote(payloadJson)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            webView?.evaluateJavascript(
                "window.__auraHttpResolve && window.__auraHttpResolve(${safeId}, ${safePayload})",
                null
            )
        }
    }

    private fun isPublicHostnameSafe(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase(Locale.US).trimEnd('.')
        if (host.isEmpty() || host.length > 253) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host.contains(':') || Regex("""^d+.d+.d+.d+$""").matches(host)) return false
        return try {
            val addrs = InetAddress.getAllByName(host)
            if (addrs.isEmpty()) return false
            addrs.none { addr ->
                addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isMulticastAddress
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isFeedHostAllowed(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase(Locale.US).trimEnd('.')
        if (host.isEmpty()) return false
        if (BUILTIN_FEED_HOSTS.contains(host)) return true
        return allowedExtraFeedHosts.contains(host)
    }

    private fun performAllowlistedHttpsGet(rawUrl: String): String {
        var currentUrl = rawUrl.trim()
        for (hop in 0..2) {
            var conn: HttpURLConnection? = null
            try {
                val parsed = URL(currentUrl)
                if (!parsed.protocol.equals("https", ignoreCase = true)) {
                    return JSONObject().put("ok", false).put("status", 400).toString()
                }
                if (!parsed.userInfo.isNullOrEmpty()) {
                    return JSONObject().put("ok", false).put("status", 400).toString()
                }
                val host = parsed.host.orEmpty().lowercase(Locale.US)
                if (!isFeedHostAllowed(host) || !isPublicHostnameSafe(host)) {
                    return JSONObject().put("ok", false).put("status", 403).toString()
                }

                conn = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "AuraLauncher/2.1 (Android)")
                    setRequestProperty("Accept", "application/json, application/rss+xml, application/atom+xml, application/xml, text/xml, text/html")
                }

                val status = conn.responseCode
                if (status in 301..308 && hop < 2) {
                    val location = conn.getHeaderField("Location").orEmpty().trim()
                    if (location.isNotEmpty()) {
                        val nextUrl = URL(parsed, location)
                        if (nextUrl.protocol.equals("https", ignoreCase = true)) {
                            currentUrl = nextUrl.toString()
                            continue
                        }
                    }
                    return JSONObject().put("ok", false).put("status", status).toString()
                }

                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                var body = ""
                if (stream != null) {
                    val bis = BufferedInputStream(stream)
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = bis.read(buf)
                        if (n <= 0) break
                        val allowed = minOf(n, MAX_HTTP_BODY_BYTES - total)
                        if (allowed > 0) {
                            baos.write(buf, 0, allowed)
                            total += allowed
                        }
                        if (total >= MAX_HTTP_BODY_BYTES) break
                    }
                    bis.close()
                    body = baos.toString("UTF-8")
                }

                return JSONObject()
                    .put("ok", status in 200..299)
                    .put("status", status)
                    .put("body", body)
                    .toString()
            } catch (_: Throwable) {
                return JSONObject().put("ok", false).put("status", 0).toString()
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
        return JSONObject().put("ok", false).put("status", 0).toString()
    }

    private fun resolveRawAppCategory(appInfo: ApplicationInfo?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appInfo == null) return ""
        return when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME -> "GAME"
            ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
            ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
            ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
            ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
            ApplicationInfo.CATEGORY_NEWS -> "NEWS"
            ApplicationInfo.CATEGORY_MAPS -> "MAPS"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
            else -> ""
        }
    }
}
