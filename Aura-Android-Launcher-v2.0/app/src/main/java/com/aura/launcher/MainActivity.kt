package com.aura.launcher

import android.Manifest
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_READ_CALENDAR = 1001
        private const val REQ_ACTIVITY_RECOGNITION = 1002
        private const val CRASH_PREFS = "aura_crash_guard"
        private const val CRASH_KEY = "last_crash_trace"
        private const val CRASH_TIME_KEY = "last_crash_time"
        private const val USER_PREFS_NAME = "aura_user_prefs"
        private const val KEY_ONBOARDING_DONE = "aura_onboarding_done_v1"
    }

    private data class AppItem(
        val packageName: String,
        val activityName: String,
        val label: String,
        val category: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootContainer: FrameLayout? = null
    private var splashShownOnce = false
    private var currentScreen = "splash" // splash | onboarding | home | apps | settings

    private var cachedApps: List<AppItem> = emptyList()
    private var sensorManager: SensorManager? = null
    private var stepSensorListener: SensorEventListener? = null
    private var stepSensorRegistered: Boolean = false

    // Paleta oficial Aura
    private val bgMain = Color.parseColor("#15120F")
    private val bgCard = Color.parseColor("#1F1A16")
    private val bgCardElevated = Color.parseColor("#28211C")
    private val textPrimary = Color.argb(238, 0xEF, 0xE8, 0xDF)
    private val textSecondary = Color.argb(170, 0xEF, 0xE8, 0xDF)
    private val textMuted = Color.argb(120, 0xEF, 0xE8, 0xDF)
    private val strokeSoft = Color.argb(38, 0xEF, 0xE8, 0xDF)
    private val amberAccent = Color.parseColor("#E9A06B")
    private val amberStroke = Color.argb(130, 0xE9, 0xA0, 0x6B)
    private val amberFill = Color.argb(28, 0xE9, 0xA0, 0x6B)

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashShield()
        super.onCreate(null)

        try {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
        } catch (_: Throwable) {
            // Compatibilidade com One UI
        }

        val previousCrash = consumeRecentCrashTrace()
        if (previousCrash != null) {
            showNativeRecoveryScreen(
                title = "A Aura se protegeu de um fechamento",
                subtitle = "Toque em recarregar para abrir a interface nativa novamente.",
                detail = previousCrash
            )
            return
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(bgMain)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = container
        setContentView(container)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    "apps", "settings" -> showHomeScreen()
                    "onboarding" -> {
                        if (isOnboardingDone()) showHomeScreen()
                    }
                    else -> {
                        // Na tela inicial do launcher, não fecha o app
                    }
                }
            }
        })

        val shortcutTarget = extractShortcutTarget(intent)
        if (!splashShownOnce) {
            splashShownOnce = true
            showSplashScreen {
                routeAfterSplash(shortcutTarget)
            }
        } else {
            routeAfterSplash(shortcutTarget)
        }
    }

    private fun routeAfterSplash(shortcutTarget: String?) {
        when {
            shortcutTarget == "settings" || shortcutTarget == "fundo" -> showSettingsScreen()
            !isOnboardingDone() -> showOnboardingScreen()
            else -> showHomeScreen()
        }
    }

    // ---------------------------------------------------------
    // 1. TELA DE SPLASH (LOGO DA AURA NATIVO)
    // ---------------------------------------------------------
    private fun showSplashScreen(onComplete: () -> Unit) {
        currentScreen = "splash"
        val container = rootContainer ?: return
        container.removeAllViews()

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgMain)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val logoView = AuraRingLogoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }

        val brandTitle = TextView(this).apply {
            text = "Aura"
            textSize = 30f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(6))
        }

        val brandSub = TextView(this).apply {
            text = "PRESENÇA E FOCO"
            textSize = 11f
            letterSpacing = 0.22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }

        wrapper.addView(logoView)
        wrapper.addView(brandTitle)
        wrapper.addView(brandSub)

        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 650L
            fillAfter = true
        }
        wrapper.startAnimation(fadeIn)
        container.addView(wrapper)

        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                triggerVibration("pouso")
                onComplete()
            }
        }, 1650L)
    }

    // ---------------------------------------------------------
    // 2. TELA DE BOAS-VINDAS / INSTALAÇÃO NO GALAXY S26
    // ---------------------------------------------------------
    private fun showOnboardingScreen() {
        currentScreen = "onboarding"
        val container = rootContainer ?: return
        container.removeAllViews()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgMain)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(54), dp(24), dp(36))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
        }

        val miniLogo = AuraRingLogoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }

        val badgeText = TextView(this).apply {
            text = "  BEM-VINDO À AURA"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(amberAccent)
        }
        headerRow.addView(miniLogo)
        headerRow.addView(badgeText)

        val title = TextView(this).apply {
            text = "Sua tela inicial,\nmais calma e intencional."
            textSize = 26f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        val desc = TextView(this).apply {
            text = "A Aura roda 100% nativa no seu Galaxy S26 — sem navegador, sem distrações e com acesso rápido aos seus apps, agenda e modo foco."
            textSize = 14f
            setTextColor(textSecondary)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, dp(24))
        }

        // Card de instalação / configuração rápida
        val setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBox(bgCard, 24, strokeSoft)
        }

        val isDefault = isDefaultLauncher()
        val step1Title = TextView(this).apply {
            text = if (isDefault) "✓ 1. Aura definida como Tela Inicial" else "1. Definir como Tela Inicial (Launcher)"
            textSize = 15f
            setTextColor(if (isDefault) amberAccent else textPrimary)
        }

        val step1Sub = TextView(this).apply {
            text = if (isDefault) {
                "Tudo pronto! Ao tocar no botão Início do S26, a Aura abrirá direto."
            } else {
                "Selecione a Aura como seu App de início padrão nas configurações do Galaxy S26."
            }
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        }

        val btnSetHome = makeActionButton(
            label = if (isDefault) "App de início configurado" else "Definir Aura como Tela Inicial",
            highlighted = !isDefault
        ) {
            triggerVibration("tique")
            openHomePickerSettings()
        }

        val divider1 = makeDivider()

        val hasCal = hasReadCalendar()
        val step2Title = TextView(this).apply {
            text = if (hasCal) "✓ 2. Agenda conectada" else "2. Agenda do dia (opcional)"
            textSize = 15f
            setTextColor(if (hasCal) amberAccent else textPrimary)
        }

        val step2Sub = TextView(this).apply {
            text = "Exibe seu próximo compromisso direto na tela inicial."
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        }

        val btnCal = makeActionButton(
            label = if (hasCal) "Agenda autorizada" else "Permitir acesso à Agenda",
            highlighted = false
        ) {
            triggerVibration("tique")
            if (!hasReadCalendar()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_CALENDAR),
                    REQ_READ_CALENDAR
                )
            }
        }

        setupCard.addView(step1Title)
        setupCard.addView(step1Sub)
        setupCard.addView(btnSetHome)
        setupCard.addView(divider1)
        setupCard.addView(step2Title)
        setupCard.addView(step2Sub)
        setupCard.addView(btnCal)

        val btnEnter = Button(this).apply {
            text = "Concluir e abrir a Aura"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor("#15120F"))
            background = GradientDrawable().apply {
                setColor(amberAccent)
                cornerRadius = dp(99).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            lp.topMargin = dp(24)
            layoutParams = lp
            setOnClickListener {
                triggerVibration("pouso")
                markOnboardingDone(true)
                showHomeScreen()
            }
        }

        root.addView(headerRow)
        root.addView(title)
        root.addView(desc)
        root.addView(setupCard)
        root.addView(btnEnter)

        scroll.addView(root)
        container.addView(scroll)
    }

    // ---------------------------------------------------------
    // 3. TELA INICIAL NATIVA DA AURA
    // ---------------------------------------------------------
    private fun showHomeScreen() {
        currentScreen = "home"
        val container = rootContainer ?: return
        container.removeAllViews()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgMain)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(54), dp(22), dp(36))
        }

        // Topo: Relógio + Data + Botão Ajustes
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val timeText = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 44f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
        }

        val dateStr = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
            .format(Date())
            .replaceFirstChar { it.uppercase() }
        val dateText = TextView(this).apply {
            text = dateStr
            textSize = 14f
            setTextColor(textSecondary)
        }

        clockCol.addView(timeText)
        clockCol.addView(dateText)

        val settingsBtn = TextView(this).apply {
            text = "Ajustes"
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBox(bgCard, 99, strokeSoft)
            setOnClickListener {
                triggerVibration("tique")
                showSettingsScreen()
            }
        }

        topBar.addView(clockCol)
        topBar.addView(settingsBtn)
        root.addView(topBar)

        // Banner caso ainda não seja o launcher padrão
        if (!isDefaultLauncher()) {
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedBox(bgCardElevated, 20, amberStroke)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(18)
                layoutParams = lp
                setOnClickListener {
                    openHomePickerSettings()
                }
            }
            banner.addView(TextView(this).apply {
                text = "Definir Aura como App de início no S26 →"
                textSize = 13f
                setTextColor(amberAccent)
            })
            root.addView(banner)
        }

        // Card de Hoje (Agenda + Próximo Alarme)
        val todayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBox(bgCard, 24, strokeSoft)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(20)
            layoutParams = lp
        }

        val todayLabel = TextView(this).apply {
            text = "HOJE"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(amberAccent)
        }

        val nextEventSummary = getNextCalendarEventSummary()
        val eventView = TextView(this).apply {
            text = nextEventSummary
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(0, dp(8), 0, dp(6))
        }

        val alarmSummary = getNextAlarmLabel()
        val alarmView = TextView(this).apply {
            text = alarmSummary
            textSize = 13f
            setTextColor(textSecondary)
        }

        todayCard.addView(todayLabel)
        todayCard.addView(eventView)
        todayCard.addView(alarmView)
        root.addView(todayCard)

        // Atalhos Essenciais Nativos (Telefone, Mensagens, Câmera, Agenda)
        val quickLabel = TextView(this).apply {
            text = "ESSENCIAIS"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(textMuted)
            setPadding(dp(4), dp(24), 0, dp(10))
        }
        root.addView(quickLabel)

        val quickActions = listOf(
            "Telefone" to { launchSystemIntent(Intent(Intent.ACTION_DIAL)) },
            "Mensagens" to {
                launchSystemIntent(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
                )
            },
            "Câmera" to { launchSystemIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
            "Agenda" to {
                launchSystemIntent(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
                )
            }
        )

        for ((label, action) in quickActions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(15), dp(18), dp(15))
                background = roundedBox(bgCard, 18, strokeSoft)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10)
                layoutParams = lp
                setOnClickListener {
                    triggerVibration("tique")
                    action()
                }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "↗"
                textSize = 15f
                setTextColor(textMuted)
            })
            root.addView(row)
        }

        // Botão para abrir a gaveta completa de apps instalados
        val allAppsBtn = makeActionButton(
            label = "Todos os aplicativos",
            highlighted = true
        ) {
            triggerVibration("tique")
            showAllAppsScreen()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        }
        root.addView(allAppsBtn)

        scroll.addView(root)
        container.addView(scroll)
    }

    // ---------------------------------------------------------
    // 4. GAVETA DE APLICATIVOS NATIVA (COM BUSCA)
    // ---------------------------------------------------------
    private fun showAllAppsScreen() {
        currentScreen = "apps"
        val container = rootContainer ?: return
        container.removeAllViews()

        val apps = loadInstalledApps()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            setPadding(dp(20), dp(50), dp(20), dp(20))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }

        val backBtn = TextView(this).apply {
            text = "← Início"
            textSize = 14f
            setTextColor(amberAccent)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = roundedBox(bgCard, 99, strokeSoft)
            setOnClickListener {
                triggerVibration("tique")
                showHomeScreen()
            }
        }

        val title = TextView(this).apply {
            text = "  Aplicativos (${apps.size})"
            textSize = 18f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
        }

        topRow.addView(backBtn)
        topRow.addView(title)
        root.addView(topRow)

        val searchInput = EditText(this).apply {
            hint = "Buscar aplicativo..."
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundedBox(bgCard, 16, strokeSoft)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(14)
            layoutParams = lp
        }
        root.addView(searchInput)

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listScroll.addView(listContainer)
        root.addView(listScroll)

        fun renderFiltered(query: String) {
            listContainer.removeAllViews()
            val q = query.trim().lowercase(Locale.getDefault())
            val filtered = if (q.isEmpty()) apps else apps.filter {
                it.label.lowercase(Locale.getDefault()).contains(q)
            }
            for (app in filtered) {
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(6)
                    layoutParams = lp
                    background = roundedBox(bgCard, 16, Color.TRANSPARENT)
                    setOnClickListener {
                        triggerVibration("tique")
                        launchAppItem(app)
                    }
                    setOnLongClickListener {
                        openAppDetails(app.packageName)
                        true
                    }
                }

                val iconView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                    } catch (_: Throwable) {
                        // fallback sem ícone
                    }
                }

                val nameView = TextView(this).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(textPrimary)
                    setPadding(dp(14), 0, 0, 0)
                }

                itemRow.addView(iconView)
                itemRow.addView(nameView)
                listContainer.addView(itemRow)
            }
        }

        renderFiltered("")

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderFiltered(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        container.addView(root)
    }

    // ---------------------------------------------------------
    // 5. TELA DE AJUSTES DA AURA
    // ---------------------------------------------------------
    private fun showSettingsScreen() {
        currentScreen = "settings"
        val container = rootContainer ?: return
        container.removeAllViews()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgMain)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(52), dp(22), dp(36))
        }

        val backBtn = TextView(this).apply {
            text = "← Voltar para a Aura"
            textSize = 14f
            setTextColor(amberAccent)
            setPadding(0, 0, 0, dp(16))
            setOnClickListener {
                triggerVibration("tique")
                showHomeScreen()
            }
        }

        val title = TextView(this).apply {
            text = "Ajustes da Aura"
            textSize = 24f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(0, 0, 0, dp(20))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBox(bgCard, 22, strokeSoft)
        }

        val btnHome = makeActionButton("Definir Aura como Tela Inicial (S26)", true) {
            openHomePickerSettings()
        }

        val btnReplayWelcome = makeActionButton("Reabrir tela de boas-vindas / instalação", false) {
            markOnboardingDone(false)
            showSplashScreen {
                showOnboardingScreen()
            }
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        }

        val btnDnd = makeActionButton("Permissão de Não Perturbe (Foco)", false) {
            try {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                // ignore
            }
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        }

        card.addView(btnHome)
        card.addView(btnReplayWelcome)
        card.addView(btnDnd)

        root.addView(backBtn)
        root.addView(title)
        root.addView(card)

        scroll.addView(root)
        container.addView(scroll)
    }

    // ---------------------------------------------------------
    // COMPONENTE DO LOGO NATIVO DA AURA (ANEL ÂMBAR)
    // ---------------------------------------------------------
    private class AuraRingLogoView(context: Context) : View(context) {
        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(55, 0xE9, 0xA0, 0x6B)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(230, 0xE9, 0xA0, 0x6B)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val minDim = minOf(width, height).toFloat()
            outerGlowPaint.strokeWidth = minDim * 0.11f
            ringPaint.strokeWidth = minDim * 0.042f
            val radius = minDim * 0.36f
            canvas.drawCircle(cx, cy, radius, outerGlowPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }

    // ---------------------------------------------------------
    // UTILITÁRIOS NATIVOS (APPS, AGENDA, ALARME, LAUNCHER)
    // ---------------------------------------------------------
    private fun loadInstalledApps(): List<AppItem> {
        if (cachedApps.isNotEmpty()) return cachedApps
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

        val list = mutableListOf<AppItem>()
        val seen = HashSet<String>()
        for (info in resolveInfos) {
            val act = info.activityInfo ?: continue
            val pkg = act.packageName ?: continue
            val cls = act.name ?: continue
            if (pkg == packageName || !seen.add(pkg)) continue
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) continue
            list.add(AppItem(pkg, cls, label, "geral"))
        }
        val sorted = list.sortedBy { it.label.lowercase(Locale.getDefault()) }
        cachedApps = sorted
        return sorted
    }

    private fun launchAppItem(app: AppItem) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                return
            }
            val compIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(compIntent)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun launchSystemIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun openAppDetails(pkg: String) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun getNextCalendarEventSummary(): String {
        if (!hasReadCalendar()) {
            return "Agenda não conectada — toque em Ajustes para liberar."
        }
        return try {
            val now = System.currentTimeMillis()
            val end = now + 24L * 60L * 60L * 1000L
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, now)
            ContentUris.appendId(uriBuilder, end)

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY
            )

            contentResolver.query(
                uriBuilder.build(),
                projection,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cursor ->
                val idxTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val idxBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val idxAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                while (cursor.moveToNext()) {
                    val allDay = idxAllDay >= 0 && cursor.getInt(idxAllDay) == 1
                    if (allDay) continue
                    val title = if (idxTitle >= 0) cursor.getString(idxTitle)?.trim().orEmpty() else ""
                    val begin = if (idxBegin >= 0) cursor.getLong(idxBegin) else 0L
                    if (title.isNotEmpty() && begin >= now - 15 * 60_000L) {
                        return "Próximo: $title às ${fmt.format(Date(begin))}"
                    }
                }
            }
            "Nenhum compromisso próximo nas próximas 24 horas."
        } catch (_: Throwable) {
            "Agenda disponível no aparelho."
        }
    }

    private fun getNextAlarmLabel(): String {
        return try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return "Sem alarme ativo"
            val next = am.nextAlarmClock ?: return "Nenhum alarme programado"
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            "Alarme ativo às ${fmt.format(Date(next.triggerTime))}"
        } catch (_: Throwable) {
            "Alarme do sistema"
        }
    }

    private fun isOnboardingDone(): Boolean {
        return getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)
    }

    private fun markOnboardingDone(done: Boolean) {
        getSharedPreferences(USER_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_DONE, done)
            .apply()
    }

    private fun isDefaultLauncher(): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            res?.activityInfo?.packageName == packageName
        } catch (_: Throwable) {
            false
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = extractShortcutTarget(intent)
        if (target == "settings" || target == "fundo") {
            showSettingsScreen()
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            if (isOnboardingDone()) {
                showHomeScreen()
            } else {
                showOnboardingScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cachedApps = emptyList()
        if (currentScreen == "onboarding") {
            showOnboardingScreen()
        } else if (currentScreen == "home") {
            showHomeScreen()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (currentScreen == "onboarding") {
            showOnboardingScreen()
        } else if (currentScreen == "home") {
            showHomeScreen()
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

    private fun makeActionButton(label: String, highlighted: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (highlighted) amberAccent else textPrimary)
            background = GradientDrawable().apply {
                setColor(if (highlighted) amberFill else Color.argb(18, 0xEF, 0xE8, 0xDF))
                cornerRadius = dp(99).toFloat()
                setStroke(dp(1), if (highlighted) amberStroke else strokeSoft)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(): View {
        return View(this).apply {
            setBackgroundColor(strokeSoft)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.topMargin = dp(16)
            lp.bottomMargin = dp(16)
            layoutParams = lp
        }
    }

    private fun roundedBox(fillColor: Int, radiusDp: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics
    ).toInt()

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = if (pattern == "pouso") VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
                vibrator.vibrate(VibrationEffect.createPredefined
