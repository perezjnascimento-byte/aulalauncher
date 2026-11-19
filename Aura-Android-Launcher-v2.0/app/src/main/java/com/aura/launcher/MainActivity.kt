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

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgMain)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        wrapper.addView(AuraRingLogoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        })

        wrapper.addView(TextView(this).apply {
            text = "Aura"
            textSize = 30f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(6))
        })

        wrapper.addView(TextView(this).apply {
            text = "PRESENÇA E FOCO"
            textSize = 11f
            letterSpacing = 0.22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        })

        wrapper.startAnimation(AlphaAnimation(0f, 1f).apply {
            duration = 650L
            fillAfter = true
        })
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
        headerRow.addView(AuraRingLogoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        })
        headerRow.addView(TextView(this).apply {
            text = "  BEM-VINDO À AURA"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(amberAccent)
        })

        val title = TextView(this).apply {
            text = "Sua tela inicial,\nmais calma e intencional."
            textSize = 26f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        val desc = TextView(this).apply {
            text = "A Aura roda nativa no seu celular, sem WebView externa nem logotipo do Google AI Studio."
            textSize = 14f
            setTextColor(textSecondary)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, dp(24))
        }

        val setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBox(bgCard, 24, strokeSoft)
        }

        val isDefault = isDefaultLauncher()
        setupCard.addView(TextView(this).apply {
            text = if (isDefault) "✓ 1. Aura definida como Tela Inicial" else "1. Definir como Tela Inicial (Launcher)"
            textSize = 15f
            setTextColor(if (isDefault) amberAccent else textPrimary)
        })
        setupCard.addView(TextView(this).apply {
            text = if (isDefault) {
                "Tudo pronto! Ao tocar no botão Início, a Aura abrirá direto."
            } else {
                "Selecione a Aura como seu App de início padrão nas configurações do aparelho."
            }
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        })
        setupCard.addView(makeActionButton(
            label = if (isDefault) "App de início configurado" else "Definir Aura como Tela Inicial",
            highlighted = !isDefault
        ) {
            triggerVibration("tique")
            openHomePickerSettings()
        })

        setupCard.addView(makeDivider())

        val hasCal = hasReadCalendar()
        setupCard.addView(TextView(this).apply {
            text = if (hasCal) "✓ 2. Agenda conectada" else "2. Agenda do dia (opcional)"
            textSize = 15f
            setTextColor(if (hasCal) amberAccent else textPrimary)
        })
        setupCard.addView(TextView(this).apply {
            text = "Exibe seu próximo compromisso direto na tela inicial."
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        })
        setupCard.addView(makeActionButton(
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
        })

        val btnEnter = Button(this).apply {
            text = "Concluir e abrir a Aura"
            isAllCaps = false
            textSize = 15f
            setTextColor(bgMain)
            background = GradientDrawable().apply {
                setColor(amberAccent)
                cornerRadius = dp(99).toFloat()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        clockCol.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 44f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        clockCol.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
                .format(Date())
                .replaceFirstChar { it.uppercase() }
            textSize = 14f
            setTextColor(textSecondary)
        })

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

        if (!isDefaultLauncher()) {
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedBox(bgCardElevated, 20, amberStroke)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(18)
                layoutParams = lp
                setOnClickListener { openHomePickerSettings() }
            }
            banner.addView(TextView(this).apply {
                text = "Definir Aura como App de início →"
                textSize = 13f
                setTextColor(amberAccent)
            })
            root.addView(banner)
        }

        val todayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBox(bgCard, 24, strokeSoft)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(20)
            layoutParams = lp
        }
        todayCard.addView(TextView(this).apply {
            text = "HOJE"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(amberAccent)
        })
        todayCard.addView(TextView(this).apply {
            text = getNextCalendarEventSummary()
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(0, dp(8), 0, dp(6))
        })
        todayCard.addView(TextView(this).apply {
            text = getNextAlarmLabel()
            textSize = 13f
            setTextColor(textSecondary)
        })
        root.addView(todayCard)

        root.addView(TextView(this).apply {
            text = "ESSENCIAIS"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(textMuted)
            setPadding(dp(4), dp(24), 0, dp(10))
        })

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

        for ((label, action) in quickActions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(15), dp(18), dp(15))
                background = roundedBox(bgCard, 18, strokeSoft)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

        root.addView(makeActionButton("Todos os aplicativos", true) {
            triggerVibration("tique")
            showAllAppsScreen()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        })

        scroll.addView(root)
        container.addView(scroll)
    }

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
        topRow.addView(TextView(this).apply {
            text = "← Início"
            textSize = 14f
            setTextColor(amberAccent)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = roundedBox(bgCard, 99, strokeSoft)
            setOnClickListener {
                triggerVibration("tique")
                showHomeScreen()
            }
        })
        topRow.addView(TextView(this).apply {
            text = "  Aplicativos (${apps.size})"
            textSize = 18f
            setTypeface(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        root.addView(topRow)

        val searchInput = EditText(this).apply {
            hint = "Buscar aplicativo..."
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundedBox(bgCard, 16, strokeSoft)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(14)
            layoutParams = lp
        }
        root.addView(searchInput)

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
