package com.separ.locationdrifter

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsetsController
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.data.RouteManager
import com.separ.locationdrifter.util.RealLocationHelper
import java.util.WeakHashMap

/**
 * 全局应用入口：负责初始化自定义速度/海拔配置、夜间模式（跟随系统 / 夜间 / 浅色）、
 * 侧边栏与设置/关于页面挂载、系统栏着色、暗色地图同步、弹窗深色适配及启动自动定位当前位置。
 */
class LocationDrifterApp : Application() {

    companion object {
        private const val UI_PREFS_NAME = "ui_state_prefs"
        private const val KEY_THEME_MODE = "theme_mode"

        const val THEME_MODE_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2

        private val LIGHT_TO_DARK_TEXT = mapOf(
            Color.parseColor("#0F172A") to Color.parseColor("#F1F5F9"),
            Color.parseColor("#1E293B") to Color.parseColor("#E2E8F0"),
            Color.parseColor("#334155") to Color.parseColor("#CBD5E1"),
            Color.parseColor("#475569") to Color.parseColor("#CBD5E1"),
            Color.parseColor("#64748B") to Color.parseColor("#94A3B8"),
            Color.parseColor("#1D4ED8") to Color.parseColor("#93C5FD"),
            Color.parseColor("#2563EB") to Color.parseColor("#60A5FA"),
            Color.parseColor("#1E3A8A") to Color.parseColor("#93C5FD"),
            Color.parseColor("#059669") to Color.parseColor("#34D399"),
            Color.parseColor("#D97706") to Color.parseColor("#FBBF24"),
            Color.parseColor("#DC2626") to Color.parseColor("#F87171"),
            Color.parseColor("#92400E") to Color.parseColor("#FDE68A"),
            Color.parseColor("#78350F") to Color.parseColor("#FCD34D")
        )

        private val LIGHT_TO_DARK_PILL = mapOf(
            Color.parseColor("#ECFDF5") to (Color.parseColor("#064E3B") to Color.parseColor("#059669")),
            Color.parseColor("#FEF2F2") to (Color.parseColor("#450A0A") to Color.parseColor("#991B1B")),
            Color.parseColor("#FFFBEB") to (Color.parseColor("#451A03") to Color.parseColor("#92400E")),
            Color.parseColor("#EFF6FF") to (Color.parseColor("#172554") to Color.parseColor("#3B82F6")),
            Color.parseColor("#F8FAFC") to (Color.parseColor("#1E293B") to Color.parseColor("#334155")),
            Color.parseColor("#F1F5F9") to (Color.parseColor("#1E293B") to Color.parseColor("#334155"))
        )

        private val LIGHT_DIVIDER_COLOR = Color.parseColor("#F1F5F9")
        private val DARK_DIVIDER_COLOR = Color.parseColor("#334155")
    }

    private val wiredActivities = WeakHashMap<Activity, Boolean>()
    private val adaptedDialogRoots = WeakHashMap<View, Boolean>()
    private val autoLocatedActivities = WeakHashMap<Activity, Boolean>()

