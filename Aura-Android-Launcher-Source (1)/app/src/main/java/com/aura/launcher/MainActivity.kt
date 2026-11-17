package com.aura.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_READ_CALENDAR = 1001
        private const val ICON_SIZE_PX = 96
    }

    private lateinit var webView: WebView
    private val auraEntryUrl = "https://ais-pre-jd7xmhrwywshavy6m2tnrs-576801925631.us-west2.run.app/"
    private val officialOriginUri: Uri = Uri.parse(auraEntryUrl)

    private val iconMemoryCache = ConcurrentHashMap<String, String>()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var photoPickerMultipleLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var photoPickerSingleLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var genericFilePickerLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Photo Picker oficial para múltiplas fotos (Fundo)
        photoPickerMultipleLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(24)
        ) { uris ->
            val cb = filePathCallback
            filePathCallback = null
            if (uris.isNullOrEmpty()) {
                cb?.onReceiveValue(null)
            } else {
                cb?.onReceiveValue(uris.toTypedArray())
            }
        }

        photoPickerSingleLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            val cb = filePathCallback
            filePathCallback = null
            if (uri == null) {
                cb?.onReceiveValue(null)
            } else {
                cb?.onReceiveValue(arrayOf(uri))
            }
        }

        // Seletor genérico para CSV (Samsung Health) ou áudio local
        genericFilePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
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
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#15120F"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            addJavascriptInterface(AuraNativeBridgeImpl(), "AuraNativeBridge")

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this@MainActivity.webView, true)

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
                            if (allowMultiple) {
                                photoPickerMultipleLauncher.launch(req)
                            } else {
                                photoPickerSingleLauncher.launch(req)
                            }
                            true
                        } else {
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            if (allowMultiple) {
                                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                            genericFilePickerLauncher.launch(intent)
                            true
                        }
                    } catch (_: Exception) {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (isOfficialOrigin(uri)) {
                        return false
                    }
                    openExternally(uri)
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame != true) return
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
                              <p>Verifique sua rede para carregar a interface da Aura pela primeira vez.</p>
                              <button onclick="window.location.href='${auraEntryUrl}'">Tentar novamente</button>
                            </div>
                          </body>
                        </html>
                    """.trimIndent()
                    view?.loadDataWithBaseURL(auraEntryUrl, offlineHtml, "text/html", "UTF-8", null)
                }
            }
        }

        setContentView(webView)
        webView.loadUrl(auraEntryUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    dispatchHomeToWeb()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            dispatchHomeToWeb()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_CALENDAR) {
            webView.post {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('aura-calendar-permission'))",
                    null
                )
            }
        }
    }

    private fun dispatchHomeToWeb() {
        webView.post {
            webView.evaluateJavascript(
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
        val expectedScheme = officialOriginUri.scheme ?: return false
        val expectedHost = officialOriginUri.host ?: return false
        val actualScheme = uri.scheme ?: return false
        val actualHost = uri.host ?: return false
        return actualScheme.equals(expectedScheme, ignoreCase = true) &&
            actualHost.equals(expectedHost, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(officialOriginUri)
    }

    private fun requireOfficialOrigin(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return isOfficialOrigin(webView.url?.let { Uri.parse(it) })
        }
        var allowed = false
        val latch = CountDownLatch(1)
        runOnUiThread {
            allowed = try {
                isOfficialOrigin(webView.url?.let { Uri.parse(it) })
            } catch (_: Exception) {
                false
            }
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return allowed
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
        } catch (_: Exception) {
            // Sem app correspondente no aparelho
        }
    }

    private fun hasReadCalendar(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showCalendarRationaleAndRequest() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
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
        }
    }

    private fun showDndPermissionExplanation() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
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
                    } catch (_: Exception) {
                        // ignore
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun getAppIconBase64Cached(pkg: String, pm: PackageManager): String? {
        iconMemoryCache[pkg]?.let { return it }

        val iconDir = File(cacheDir, "app_icons").apply { mkdirs() }
        val safeName = pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cacheFile = File(iconDir, "$safeName.b64")
        if (cacheFile.exists()) {
            val cached = try {
                cacheFile.readText()
            } catch (_: Exception) {
                ""
            }
            if (cached.startsWith("data:image/png;base64,")) {
                iconMemoryCache[pkg] = cached
                return cached
            }
        }

        return try {
            val drawable: Drawable = pm.getApplicationIcon(pkg)
            val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
            drawable.draw(canvas)

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            bitmap.recycle()

            val dataUrl = "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            iconMemoryCache[pkg] = dataUrl
            try {
                cacheFile.writeText(dataUrl)
            } catch (_: Exception) {
                // ignore disk write error
            }
            dataUrl
        } catch (_: Exception) {
            null
        }
    }

    inner class AuraNativeBridgeImpl {

        @JavascriptInterface
        fun isLauncherMode(): Boolean = requireOfficialOrigin()

        @JavascriptInterface
        fun tick() {
            if (!requireOfficialOrigin()) return
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Exception) {
                // ignore vibration error
            }
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            if (!requireOfficialOrigin()) return "[]"
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }

            val arr = JSONArray()
            val seen = HashSet<String>()
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) continue
                val cat = when {
                    pkg.contains("spotify") || pkg.contains("music") || pkg.contains("videos") -> "musica"
                    pkg.contains("kindle") || pkg.contains("books") || pkg.contains("calm") || pkg.contains("headspace") -> "leitura_mindset"
                    pkg.contains("health") || pkg.contains("shealth") || pkg.contains("fitness") || pkg.contains("strava") -> "saude"
                    else -> "essenciais"
                }
                val iconB64 = getAppIconBase64Cached(pkg, pm)
                arr.put(JSONObject().apply {
                    put("packageName", pkg)
                    put("label", label)
                    put("category", cat)
                    if (!iconB64.isNullOrEmpty()) {
                        put("icon", iconB64)
                    }
                })
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun launchPackage(pkg: String): Boolean {
            if (!requireOfficialOrigin()) return false
            return try {
                val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                true
            } catch (_: Exception) {
                false
            }
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
                    CalendarContract.Instances.ALL_DAY
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

                    while (cursor.moveToNext()) {
                        val allDay = if (idxAllDay >= 0) cursor.getInt(idxAllDay) == 1 else false
                        if (allDay) continue
                        val startMs = if (idxBegin >= 0) cursor.getLong(idxBegin) else 0L
                        val endMs = if (idxEnd >= 0) cursor.getLong(idxEnd) else startMs + 45 * 60_000L
                        val title = (if (idxTitle >= 0) cursor.getString(idxTitle) else null)?.trim().orEmpty()
                        if (title.isEmpty()) continue

                        arr.put(JSONObject().apply {
                            put("id", if (idxId >= 0) "cal-${cursor.getLong(idxId)}-$startMs" else "cal-$startMs")
                            put("title", title)
                            put("startMs", startMs)
                            put("endMs", endMs)
                            put("location", (if (idxLoc >= 0) cursor.getString(idxLoc) else null)?.trim().orEmpty())
                        })
                    }
                }
            } catch (_: Exception) {
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
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            return nm.isNotificationPolicyAccessGranted
        }

        @JavascriptInterface
        fun setDoNotDisturb(enabled: Boolean): Boolean {
            if (!requireOfficialOrigin()) return false
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            if (!nm.isNotificationPolicyAccessGranted) {
                showDndPermissionExplanation()
                return false
            }
            nm.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            return true
        }

        @JavascriptInterface
        fun getNextSystemAlarm(): String {
            if (!requireOfficialOrigin()) return ""
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return ""
            val next = am.nextAlarmClock ?: return ""
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            return JSONObject().apply {
                put("triggerAtMs", next.triggerTime)
                put("timeLabel", fmt.format(Date(next.triggerTime)))
            }.toString()
        }

        @JavascriptInterface
        fun getRecentMediaAndBooks(): String {
            if (!requireOfficialOrigin()) return "{}"
            return JSONObject().apply {
                AuraNotificationListener.lastMediaJson?.let { put("media", it) }
                AuraNotificationListener.lastBookJson?.let { put("book", it) }
            }.toString()
        }

        @JavascriptInterface
        fun getHealthSummary(): String {
            if (!requireOfficialOrigin()) return "{}"
            return JSONObject().toString()
        }
    }
}
