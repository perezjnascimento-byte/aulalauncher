package com.aura.launcher

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        private const val CRASH_PREFS = "aura_crash_guard"
        private const val CRASH_KEY = "last_crash_trace"
        private const val CRASH_TIME_KEY = "last_crash_time"
        private const val USER_PREFS_NAME = "aura_user_prefs"
        private const val KEY_ONBOARDING_DONE = "aura_onboarding_done_v1"
    }

    private data class AppItem(
        val packageName: String,
        val activityName: String,
        val label: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootContainer: FrameLayout? = null
    private var splashShownOnce = false
    private var currentScreen = "splash"
    private var cachedApps: List<AppItem> = emptyList()

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
            // ignore
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

        val container = FrameLayout(this)
        container.setBackgroundColor(bgMain)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootContainer = container
        setContentView(container)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    "apps", "settings" -> showHomeScreen()
                    "onboarding" -> if (isOnboardingDone()) showHomeScreen()
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

    private fun showSplashScreen(onComplete: () -> Unit) {
        currentScreen = "splash"
        val container = rootContainer ?: return
        container.removeAllViews()

        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.gravity = Gravity.CENTER
        wrapper.setBackgroundColor(bgMain)
        wrapper.setPadding(dp(32), dp(32), dp(32), dp(32))

        val logo = AuraRingLogoView(this)
        logo.layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        wrapper.addView(logo)

        val titleView = TextView(this)
        titleView.text = "Aura"
        titleView.textSize = 30f
        titleView.setTypeface(Typeface.SERIF, Typeface.NORMAL)
        titleView.setTextColor(textPrimary)
        titleView.gravity = Gravity.CENTER
        titleView.setPadding(0, dp(20), 0, dp(6))
        wrapper.addView(titleView)

        val subView = TextView(this)
        subView.text = "PRESENÇA E FOCO"
        subView.textSize = 11f
        subView.letterSpacing = 0.22f
        subView.setTextColor(textMuted)
        subView.gravity = Gravity.CENTER
        wrapper.addView(subView)

        val anim = AlphaAnimation(0f, 1f)
        anim.duration = 650L
        anim.fillAfter = true
        wrapper.startAnimation(anim)
        container.addView(wrapper)

        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                triggerVibration("pouso")
                onComplete()
            }
        }, 1650L)
    }

    private fun showOnboardingScreen() {
        currentScreen = "onboarding"
        val container = rootContainer ?: return
        container.removeAllViews()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgMain)
        scroll.isFillViewport = true

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(dp(24), dp(54), dp(24), dp(36))

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.setPadding(0, 0, 0, dp(18))

        val miniLogo = AuraRingLogoView(this)
        miniLogo.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        headerRow.addView(miniLogo)

        val badge = TextView(this)
        badge.text = "  BEM-VINDO À AURA"
        badge.textSize = 11f
        badge.letterSpacing = 0.16f
        badge.setTextColor(amberAccent)
        headerRow.addView(badge)

        val title = TextView(this)
        title.text = "Sua tela inicial,\nmais calma e intencional."
        title.textSize = 26f
        title.setTypeface(Typeface.SERIF, Typeface.NORMAL)
        title.setTextColor(textPrimary)
        title.setLineSpacing(dp(4).toFloat(), 1f)

        val desc = TextView(this)
        desc.text = "A Aura roda nativa no seu celular, sem WebView externa nem logotipo do Google AI Studio."
        desc.textSize = 14f
        desc.setTextColor(textSecondary)
        desc.setLineSpacing(dp(3).toFloat(), 1f)
        desc.setPadding(0, dp(12), 0, dp(24))

        val setupCard = LinearLayout(this)
        setupCard.orientation = LinearLayout.VERTICAL
        setupCard.setPadding(dp(20), dp(20), dp(20), dp(20))
        setupCard.background = roundedBox(bgCard, 24, strokeSoft)

        val isDefault = isDefaultLauncher()
        val s1Title = TextView(this)
        s1Title.text = if (isDefault) "✓ 1. Aura definida como Tela Inicial" else "1. Definir como Tela Inicial (Launcher)"
        s1Title.textSize = 15f
        s1Title.setTextColor(if (isDefault) amberAccent else textPrimary)
        setupCard.addView(s1Title)

        val s1Sub = TextView(this)
        s1Sub.text = if (isDefault) {
            "Tudo pronto! Ao tocar no botão Início, a Aura abrirá direto."
        } else {
            "Selecione a Aura como seu App de início padrão nas configurações do aparelho."
        }
        s1Sub.textSize = 13f
        s1Sub.setTextColor(textSecondary)
        s1Sub.setPadding(0, dp(4), 0, dp(12))
        setupCard.addView(s1Sub)

        setupCard.addView(
            makeActionButton(
                label = if (isDefault) "App de início configurado" else "Definir Aura como Tela Inicial",
                highlighted = !isDefault
            ) {
                triggerVibration("tique")
                openHomePickerSettings()
            }
        )

        setupCard.addView(makeDivider())

        val hasCal = hasReadCalendar()
        val s2Title = TextView(this)
        s2Title.text = if (hasCal) "✓ 2. Agenda conectada" else "2. Agenda do dia (opcional)"
        s2Title.textSize = 15f
        s2Title.setTextColor(if (hasCal) amberAccent else textPrimary)
        setupCard.addView(s2Title)

        val s2Sub = TextView(this)
        s2Sub.text = "Exibe seu próximo compromisso direto na tela inicial."
        s2Sub.textSize = 13f
        s2Sub.setTextColor(textSecondary)
        s2Sub.setPadding(0, dp(4), 0, dp(12))
        setupCard.addView(s2Sub)

        setupCard.addView(
            makeActionButton(
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
        )

        val btnEnter = Button(this)
        btnEnter.text = "Concluir e abrir a Aura"
        btnEnter.isAllCaps = false
        btnEnter.textSize = 15f
        btnEnter.setTextColor(bgMain)
        val enterBg = GradientDrawable()
        enterBg.setColor(amberAccent)
        enterBg.cornerRadius = dp(99).toFloat()
        btnEnter.background = enterBg
        val enterLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        enterLp.topMargin = dp(24)
        btnEnter.layoutParams = enterLp
        btnEnter.setOnClickListener {
            triggerVibration("pouso")
            markOnboardingDone(true)
            showHomeScreen()
        }

        root.addView(headerRow)
        root.addView(title)
        root.addView(desc)
        root.addView(setupCard)
        root.addView(btnEnter)

        scroll.addView(root)
        container.addView(scroll)
    }

    private fun showHomeScreen() {
        currentScreen = "home"
        val container = rootContainer ?: return
        container.removeAllViews()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgMain)
        scroll.isFillViewport = true

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(54), dp(22), dp(36))

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL

        val clockCol = LinearLayout(this)
        clockCol.orientation = LinearLayout.VERTICAL
        clockCol.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val clockView = TextView(this)
        clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        clockView.textSize = 44f
        clockView.setTypeface(Typeface.SERIF, Typeface.NORMAL)
        clockView.setTextColor(textPrimary)
        clockCol.addView(clockView)

        val dateView = TextView(this)
        dateView.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
            .format(Date())
            .replaceFirstChar { it.uppercase() }
        dateView.textSize = 14f
        dateView.setTextColor(textSecondary)
        clockCol.addView(dateView)

        val settingsBtn = TextView(this)
        settingsBtn.text = "Ajustes"
        settingsBtn.textSize = 13f
        settingsBtn.setTextColor(textSecondary)
        settingsBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
        settingsBtn.background = roundedBox(bgCard, 99, strokeSoft)
        settingsBtn.setOnClickListener {
            triggerVibration("tique")
            showSettingsScreen()
        }

        topBar.addView(clockCol)
        topBar.addView(settingsBtn)
        root.addView(topBar)

        if (!isDefaultLauncher()) {
            val banner = LinearLayout(this)
            banner.orientation = LinearLayout.VERTICAL
            banner.setPadding(dp(16), dp(14), dp(16), dp(14))
            banner.background = roundedBox(bgCardElevated, 20, amberStroke)
            val bannerLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            bannerLp.topMargin = dp(18)
            banner.layoutParams = bannerLp
            banner.setOnClickListener { openHomePickerSettings() }

            val bannerText = TextView(this)
            bannerText.text = "Definir Aura como App de início →"
            bannerText.textSize = 13f
            bannerText.setTextColor(amberAccent)
            banner.addView(bannerText)
            root.addView(banner)
        }

        val todayCard = LinearLayout(this)
        todayCard.orientation = LinearLayout.VERTICAL
        todayCard.setPadding(dp(18), dp(18), dp(18), dp(18))
        todayCard.background = roundedBox(bgCard, 24, strokeSoft)
        val todayLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        todayLp.topMargin = dp(20)
        todayCard.layoutParams = todayLp

        val todayTitle = TextView(this)
        todayTitle.text = "HOJE"
        todayTitle.textSize = 11f
        todayTitle.letterSpacing = 0.16f
        todayTitle.setTextColor(amberAccent)
        todayCard.addView(todayTitle)

        val eventView = TextView(this)
        eventView.text = getNextCalendarEventSummary()
        eventView.textSize = 15f
        eventView.setTextColor(textPrimary)
        eventView.setPadding(0, dp(8), 0, dp(6))
        todayCard.addView(eventView)

        val alarmView = TextView(this)
        alarmView.text = getNextAlarmLabel()
        alarmView.textSize = 13f
        alarmView.setTextColor(textSecondary)
        todayCard.addView(alarmView)

        root.addView(todayCard)

        val essLabel = TextView(this)
        essLabel.text = "ESSENCIAIS"
        essLabel.textSize = 11f
        essLabel.letterSpacing = 0.16f
        essLabel.setTextColor(textMuted)
        essLabel.setPadding(dp(4), dp(24), 0, dp(10))
        root.addView(essLabel)

        val quickActions = listOf(
            "Telefone" to { launchSystemIntent(Intent(Intent.ACTION_DIAL)) },
            "Mensagens" to {
                launchSystemIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
            },
            "Câmera" to { launchSystemIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
            "Agenda" to {
                launchSystemIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
            }
        )

        for (pair in quickActions) {
            val label = pair.first
            val action = pair.second
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), dp(15), dp(18), dp(15))
            row.background = roundedBox(bgCard, 18, strokeSoft)
            val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rowLp.bottomMargin = dp(10)
            row.layoutParams = rowLp
            row.setOnClickListener {
                triggerVibration("tique")
                action()
            }

            val labelView = TextView(this)
            labelView.text = label
            labelView.textSize = 16f
            labelView.setTextColor(textPrimary)
            labelView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(labelView)

            val arrowView = TextView(this)
            arrowView.text = "↗"
            arrowView.textSize = 15f
            arrowView.setTextColor(textMuted)
            row.addView(arrowView)

            root.addView(row)
        }

        val allAppsBtn = makeActionButton("Todos os aplicativos", true) {
            triggerVibration("tique")
            showAllAppsScreen()
        }
        val allAppsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        allAppsLp.topMargin = dp(12)
        allAppsBtn.layoutParams = allAppsLp
        root.addView(allAppsBtn)

        scroll.addView(root)
        container.addView(scroll)
    }

    private fun showAllAppsScreen() {
        currentScreen = "apps"
        val container = rootContainer ?: return
        container.removeAllViews()

        val apps = loadInstalledApps()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgMain)
        root.setPadding(dp(20), dp(50), dp(20), dp(20))

        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL
        topRow.setPadding(0, 0, 0, dp(14))

        val backBtn = TextView(this)
        backBtn.text = "← Início"
        backBtn.textSize = 14f
        backBtn.setTextColor(amberAccent)
        backBtn.setPadding(dp(12), dp(8), dp(14), dp(8))
        backBtn.background = roundedBox(bgCard, 99, strokeSoft)
        backBtn.setOnClickListener {
            triggerVibration("tique")
            showHomeScreen()
        }
        topRow.addView(backBtn)

        val titleView = TextView(this)
        titleView.text = "  Aplicativos (${apps.size})"
        titleView.textSize = 18f
        titleView.setTypeface(Typeface.SERIF, Typeface.NORMAL)
        titleView.setTextColor(textPrimary)
        topRow.addView(titleView)

        root.addView(topRow)

        val searchInput = EditText(this)
        searchInput.hint = "Buscar aplicativo..."
        searchInput.setHintTextColor(textMuted)
        searchInput.setTextColor(textPrimary)
        searchInput.textSize = 15f
        searchInput.setSingleLine(true)
        searchInput.setPadding(dp(16), dp(13), dp(16), dp(13))
        searchInput.background = roundedBox(bgCard, 16, strokeSoft)
        val searchLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchLp.bottomMargin = dp(14)
        searchInput.layoutParams = searchLp
        root.addView(searchInput)

        val listScroll = ScrollView(this)
        listScroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val listContainer = LinearLayout(this)
        listContainer.orientation = LinearLayout.VERTICAL
        listScroll.addView(listContainer)
        root.addView(listScroll)

        fun renderFiltered(query: String) {
            listContainer.removeAllViews()
            val q = query.trim().lowercase(Locale.getDefault())
            val filtered = if (q.isEmpty()) {
                apps
            } else {
                apps.filter { it.label.lowercase(Locale.getDefault()).contains(q) }
            }
            for (app in filtered) {
                val itemRow = LinearLayout(this)
                itemRow.orientation = LinearLayout.HORIZONTAL
                itemRow.gravity = Gravity.CENTER_VERTICAL
                itemRow.setPadding(dp(10), dp(10), dp(12), dp(10))
                val itemLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                itemLp.bottomMargin = dp(6)
                itemRow.layoutParams = itemLp
                itemRow.background = roundedBox(bgCard, 16, Color.TRANSPARENT)
                itemRow.setOnClickListener {
                    triggerVibration("tique")
                    launchAppItem(app)
                }
                itemRow.setOnLongClickListener {
                    openAppDetails(app.packageName)
                    true
                }

                val iconView = ImageView(this)
                iconView.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                try {
                    iconView.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                } catch (_: Throwable) {
                    // ignore
                }
                itemRow.addView(iconView)

                val nameView = TextView(this)
                nameView.text = app.label
                nameView.textSize = 15f
                nameView.setTextColor(textPrimary)
                nameView.setPadding(dp(14), 0, 0, 0)
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

    private fun showSettingsScreen() {
        currentScreen = "settings"
        val container = rootContainer ?: return
        container.removeAllViews()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgMain)
        scroll.isFillViewport = true

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(52), dp(22), dp(36))

        val backBtn = TextView(this)
        backBtn.text = "← Voltar para a Aura"
        backBtn.textSize = 14f
        backBtn.setTextColor(amberAccent)
        backBtn.setPadding(0, 0, 0, dp(16))
        backBtn.setOnClickListener {
            triggerVibration("tique")
            showHomeScreen()
        }
        root.addView(backBtn)

        val titleView = TextView(this)
        titleView.text = "Ajustes da Aura"
        titleView.textSize = 24f
        titleView.setTypeface(Typeface.SERIF, Typeface.NORMAL)
        titleView.setTextColor(textPrimary)
        titleView.setPadding(0, 0, 0, dp(20))
        root.addView(titleView)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(18), dp(18), dp(18))
        card.background = roundedBox(bgCard, 22, strokeSoft)

        card.addView(
            makeActionButton("Definir Aura como Tela Inicial", true) {
                openHomePickerSettings()
            }
        )

        val replayBtn = makeActionButton("Reabrir tela de boas-vindas / instalação", false) {
            markOnboardingDone(false)
            showSplashScreen {
                showOnboardingScreen()
            }
        }
        val replayLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        replayLp.topMargin = dp(12)
        replayBtn.layoutParams = replayLp
        card.addView(replayBtn)

        val dndBtn = makeActionButton("Permissão de Não Perturbe", false) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Throwable) {
                // ignore
            }
        }
        val dndLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        dndLp.topMargin = dp(12)
        dndBtn.layoutParams = dndLp
        card.addView(dndBtn)

        root.addView(card)
        scroll.addView(root)
        container.addView(scroll)
    }

    private class AuraRingLogoView(context: Context) : View(context) {
        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            outerGlowPaint.style = Paint.Style.STROKE
            outerGlowPaint.color = Color.argb(55, 0xE9, 0xA0, 0x6B)
            ringPaint.style = Paint.Style.STROKE
            ringPaint.color = Color.argb(230, 0xE9, 0xA0, 0x6B)
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

    private fun loadInstalledApps(): List<AppItem> {
        if (cachedApps.isNotEmpty()) return cachedApps
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

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
            list.add(AppItem(pkg, cls, label))
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
            val compIntent = Intent(Intent.ACTION_MAIN)
            compIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            compIntent.component = ComponentName(app.packageName, app.activityName)
            compIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
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
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
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
            val homeIntent = Intent(Intent.ACTION_MAIN)
            homeIntent.addCategory(Intent.CATEGORY_HOME)
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
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Throwable) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
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
            if (isOnboardingDone()) showHomeScreen() else showOnboardingScreen()
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
        val btn = Button(this)
        btn.text = label
        btn.isAllCaps = false
        btn.textSize = 14f
        btn.setTextColor(if (highlighted) amberAccent else textPrimary)
        val bg = GradientDrawable()
        bg.setColor(if (highlighted) amberFill else Color.argb(18, 0xEF, 0xE8, 0xDF))
        bg.cornerRadius = dp(99).toFloat()
        bg.setStroke(dp(1), if (highlighted) amberStroke else strokeSoft)
        btn.background = bg
        btn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun makeDivider(): View {
        val div = View(this)
        div.setBackgroundColor(strokeSoft)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.topMargin = dp(16)
        lp.bottomMargin = dp(16)
        div.layoutParams = lp
        return div
    }

    private fun roundedBox(fillColor: Int, radiusDp: Int, strokeColor: Int): GradientDrawable {
        val gd = GradientDrawable()
        gd.setColor(fillColor)
        gd.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != Color.TRANSPARENT) {
            gd.setStroke(dp(1), strokeColor)
        }
        return gd
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effectId = if (pattern == "pouso") {
                    VibrationEffect.EFFECT_CLICK
                } else {
                    VibrationEffect.EFFECT_TICK
                }
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(14L)
            }
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

    private fun showNativeRecoveryScreen(title: String, subtitle: String, detail: String) {
        try {
            val root = LinearLayout(this)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            root.setBackgroundColor(bgMain)
            root.setPadding(dp(24), dp(48), dp(24), dp(32))

            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(22), dp(24), dp(22), dp(22))
            card.background = roundedBox(bgCard, 28, strokeSoft)

            val tView = TextView(this)
            tView.text = title
            tView.textSize = 20f
            tView.setTypeface(Typeface.SERIF, Typeface.NORMAL)
            tView.setTextColor(textPrimary)
            card.addView(tView)

            val sView = TextView(this)
            sView.text = subtitle
            sView.textSize = 14f
            sView.setTextColor(textSecondary)
            sView.setPadding(0, dp(10), 0, dp(16))
            card.addView(sView)

            val dView = TextView(this)
            dView.text = detail
            dView.textSize = 11f
            dView.setTextColor(textMuted)
            dView.setPadding(0, 0, 0, dp(16))
            card.addView(dView)

            card.addView(
                makeActionButton("Recarregar Aura", true) {
                    showHomeScreen()
                }
            )
            root.addView(card)
            setContentView(root)
        } catch (_: Throwable) {
            // ignore
        }
    }
}