    override fun onCreate() {
        super.onCreate()
        RouteManager.initCustomSpeeds(this)
        ConfigManager.getAltitude(this)

        val savedMode = getSavedThemeMode(this)
        applyPlatformNightMode(savedMode)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                val mode = getSavedThemeMode(activity)
                applyLegacyNightConfigIfNeeded(activity, mode)
                val dark = isDarkActive(activity, mode)
                activity.setTheme(if (dark) R.style.AppTheme_Dark else R.style.AppTheme_Light)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val mode = getSavedThemeMode(activity)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    applyLegacyNightConfigIfNeeded(activity, mode)
                }
            }

            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureActivityWired(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                ensureActivityWired(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                ensureActivityWired(activity)
                val mode = getSavedThemeMode(activity)
                val dark = isDarkActive(activity, mode)
                applySystemBars(activity, dark)
                updateThemeToggleLabel(activity, mode)
                syncWebMapTheme(activity, dark)
                tryAutoLocateOnStartup(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                wiredActivities.remove(activity)
                autoLocatedActivities.remove(activity)
            }
        })
    }

    /**
     * 必须在 Activity.setContentView() 之后挂载侧边栏、主题开关与深色适配：
     * onActivityCreated 发生在 super.onCreate() 内，此时布局尚未加载；
     * 因此统一在 onActivityPostCreated / onActivityStarted 中幂等初始化。
     */
    private fun ensureActivityWired(activity: Activity) {
        if (wiredActivities[activity] == true) return
        if (activity.findViewById<View>(R.id.rootMainLayout) == null) return
        wiredActivities[activity] = true

        val mode = getSavedThemeMode(activity)
        val dark = isDarkActive(activity, mode)
        applySystemBars(activity, dark)
        wireThemeControls(activity, mode)
        SidebarController.attach(
            activity = activity,
            getThemeMode = { getSavedThemeMode(activity) },
            onApplyThemeMode = { targetMode -> switchThemeMode(activity, targetMode) }
        )
        wireLocateMeButton(activity)
        syncWebMapTheme(activity, dark)
        tryAutoLocateOnStartup(activity)
        if (dark) {
            attachDarkModeAdapter(activity)
        }
    }

    private fun wireLocateMeButton(activity: Activity) {
        val btn = activity.findViewById<View>(R.id.btnLocateMe) ?: return
        btn.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            RealLocationHelper.locateCurrentPosition(activity, silent = false)
        }
    }

    private fun tryAutoLocateOnStartup(activity: Activity) {
        if (autoLocatedActivities[activity] == true) return
        if (!RealLocationHelper.hasLocationPermission(activity)) return
        val cfg = ConfigManager.load(activity)
        if (cfg.enabled) {
            autoLocatedActivities[activity] = true
            return
        }
        autoLocatedActivities[activity] = true
        RealLocationHelper.locateCurrentPosition(activity, silent = true)
    }

    private fun getSavedThemeMode(context: Context): Int {
        return context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, THEME_MODE_SYSTEM)
    }

    private fun saveThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode)
            .commit()
    }

    private fun switchThemeMode(activity: Activity, targetMode: Int) {
        saveThemeMode(activity, targetMode)
        updateThemeToggleLabel(activity, targetMode)
        val label = when (targetMode) {
            THEME_MODE_DARK -> "已切换：夜间模式"
            THEME_MODE_LIGHT -> "已切换：浅色模式"
            else -> "已切换：跟随系统"
        }
        Toast.makeText(activity, label, Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyPlatformNightMode(targetMode)
        } else {
            activity.recreate()
        }
    }

    private fun applyPlatformNightMode(mode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val uiModeManager = getSystemService(UiModeManager::class.java)
                val target = when (mode) {
                    THEME_MODE_DARK -> UiModeManager.MODE_NIGHT_YES
                    THEME_MODE_LIGHT -> UiModeManager.MODE_NIGHT_NO
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
                uiModeManager?.setApplicationNightMode(target)
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyNightConfigIfNeeded(activity: Activity, mode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (mode != THEME_MODE_LIGHT && mode != THEME_MODE_DARK) return
        try {
            val res = activity.resources
            val config = Configuration(res.configuration)
            val nightFlag = if (mode == THEME_MODE_DARK) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightFlag
            res.updateConfiguration(config, res.displayMetrics)
        } catch (_: Throwable) {
        }
    }

    private fun isDarkActive(context: Context, mode: Int): Boolean {
        return when (mode) {
            THEME_MODE_DARK -> true
            THEME_MODE_LIGHT -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(activity: Activity, isDark: Boolean) {
        try {
            val window = activity.window ?: return
            window.statusBarColor = activity.getColor(R.color.surface_header)
            window.navigationBarColor = activity.getColor(R.color.surface_sheet)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                val appearance = if (isDark) 0 else mask
                window.insetsController?.setSystemBarsAppearance(appearance, mask)
            } else {
                val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                val current = window.decorView.systemUiVisibility
                window.decorView.systemUiVisibility = if (isDark) {
                    current and lightFlags.inv()
                } else {
                    current or lightFlags
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateThemeToggleLabel(activity: Activity, mode: Int) {
        val btn = activity.findViewById<TextView>(R.id.btnThemeToggle) ?: return
        btn.text = when (mode) {
            THEME_MODE_DARK -> "🌙 夜间模式：已开启"
            THEME_MODE_LIGHT -> "☀ 夜间模式：浅色"
            else -> "◐ 夜间模式：跟随系统"
        }
    }

    private fun wireThemeControls(activity: Activity, currentMode: Int) {
        val btn = activity.findViewById<TextView>(R.id.btnThemeToggle) ?: return
        updateThemeToggleLabel(activity, currentMode)
        btn.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val nowMode = getSavedThemeMode(activity)
            val nextMode = when (nowMode) {
                THEME_MODE_SYSTEM -> THEME_MODE_DARK
                THEME_MODE_DARK -> THEME_MODE_LIGHT
                else -> THEME_MODE_SYSTEM
            }
            switchThemeMode(activity, nextMode)
        }
    }

    private fun syncWebMapTheme(activity: Activity, isDark: Boolean) {
        val webMap = activity.findViewById<WebView>(R.id.webMap) ?: return
        try {
            webMap.setBackgroundColor(activity.getColor(R.color.map_bg))
            val js = "window.setDarkMode && window.setDarkMode($isDark);"
            webMap.evaluateJavascript(js, null)
            webMap.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    webMap.evaluateJavascript(js, null)
                }
            }, 600L)
            webMap.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    webMap.evaluateJavascript(js, null)
                }
            }, 1600L)
        } catch (_: Throwable) {
        }
    }

    private fun attachDarkModeAdapter(activity: Activity) {
        val root = activity.findViewById<View>(R.id.rootMainLayout) ?: return
        val strokePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            activity.resources.displayMetrics
        ).toInt().coerceAtLeast(1)

        val pillIds = intArrayOf(R.id.tvRunStatus, R.id.tvRootStatus, R.id.tvXposedStatus)
        val buttonIds = intArrayOf(R.id.btnToggleJoystick, R.id.btnDrawRoute, R.id.btnToggleOptions)

        val listener = ViewTreeObserver.OnPreDrawListener {
            for (id in pillIds) {
                val tv = activity.findViewById<TextView>(id) ?: continue
                remapTextViewColor(tv)
                remapPillBackground(tv, strokePx)
            }
            for (id in buttonIds) {
                val tv = activity.findViewById<TextView>(id) ?: continue
                remapTextViewColor(tv)
            }
            val candidates = activity.findViewById<LinearLayout>(R.id.candidatesList)
            if (candidates != null && candidates.childCount > 0) {
                remapSubtreeForDark(candidates, strokePx)
            }
            true
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)

        // 监听弹窗弹出（主窗口失焦时扫描当前进程内的所有 AlertDialog 窗口并自动深色适配）
        val decor = activity.window?.decorView
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus && !activity.isFinishing && !activity.isDestroyed) {
                scanAndAdaptDialogs(activity, strokePx)
                decor.postDelayed({
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        scanAndAdaptDialogs(activity, strokePx)
                    }
                }, 100L)
            }
        }
        decor?.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                scanAndAdaptDialogs(activity, strokePx)
            }
        }, 300L)
    }

    private fun scanAndAdaptDialogs(activity: Activity, strokePx: Int) {
        val dialogRoots = findForeignWindowRoots(activity)
        for (dialogRoot in dialogRoots) {
            if (dialogRoot is ViewGroup) {
                remapSubtreeForDark(dialogRoot, strokePx)
                if (adaptedDialogRoots[dialogRoot] != true) {
                    adaptedDialogRoots[dialogRoot] = true
                    dialogRoot.viewTreeObserver.addOnPreDrawListener {
                        remapSubtreeForDark(dialogRoot, strokePx)
                        true
                    }
                }
            }
        }
    }

    private fun findForeignWindowRoots(activity: Activity): List<View> {
        return try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmgClass.getMethod("getInstance").invoke(null) ?: return emptyList()
            val viewsField = wmgClass.getDeclaredField("mViews")
            viewsField.isAccessible = true
            val raw = viewsField.get(instance) as? List<*> ?: return emptyList()
            val mainDecor = activity.window?.decorView
            raw.mapNotNull { it as? View }.filter { it !== mainDecor }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun remapTextViewColor(tv: TextView) {
        val cur = tv.currentTextColor
        val mapped = LIGHT_TO_DARK_TEXT[cur]
        if (mapped != null) {
            tv.setTextColor(mapped)
        }
    }

    private fun remapPillBackground(view: View, strokePx: Int) {
        val bg = view.background as? GradientDrawable ?: return
        val curColor = bg.color?.defaultColor ?: return
        val mapped = LIGHT_TO_DARK_PILL[curColor] ?: return
        bg.setColor(mapped.first)
        bg.setStroke(strokePx, mapped.second)
    }

    private fun remapSubtreeForDark(group: ViewGroup, strokePx: Int) {
        remapPillBackground(group, strokePx)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            remapPillBackground(child, strokePx)
            when (child) {
                is TextView -> remapTextViewColor(child)
                is ViewGroup -> remapSubtreeForDark(child, strokePx)
                else -> {
                    val bg = child.background as? ColorDrawable
                    if (bg != null && bg.color == LIGHT_DIVIDER_COLOR) {
                        child.setBackgroundColor(DARK_DIVIDER_COLOR)
                    }
                }
            }
        }
    }
}
