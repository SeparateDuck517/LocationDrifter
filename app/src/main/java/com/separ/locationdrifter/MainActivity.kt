package com.separ.locationdrifter

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.data.RouteManager
import com.separ.locationdrifter.service.DriftVpnService
import com.separ.locationdrifter.service.JoystickOverlayService
import com.separ.locationdrifter.service.MockLocationService
import com.separ.locationdrifter.util.CoordTransform
import com.separ.locationdrifter.util.RootUtils
import com.separ.locationdrifter.util.ShizukuHelper
import com.separ.locationdrifter.xposed.ModuleStatus
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 101
        private const val REQ_VPN = 102
        private const val REQ_SHIZUKU = 103
        private const val REQ_OVERLAY = 104
        private const val UI_PREFS_NAME = "ui_state_prefs"
        private const val KEY_XPOSED_REBOOT_PROMPTED = "xposed_reboot_prompted"
        private const val KEY_SELECTED_WORK_MODE = "selected_work_mode"
        private const val KEY_SKIP_STARTUP_MODE_DIALOG = "skip_startup_mode_dialog"
        private const val KEY_SHIZUKU_AUTO_SUPPRESS_SCAN = "shizuku_auto_suppress_scan"
        private const val KEY_SHIZUKU_ISOLATE_TARGET_APPS = "shizuku_isolate_target_apps"
        private const val KEY_VPN_DEFAULT_MIGRATED_V2 = "vpn_default_migrated_v2"
        private const val KEY_CACHED_HAS_ROOT = "cached_has_root"
        private const val PROBE_PROVIDER = "location_drifter_probe"
        private val searchPool = Executors.newCachedThreadPool()
        private val searchGeneration = AtomicInteger(0)

        private val BUILTIN_CITIES_GCJ02 = listOf(
            "北京" to (39.908823 to 116.397470),
            "上海" to (31.230416 to 121.473701),
            "广州" to (23.129112 to 113.264385),
            "深圳" to (22.543096 to 114.057865),
            "杭州" to (30.274085 to 120.155070),
            "南京" to (32.060255 to 118.796877),
            "武汉" to (30.592849 to 114.305539),
            "成都" to (30.572816 to 104.066801),
            "重庆" to (29.563010 to 106.551557),
            "西安" to (34.341574 to 108.939770),
            "长沙" to (28.228209 to 112.938814),
            "南昌" to (28.682892 to 115.858198),
            "赣州" to (25.831091 to 114.935909),
            "九江" to (29.705103 to 116.001951),
            "上饶" to (28.454863 to 117.943433),
            "郑州" to (34.746599 to 113.625368),
            "济南" to (36.651216 to 117.120098),
            "青岛" to (36.067108 to 120.382609),
            "苏州" to (31.298974 to 120.585289),
            "无锡" to (31.491169 to 120.311910),
            "宁波" to (29.868388 to 121.543990),
            "温州" to (27.993849 to 120.699367),
            "天津" to (39.084158 to 117.200983),
            "合肥" to (31.820586 to 117.227239),
            "福州" to (26.074508 to 119.296494),
            "厦门" to (24.479834 to 118.089425),
            "泉州" to (24.874132 to 118.675675),
            "昆明" to (24.880095 to 102.832891),
            "贵阳" to (26.647661 to 106.630154),
            "南宁" to (22.817002 to 108.366543),
            "海口" to (20.044002 to 110.198293),
            "三亚" to (18.252847 to 109.511909),
            "石家庄" to (38.042758 to 114.514860),
            "太原" to (37.870590 to 112.548879),
            "沈阳" to (41.805698 to 123.431475),
            "大连" to (38.914003 to 121.614682),
            "长春" to (43.817072 to 125.323544),
            "哈尔滨" to (45.803775 to 126.534967),
            "呼和浩特" to (40.842585 to 111.749181),
            "兰州" to (36.061089 to 103.834303),
            "西宁" to (36.617144 to 101.778228),
            "银川" to (38.487194 to 106.230909),
            "乌鲁木齐" to (43.825592 to 87.616848),
            "拉萨" to (29.652491 to 91.172112),
            "东莞" to (23.020673 to 113.751799),
            "佛山" to (23.021548 to 113.121416),
            "珠海" to (22.270715 to 113.576726),
            "香港" to (22.319304 to 114.169361),
            "澳门" to (22.198745 to 113.543873),
            "台北" to (25.032969 to 121.565418)
        )
    }

    private enum class WorkMode(val key: String) {
        ROOT_LSPOSED("root_lsposed"),
        SHIZUKU("shizuku"),
        ROOTLESS_BASIC("rootless_basic");

        companion object {
            fun fromKey(raw: String?): WorkMode? {
                return values().firstOrNull { it.key == raw }
            }
        }
    }

    private data class PlaceSearchResult(
        val gcjLat: Double,
        val gcjLng: Double,
        val label: String,
        val detail: String = ""
    )

    private lateinit var rootMainLayout: LinearLayout
    private lateinit var bottomControlPanel: LinearLayout
    private lateinit var tvSubtitleMode: TextView
    private lateinit var btnSwitchMode: TextView
    private lateinit var btnHelpGuide: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvXposedStatus: TextView
    private lateinit var tvRunStatus: TextView
    private lateinit var cardDegradedBanner: LinearLayout
    private lateinit var tvDegradedTitle: TextView
    private lateinit var tvDegradedDesc: TextView
    private lateinit var btnDegradedAction: TextView

    private lateinit var etPlaceSearch: EditText
    private lateinit var btnClearSearch: TextView
    private lateinit var btnSearchPlace: Button
    private lateinit var candidatesScroll: ScrollView
    private lateinit var candidatesList: LinearLayout

    private lateinit var tvAddress: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnCopyCoord: TextView

    // 摇杆与轨迹控制视图
    private lateinit var routeControlCard: LinearLayout
    private lateinit var tvRouteInfo: TextView
    private lateinit var btnRouteUndo: TextView
    private lateinit var btnRouteClear: TextView
    private lateinit var btnRouteSave: TextView
    private lateinit var btnRouteSpeed: Button
    private lateinit var btnRouteLoop: Button
    private lateinit var btnRoutePlayPause: Button
    private lateinit var btnToggleJoystick: Button
    private lateinit var btnDrawRoute: Button
    private lateinit var btnSavedRoutes: TextView
    private lateinit var btnToggleOptions: Button
    private lateinit var strategyOptionsPanel: LinearLayout

    private lateinit var webMap: WebView
    private lateinit var cbSystemMock: CheckBox
    private lateinit var cbAntiDrift: CheckBox
    private lateinit var cbIptables: CheckBox
    private lateinit var cbVpn: CheckBox
    private lateinit var cbStepSim: CheckBox

    private lateinit var btnFavorites: Button
    private lateinit var btnToggleDrift: Button
    private lateinit var btnUpdatePoint: Button

    private var currentGcjLat = 39.908823
    private var currentGcjLng = 116.397470
    private var currentWgsLat = 39.907418
    private var currentWgsLng = 116.391227
    private var currentAddress = "北京天安门"

    private var hasRoot = false
    private var hasXposed = false
    private var hasSystemHookActive = false
    private var hasShizukuBinder = false
    private var hasShizukuPermission = false
    private var shizukuAutoGrantTried = false
    private var rootCheckCompleted = false
    private var startupDialogShown = false
    private var selectedMode: WorkMode = WorkMode.ROOTLESS_BASIC
    private var shizukuAutoSuppressScan = true
    private var shizukuIsolateTargetApps = false
    private var isEnabled = false
    private var lastRenderedEnabled: Boolean? = null
    private var suppressCheckboxCallback = false
    private var isImeVisible = false

    // 底部策略面板折叠状态
    private var isStrategyExpanded = false

    // 轨迹绘制与巡航状态
    private var isRouteDrawMode = false
    private var activeRoutePoints: List<RouteManager.RoutePoint> = emptyList()
    private var routeTotalMeters: Double = 0.0
    private var routeSpeedMps: Float = 4.0f
    private var routeLoopMode: Int = RouteManager.LOOP_ONCE

    private var btnColorAnimator: ValueAnimator? = null
    private var statusPulseAnimator: ObjectAnimator? = null

    private var modeDialog: AlertDialog? = null
    private var modeDialogRefreshCallback: (() -> Unit)? = null

    private val livePositionListener = object : MockLocationService.PositionListener {
        override fun onLivePositionUpdated(
            gcjLat: Double,
            gcjLng: Double,
            wgsLat: Double,
            wgsLng: Double,
            address: String,
            bearingDeg: Float,
            speedMps: Float
        ) {
            if (isFinishing || isDestroyed) return
            currentGcjLat = gcjLat
            currentGcjLng = gcjLng
            currentWgsLat = wgsLat
            currentWgsLng = wgsLng
            currentAddress = address
            updateCoordTexts()
            val js = String.format(Locale.US, "window.updateLivePosition(%.8f, %.8f, true)", gcjLat, gcjLng)
            webMap.evaluateJavascript(js, null)
        }
    }

    private val routeStateListener = object : MockLocationService.RouteStateListener {
        override fun onRouteStateChanged(
            isPlaying: Boolean,
            traveledMeters: Double,
            totalMeters: Double,
            finished: Boolean
        ) {
            if (isFinishing || isDestroyed) return
            refreshRoutePanelUI(traveledMeters = traveledMeters, totalMetersOverride = totalMeters)
            if (finished) {
                toast("已到达轨迹终点")
            }
        }
    }

    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val rect = Rect()
        rootMainLayout.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootMainLayout.rootView.height
        val keypadHeight = screenHeight - rect.bottom
        val nowVisible = keypadHeight > dp(140)
        if (nowVisible != isImeVisible) {
            isImeVisible = nowVisible
            if (isImeVisible) {
                bottomControlPanel.visibility = View.GONE
                cardDegradedBanner.visibility = View.GONE
            } else {
                bottomControlPanel.visibility = View.VISIBLE
                updateEnvironmentUI()
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            refreshShizukuState()
            if (hasShizukuPermission && selectedMode == WorkMode.SHIZUKU && !RootUtils.isMockLocationAllowed(this)) {
                grantPermissionsViaShizukuAsync()
            }
            updateEnvironmentUI()
            modeDialogRefreshCallback?.invoke()
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            refreshShizukuState()
            updateEnvironmentUI()
            modeDialogRefreshCallback?.invoke()
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                runOnUiThread {
                    refreshShizukuState()
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        shizukuAutoGrantTried = false
                        selectedMode = WorkMode.SHIZUKU
                        saveSelectedMode(WorkMode.SHIZUKU)
                        applyModeDefaults(WorkMode.SHIZUKU)
                        grantPermissionsViaShizukuAsync(showResultToast = true)
                    } else {
                        toast("未授予 Shizuku 权限")
                    }
                    updateEnvironmentUI()
                    modeDialogRefreshCallback?.invoke()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLightSystemBars()
        setContentView(R.layout.activity_main)

        registerShizukuListeners()
        initViews()
        rootMainLayout.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
        loadSavedState()
        bindCheckboxListeners()
        initWebView()
        checkEnvironmentStatus()
        requestBasicPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        MockLocationService.addPositionListener(livePositionListener)
        MockLocationService.addRouteStateListener(routeStateListener)
    }

    override fun onStop() {
        MockLocationService.removePositionListener(livePositionListener)
        MockLocationService.removeRouteStateListener(routeStateListener)
        super.onStop()
    }

    override fun onDestroy() {
        btnColorAnimator?.cancel()
        statusPulseAnimator?.cancel()
        try {
            rootMainLayout.viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
        } catch (_: Throwable) {
        }
        unregisterShizukuListeners()
        modeDialog?.dismiss()
        super.onDestroy()
    }

    private fun registerShizukuListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
    }

    private fun unregisterShizukuListeners() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
    }

    private fun refreshShizukuState() {
        hasShizukuBinder = ShizukuHelper.isBinderAlive()
        hasShizukuPermission = ShizukuHelper.hasPermission()
        if (!hasShizukuPermission) {
            shizukuAutoGrantTried = false
        }
    }

    private fun grantPermissionsViaShizukuAsync(showResultToast: Boolean = false) {
        if (RootUtils.isMockLocationAllowed(this)) {
            shizukuAutoGrantTried = true
            updateEnvironmentUI()
            modeDialogRefreshCallback?.invoke()
            return
        }
        ShizukuHelper.runShizukuTask {
            val ok = ShizukuHelper.grantMockAndLocationPermissions(this)
            shizukuAutoGrantTried = true
            runOnUiThread {
                updateEnvironmentUI()
                modeDialogRefreshCallback?.invoke()
                if (showResultToast) {
                    if (ok) {
                        toast("模拟位置已自动放行 ✓")
                    } else {
                        toast("系统限制自动授权，请在开发者选项手动选择本应用")
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLightSystemBars() {
        try {
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = Color.WHITE
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        reconcileRuntimeStateIfStopped()
        refreshShizukuState()
        if (hasShizukuPermission && selectedMode == WorkMode.SHIZUKU && !RootUtils.isMockLocationAllowed(this) && !shizukuAutoGrantTried) {
            grantPermissionsViaShizukuAsync()
        }
        updateEnvironmentUI()
        refreshRunStatusUI(animate = false)
        refreshFavoritesCount()
        refreshJoystickButtonUI()
        refreshSavedRoutesCount()
        refreshRoutePanelUI()
        refreshStrategyToggleBadge()
        modeDialogRefreshCallback?.invoke()
    }

    private fun reconcileRuntimeStateIfStopped() {
        val cfg = ConfigManager.load(this)
        val servicesAlive = MockLocationService.isRunning || DriftVpnService.isRunning
        if (cfg.enabled && !servicesAlive) {
            isEnabled = false
            ConfigManager.save(this, cfg.copy(enabled = false), syncTmpWithRoot = false)
            if (hasRoot) {
                RootUtils.runRootTask {
                    if (RootUtils.isRootAvailable()) {
                        RootUtils.disableIptablesBlock()
                    }
                }
            }
            if (ShizukuHelper.hasPermission()) {
                ShizukuHelper.runShizukuTask {
                    ShizukuHelper.restoreAllAntiDriftStates(this)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressScaleEffect(view: View, pressedScale: Float = 0.965f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(90L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(140L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    private fun initViews() {
        rootMainLayout = findViewById(R.id.rootMainLayout)
        bottomControlPanel = findViewById(R.id.bottomControlPanel)
        tvSubtitleMode = findViewById(R.id.tvSubtitleMode)
        btnSwitchMode = findViewById(R.id.btnSwitchMode)
        btnHelpGuide = findViewById(R.id.btnHelpGuide)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvXposedStatus = findViewById(R.id.tvXposedStatus)
        tvRunStatus = findViewById(R.id.tvRunStatus)
        cardDegradedBanner = findViewById(R.id.cardDegradedBanner)
        tvDegradedTitle = findViewById(R.id.tvDegradedTitle)
        tvDegradedDesc = findViewById(R.id.tvDegradedDesc)
        btnDegradedAction = findViewById(R.id.btnDegradedAction)

        etPlaceSearch = findViewById(R.id.etPlaceSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnSearchPlace = findViewById(R.id.btnSearchPlace)
        candidatesScroll = findViewById(R.id.candidatesScroll)
        candidatesList = findViewById(R.id.candidatesList)

        tvAddress = findViewById(R.id.tvAddress)
        tvCoords = findViewById(R.id.tvCoords)
        btnCopyCoord = findViewById(R.id.btnCopyCoord)

        routeControlCard = findViewById(R.id.routeControlCard)
        tvRouteInfo = findViewById(R.id.tvRouteInfo)
        btnRouteUndo = findViewById(R.id.btnRouteUndo)
        btnRouteClear = findViewById(R.id.btnRouteClear)
        btnRouteSave = findViewById(R.id.btnRouteSave)
        btnRouteSpeed = findViewById(R.id.btnRouteSpeed)
        btnRouteLoop = findViewById(R.id.btnRouteLoop)
        btnRoutePlayPause = findViewById(R.id.btnRoutePlayPause)
        btnToggleJoystick = findViewById(R.id.btnToggleJoystick)
        btnDrawRoute = findViewById(R.id.btnDrawRoute)
        btnSavedRoutes = findViewById(R.id.btnSavedRoutes)
        btnToggleOptions = findViewById(R.id.btnToggleOptions)
        strategyOptionsPanel = findViewById(R.id.strategyOptionsPanel)

        webMap = findViewById(R.id.webMap)
        cbSystemMock = findViewById(R.id.cbSystemMock)
        cbAntiDrift = findViewById(R.id.cbAntiDrift)
        cbIptables = findViewById(R.id.cbIptables)
        cbVpn = findViewById(R.id.cbVpn)
        cbStepSim = findViewById(R.id.cbStepSim)

        btnFavorites = findViewById(R.id.btnFavorites)
        btnToggleDrift = findViewById(R.id.btnToggleDrift)
        btnUpdatePoint = findViewById(R.id.btnUpdatePoint)

        val btnInputCoord = findViewById<TextView>(R.id.btnInputCoord)
        val btnAddFavorite = findViewById<TextView>(R.id.btnAddFavorite)

        attachPressScaleEffect(btnToggleDrift, 0.97f)
        attachPressScaleEffect(btnUpdatePoint, 0.96f)
        attachPressScaleEffect(btnSearchPlace, 0.95f)
        attachPressScaleEffect(btnInputCoord, 0.96f)
        attachPressScaleEffect(btnAddFavorite, 0.96f)
        attachPressScaleEffect(btnFavorites, 0.96f)
        attachPressScaleEffect(btnToggleJoystick, 0.96f)
        attachPressScaleEffect(btnDrawRoute, 0.96f)
        attachPressScaleEffect(btnSavedRoutes, 0.95f)
        attachPressScaleEffect(btnToggleOptions, 0.96f)
        attachPressScaleEffect(btnRoutePlayPause, 0.96f)
        attachPressScaleEffect(btnSwitchMode, 0.95f)
        attachPressScaleEffect(btnHelpGuide, 0.95f)
        attachPressScaleEffect(btnCopyCoord, 0.95f)

        setupNativeSearchBar()
        setupJoystickAndRouteControls()

        btnCopyCoord.setOnClickListener { copyCurrentCoordsToClipboard() }
        btnInputCoord.setOnClickListener { showManualCoordDialog() }
        btnAddFavorite.setOnClickListener { showAddFavoriteDialog() }
        btnFavorites.setOnClickListener { showFavoritesDialog() }

        btnSwitchMode.setOnClickListener {
            showModeSelectionDialog(isAutoStartup = false)
        }
        btnHelpGuide.setOnClickListener {
            showEnvironmentGuideDialog()
        }

        val bannerOrActionClick = View.OnClickListener {
            when {
                selectedMode == WorkMode.ROOT_LSPOSED && hasXposed && !hasSystemHookActive ->
                    showFirstActivationRebootDialog()
                selectedMode == WorkMode.SHIZUKU && !hasShizukuPermission ->
                    handleShizukuConnectAction()
                selectedMode == WorkMode.SHIZUKU && hasShizukuPermission && !RootUtils.isMockLocationAllowed(this) && shizukuAutoGrantTried ->
                    openDeveloperOptions()
                selectedMode == WorkMode.SHIZUKU && hasShizukuPermission ->
                    showTargetAppsConfigDialog()
                selectedMode == WorkMode.ROOTLESS_BASIC && !RootUtils.isMockLocationAllowed(this) ->
                    openDeveloperOptions()
                else ->
                    showEnvironmentGuideDialog()
            }
        }
        cardDegradedBanner.setOnClickListener(bannerOrActionClick)
        btnDegradedAction.setOnClickListener(bannerOrActionClick)
        tvRootStatus.setOnClickListener { showModeSelectionDialog(isAutoStartup = false) }
        tvXposedStatus.setOnClickListener(bannerOrActionClick)

        btnToggleDrift.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (isEnabled) {
                stopDrifting()
            } else {
                startDrifting()
            }
        }

        btnUpdatePoint.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (isEnabled) {
                applyUpdatedCoordinates()
            }
        }
    }

    private fun setupJoystickAndRouteControls() {
        btnToggleJoystick.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleJoystickOverlay()
        }

        btnDrawRoute.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setRouteDrawMode(!isRouteDrawMode)
        }

        btnSavedRoutes.setOnClickListener {
            showSavedRoutesDialog()
        }

        btnRouteUndo.setOnClickListener {
            webMap.evaluateJavascript("window.undoRoutePoint()", null)
        }

        btnRouteClear.setOnClickListener {
            MockLocationService.resetRoutePlaybackState()
            RouteManager.setActiveRoutePoints(this, emptyList())
            activeRoutePoints = emptyList()
            routeTotalMeters = 0.0
            webMap.evaluateJavascript("window.clearRoute()", null)
            if (isRouteDrawMode) {
                setRouteDrawMode(false)
            } else {
                refreshRoutePanelUI()
            }
            toast("已清空轨迹")
        }

        btnToggleOptions.setOnClickListener {
            isStrategyExpanded = !isStrategyExpanded
            strategyOptionsPanel.visibility = if (isStrategyExpanded) View.VISIBLE else View.GONE
            bottomControlPanel.requestLayout()
            rootMainLayout.requestLayout()
            refreshStrategyToggleBadge()
        }

        btnRouteSave.setOnClickListener {
            showSaveRouteDialog()
        }

        btnRouteSpeed.setOnClickListener {
            routeSpeedMps = RouteManager.nextSpeedPreset(routeSpeedMps)
            RouteManager.setActiveRouteSpeed(this, routeSpeedMps)
            refreshRoutePanelUI()
        }

        btnRouteLoop.setOnClickListener {
            routeLoopMode = RouteManager.nextLoopMode(routeLoopMode)
            RouteManager.setActiveLoopMode(this, routeLoopMode)
            refreshRoutePanelUI()
        }

        btnRoutePlayPause.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleRoutePlayback()
        }
    }

    private fun toggleJoystickOverlay() {
        if (JoystickOverlayService.isRunning) {
            val stopIntent = Intent(this, JoystickOverlayService::class.java).apply {
                action = JoystickOverlayService.ACTION_STOP
            }
            startService(stopIntent)
            btnToggleJoystick.postDelayed({ refreshJoystickButtonUI() }, 120L)
            toast("已关闭悬浮摇杆")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("开启全局悬浮摇杆需要「显示在其他应用上层」权限，授权后即可在任意应用界面实时控制移动方向。")
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, REQ_OVERLAY)
                    } catch (_: Throwable) {
                        toast("无法打开悬浮窗权限页，请在系统设置中手动开启")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        startJoystickOverlayService()
    }

    private fun startJoystickOverlayService() {
        val intent = Intent(this, JoystickOverlayService::class.java).apply {
            action = JoystickOverlayService.ACTION_START
        }
        startService(intent)
        btnToggleJoystick.postDelayed({ refreshJoystickButtonUI() }, 120L)
        if (isEnabled) {
            toast("悬浮摇杆已开启，拖动即可实时移动")
        } else {
            toast("悬浮摇杆已开启（请点击「开启定位漂移」使坐标生效）")
        }
    }

    private fun refreshJoystickButtonUI() {
        val running = JoystickOverlayService.isRunning
        btnToggleJoystick.text = if (running) "关闭摇杆" else "悬浮摇杆"
        btnToggleJoystick.setTextColor(Color.parseColor(if (running) "#DC2626" else "#1D4ED8"))
    }

    private fun setRouteDrawMode(enabled: Boolean) {
        isRouteDrawMode = enabled
        webMap.evaluateJavascript("window.setRouteDrawMode($enabled)", null)
        btnDrawRoute.text = if (enabled) "完成绘制" else "绘制轨迹"
        btnDrawRoute.setTextColor(Color.parseColor(if (enabled) "#059669" else "#1D4ED8"))
        if (enabled) {
            toast("点击地图依次添加轨迹途经点，可拖拽节点微调")
        }
        refreshRoutePanelUI()
    }

    private fun toggleRoutePlayback() {
        if (MockLocationService.isRoutePlaying) {
            val pauseIntent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_PAUSE_ROUTE
            }
            startService(pauseIntent)
            refreshRoutePanelUI()
            toast("已暂停轨迹模拟")
            return
        }

        if (activeRoutePoints.size < 2) {
            toast("请先在地图上点击添加至少 2 个轨迹点")
            if (!isRouteDrawMode) {
                setRouteDrawMode(true)
            }
            return
        }

        if (isRouteDrawMode) {
            setRouteDrawMode(false)
        }

        RouteManager.setActiveRoutePoints(this, activeRoutePoints)
        RouteManager.setActiveRouteSpeed(this, routeSpeedMps)
        RouteManager.setActiveLoopMode(this, routeLoopMode)

        if (!isEnabled) {
            val first = activeRoutePoints.first()
            applyPickedLocation(first.gcjLat, first.gcjLng, first.wgsLat, first.wgsLng, "轨迹起点")
            startDrifting(autoStartRoute = true)
            return
        }

        startRoutePlaybackService()
    }

    private fun startRoutePlaybackService() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBasicPermissionsIfNeeded()
            toast("请授予位置权限")
            return
        }
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
        }
        startForegroundService(intent)
        toast("开始沿轨迹模拟移动（${RouteManager.speedShortLabel(routeSpeedMps)}）")
    }

    private fun refreshRoutePanelUI(
        traveledMeters: Double = MockLocationService.routeTraveledMeters,
        totalMetersOverride: Double = -1.0
    ) {
        val shouldShow = isRouteDrawMode || activeRoutePoints.isNotEmpty() || MockLocationService.isRoutePlaying
        val targetVis = if (shouldShow) View.VISIBLE else View.GONE
        if (routeControlCard.visibility != targetVis) {
            TransitionManager.endTransitions(bottomControlPanel)
            routeControlCard.visibility = targetVis
            bottomControlPanel.requestLayout()
            rootMainLayout.requestLayout()
        }

        val total = if (totalMetersOverride >= 0.0) totalMetersOverride else routeTotalMeters
        val playing = MockLocationService.isRoutePlaying
        tvRouteInfo.text = when {
            playing && total > 0.01 -> {
                val pct = ((traveledMeters / total) * 100.0).toInt().coerceIn(0, 100)
                "模拟中 $pct% · ${RouteManager.formatDistance(traveledMeters)} / ${RouteManager.formatDistance(total)}"
            }
            activeRoutePoints.isEmpty() -> "绘制中：点击地图添加途经点"
            else -> "轨迹: ${activeRoutePoints.size} 点 · ${RouteManager.formatDistance(total)}"
        }

        btnRouteSpeed.text = RouteManager.speedShortLabel(routeSpeedMps)
        btnRouteLoop.text = RouteManager.loopModeLabel(routeLoopMode)
        btnRoutePlayPause.text = if (playing) "⏸ 暂停轨迹" else "▶ 开始轨迹模拟"
        btnRoutePlayPause.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (playing) {
                intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#D97706"))
            } else {
                intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#1D4ED8"))
            }
        ).apply {
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun refreshSavedRoutesCount() {
        val count = RouteManager.getSavedRoutes(this).size
        btnSavedRoutes.text = if (count > 0) "路线库($count)" else "路线库"
    }

    private fun refreshStrategyToggleBadge() {
        val checkedCount = listOf(cbSystemMock, cbAntiDrift, cbIptables, cbVpn, cbStepSim).count { it.isChecked }
        val arrow = if (isStrategyExpanded) "▴" else "▾"
        btnToggleOptions.text = "策略 $checkedCount/5 $arrow"
        btnToggleOptions.setTextColor(
            Color.parseColor(if (isStrategyExpanded) "#1D4ED8" else "#475569")
        )
    }

    private fun showSaveRouteDialog() {
        if (activeRoutePoints.size < 2) {
            toast("至少需要 2 个轨迹点才可保存")
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val defaultName = "轨迹 ${activeRoutePoints.size}点 (${RouteManager.formatDistance(routeTotalMeters)})"
        val etName = EditText(this).apply {
            setText(defaultName)
            hint = "请输入轨迹名称"
        }
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("保存当前轨迹")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { defaultName }
                RouteManager.saveRoute(
                    this,
                    RouteManager.SavedRoute(
                        name = name,
                        points = activeRoutePoints,
                        speedMps = routeSpeedMps,
                        loopMode = routeLoopMode
                    )
                )
                refreshSavedRoutesCount()
                toast("已保存轨迹：$name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSavedRoutesDialog() {
        val list = RouteManager.getSavedRoutes(this)
        if (list.isEmpty()) {
            toast("轨迹库为空，请先点击「绘制轨迹」并保存")
            return
        }
        val items = list.map { r ->
            val dist = RouteManager.formatDistance(RouteManager.totalDistanceMeters(r.points))
            "${r.name}  (${r.points.size}点 · $dist · ${RouteManager.speedShortLabel(r.speedMps)})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("轨迹库（点击加载）")
            .setItems(items) { _, which ->
                val route = list[which]
                MockLocationService.resetRoutePlaybackState()
                activeRoutePoints = route.points
                routeTotalMeters = RouteManager.totalDistanceMeters(route.points)
                routeSpeedMps = route.speedMps
                routeLoopMode = route.loopMode
                RouteManager.setActiveRoutePoints(this, route.points)
                RouteManager.setActiveRouteSpeed(this, route.speedMps)
                RouteManager.setActiveLoopMode(this, route.loopMode)
                setRouteDrawMode(false)
                syncRouteToWebMap(route.points)
                val first = route.points.first()
                applyPickedLocation(first.gcjLat, first.gcjLng, first.wgsLat, first.wgsLng, route.name)
                refreshRoutePanelUI()
                toast("已加载轨迹：${route.name}")
            }
            .setNeutralButton("管理") { _, _ ->
                showDeleteSavedRouteDialog(list)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDeleteSavedRouteDialog(list: List<RouteManager.SavedRoute>) {
        val items = list.map { "删除：${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("删除已保存轨迹")
            .setItems(items) { _, which ->
                val removed = list[which].name
                RouteManager.removeSavedRoute(this, which)
                refreshSavedRoutesCount()
                toast("已删除：$removed")
            }
            .setNeutralButton("清空") { _, _ ->
                RouteManager.clearSavedRoutes(this)
                refreshSavedRoutesCount()
                toast("已清空轨迹库")
            }
            .setNegativeButton("返回") { _, _ ->
                showSavedRoutesDialog()
            }
            .show()
    }

    private fun syncRouteToWebMap(points: List<RouteManager.RoutePoint>) {
        val quoted = JSONObject.quote(RouteManager.pointsToJsonArray(points).toString())
        webMap.evaluateJavascript("window.loadRoutePoints($quoted)", null)
    }

    private fun setupNativeSearchBar() {
        etPlaceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearSearch.visibility = if (!s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etPlaceSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                triggerNativeSearch()
                true
            } else {
                false
            }
        }

        btnClearSearch.setOnClickListener {
            searchGeneration.incrementAndGet()
            btnSearchPlace.isEnabled = true
            btnSearchPlace.text = "定位"
            etPlaceSearch.setText("")
            hideSearchCandidates()
            etPlaceSearch.requestFocus()
        }

        btnSearchPlace.setOnClickListener {
            triggerNativeSearch()
        }
    }

    private fun hideSoftKeyboard() {
        try {
            etPlaceSearch.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etPlaceSearch.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    private fun hideSearchCandidates() {
        candidatesScroll.visibility = View.GONE
        candidatesList.removeAllViews()
    }

    private fun triggerNativeSearch() {
        val q = etPlaceSearch.text.toString().trim()
        if (q.isEmpty()) {
            toast("请输入地名或经纬度")
            return
        }
        hideSoftKeyboard()
        hideSearchCandidates()

        val coordRegex = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,，\\s]+\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
        val match = coordRegex.matchEntire(q)
        if (match != null) {
            val a = match.groupValues[1].toDoubleOrNull()
            val b = match.groupValues[2].toDoubleOrNull()
            if (a != null && b != null) {
                var lat = a
                var lng = b
                if (kotlin.math.abs(a) > 60.0 && kotlin.math.abs(b) <= 60.0) {
                    lng = a
                    lat = b
                }
                val wgs = CoordTransform.gcj02ToWgs84(lat, lng)
                applyPickedLocation(lat, lng, wgs.lat, wgs.lng, "手动坐标")
                syncMarkerToWebMap(lat, lng, "手动坐标")
                if (isEnabled) {
                    applyUpdatedCoordinates()
                }
                return
            }
        }

        val gen = searchGeneration.incrementAndGet()
        btnSearchPlace.isEnabled = false
        btnSearchPlace.text = "搜索中"
        performPlaceSearch(q, gen)
    }

    private fun showNativeCandidates(candidates: List<PlaceSearchResult>) {
        val first = candidates.first()
        val firstWgs = CoordTransform.gcj02ToWgs84(first.gcjLat, first.gcjLng)
        applyPickedLocation(first.gcjLat, first.gcjLng, firstWgs.lat, firstWgs.lng, first.label)
        syncMarkerToWebMap(first.gcjLat, first.gcjLng, first.label)
        if (isEnabled) {
            applyUpdatedCoordinates(showToast = false)
        }

        if (candidates.size <= 1) {
            hideSearchCandidates()
            return
        }

        candidatesList.removeAllViews()
        for (i in candidates.indices) {
            val item = candidates[i]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val wgs = CoordTransform.gcj02ToWgs84(item.gcjLat, item.gcjLng)
                    applyPickedLocation(item.gcjLat, item.gcjLng, wgs.lat, wgs.lng, item.label)
                    syncMarkerToWebMap(item.gcjLat, item.gcjLng, item.label)
                    hideSearchCandidates()
                    if (isEnabled) {
                        applyUpdatedCoordinates()
                    }
                }
            }
            val tvTitle = TextView(this).apply {
                text = item.label
                setTextColor(Color.parseColor("#0F172A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvSub = TextView(this).apply {
                text = item.detail.ifBlank {
                    String.format(Locale.US, "%.5f, %.5f", item.gcjLat, item.gcjLng)
                }
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(tvTitle)
            row.addView(tvSub)
            candidatesList.addView(row)

            if (i < candidates.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(Color.parseColor("#F1F5F9"))
                }
                candidatesList.addView(divider)
            }
        }
        candidatesScroll.alpha = 0f
        candidatesScroll.visibility = View.VISIBLE
        candidatesScroll.animate().alpha(1f).setDuration(160L).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindCheckboxListeners() {
        val listener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (suppressCheckboxCallback) return@OnCheckedChangeListener
            if (selectedMode == WorkMode.SHIZUKU && buttonView.id == R.id.cbIptables) {
                shizukuAutoSuppressScan = isChecked
                getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_SHIZUKU_AUTO_SUPPRESS_SCAN, isChecked)
                    .apply()
                if (isEnabled) {
                    ShizukuHelper.runShizukuTask {
                        if (isChecked) {
                            ShizukuHelper.applyAntiDriftScanSuppression(this)
                        } else {
                            ShizukuHelper.restoreAntiDriftScanSuppression(this)
                        }
                    }
                }
                return@OnCheckedChangeListener
            }

            if (selectedMode == WorkMode.SHIZUKU && buttonView.id == R.id.cbAntiDrift) {
                if (isChecked && ShizukuHelper.getTargetApps(this).isEmpty()) {
                    suppressCheckboxCallback = true
                    cbAntiDrift.isChecked = false
                    suppressCheckboxCallback = false
                    showTargetAppsConfigDialog()
                    return@OnCheckedChangeListener
                }
                shizukuIsolateTargetApps = isChecked
                getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_SHIZUKU_ISOLATE_TARGET_APPS, isChecked)
                    .apply()
                if (isEnabled) {
                    ShizukuHelper.runShizukuTask {
                        if (isChecked) {
                            val count = ShizukuHelper.applyTargetAppLocationIsolation(this)
                            runOnUiThread {
                                toast("已隔离 $count 个指定应用的硬件扫描")
                            }
                        } else {
                            ShizukuHelper.restoreTargetAppLocationIsolation(this)
                            runOnUiThread {
                                toast("已恢复指定应用原始权限")
                            }
                        }
                    }
                }
                return@OnCheckedChangeListener
            }

            if (buttonView.id == R.id.cbStepSim && isChecked) {
                toast("已开启实验性步数模拟（零三方注入：步行/跑步自动计步，骑行默认不计步）")
            }

            if (isEnabled) {
                applyUpdatedCoordinates(showToast = false)
            } else {
                ConfigManager.save(this, buildCurrentConfig(enabled = false), syncTmpWithRoot = false)
            }
        }
        val lockedTouchExplain = View.OnTouchListener { v, event ->
            if (!v.isEnabled && event.action == MotionEvent.ACTION_UP) {
                val reason = when {
                    v.id == R.id.cbStepSim ->
                        "「步数模拟(实验性)」通过 system_server 零注入拦截传感器服务，仅 Root + LSPosed 完整模式可用"
                    v.id == R.id.cbIptables ->
                        "「内核阻断」需 Root，免 Root 请使用「VPN阻断」"
                    selectedMode == WorkMode.ROOT_LSPOSED && hasXposed && !hasSystemHookActive ->
                        "LSPosed 已激活，重启手机后可用"
                    else ->
                        "仅「Root + LSPosed 模式」或「Shizuku 模式」支持，点击顶部「切换模式」查看"
                }
                toast(reason)
                if (selectedMode == WorkMode.ROOT_LSPOSED && hasXposed && !hasSystemHookActive) {
                    showFirstActivationRebootDialog()
                } else {
                    showModeSelectionDialog(isAutoStartup = false)
                }
                true
            } else {
                false
            }
        }

        val wrappedListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            listener.onCheckedChanged(buttonView, isChecked)
            refreshStrategyToggleBadge()
        }
        for (box in listOf(cbSystemMock, cbAntiDrift, cbIptables, cbVpn, cbStepSim)) {
            box.setOnCheckedChangeListener(wrappedListener)
            box.setOnTouchListener(lockedTouchExplain)
        }
    }

    private fun loadSavedState() {
        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val savedRaw = uiPrefs.getString(KEY_SELECTED_WORK_MODE, null)
        WorkMode.fromKey(savedRaw)?.let {
            selectedMode = it
        }
        if (savedRaw != null) {
            hasRoot = uiPrefs.getBoolean(KEY_CACHED_HAS_ROOT, false)
            rootCheckCompleted = true
        }
        shizukuAutoSuppressScan = uiPrefs.getBoolean(KEY_SHIZUKU_AUTO_SUPPRESS_SCAN, true)
        shizukuIsolateTargetApps = uiPrefs.getBoolean(KEY_SHIZUKU_ISOLATE_TARGET_APPS, false)

        activeRoutePoints = RouteManager.getActiveRoutePoints(this)
        routeTotalMeters = RouteManager.totalDistanceMeters(activeRoutePoints)
        routeSpeedMps = RouteManager.getActiveRouteSpeed(this)
        routeLoopMode = RouteManager.getActiveLoopMode(this)

        suppressCheckboxCallback = true
        val cfg = ConfigManager.load(this)
        val servicesAlive = MockLocationService.isRunning || DriftVpnService.isRunning
        if (cfg.enabled && !servicesAlive) {
            isEnabled = false
            ConfigManager.save(this, cfg.copy(enabled = false), syncTmpWithRoot = false)
        } else {
            isEnabled = cfg.enabled
        }
        currentGcjLat = cfg.gcjLat
        currentGcjLng = cfg.gcjLng
        currentWgsLat = cfg.wgsLat
        currentWgsLng = cfg.wgsLng
        currentAddress = cfg.address

        cbSystemMock.isChecked = cfg.enableSystemMock
        cbAntiDrift.isChecked = cfg.blockNetworkDrift
        cbIptables.isChecked = cfg.enableIptables
        cbStepSim.isChecked = cfg.enableStepSim

        val migratedV2 = uiPrefs.getBoolean(KEY_VPN_DEFAULT_MIGRATED_V2, false)
        if (!migratedV2 && (selectedMode == WorkMode.SHIZUKU || selectedMode == WorkMode.ROOTLESS_BASIC)) {
            cbVpn.isChecked = true
            uiPrefs.edit().putBoolean(KEY_VPN_DEFAULT_MIGRATED_V2, true).apply()
        } else {
            cbVpn.isChecked = cfg.enableVpn
        }
        suppressCheckboxCallback = false

        updateCoordTexts()
        refreshRunStatusUI(animate = false)
        refreshFavoritesCount()
        refreshSavedRoutesCount()
        refreshJoystickButtonUI()
        refreshRoutePanelUI()
        refreshStrategyToggleBadge()
    }

    private fun saveSelectedMode(mode: WorkMode) {
        selectedMode = mode
        getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_WORK_MODE, mode.key)
            .apply()
    }

    private fun applyModeDefaults(mode: WorkMode) {
        if (isEnabled) return
        when (mode) {
            WorkMode.ROOT_LSPOSED -> {
                setCheckboxState(cbSystemMock, enabled = true, checked = true, label = "系统GPS注入")
                setCheckboxState(
                    cbAntiDrift,
                    enabled = hasRoot && hasSystemHookActive,
                    checked = hasRoot && hasSystemHookActive,
                    label = if (hasRoot && hasSystemHookActive) "防基站/WiFi漂移" else "防基站(待重启)"
                )
                setCheckboxState(cbIptables, enabled = hasRoot, checked = hasRoot, label = "内核阻断")
                setCheckboxState(cbVpn, enabled = true, checked = false, label = "VPN阻断")
                setCheckboxState(
                    cbStepSim,
                    enabled = hasRoot && hasSystemHookActive,
                    checked = false,
                    label = if (hasRoot && hasSystemHookActive) "步数模拟(实验性·步行/跑步)" else "步数模拟(实验性·需LSPosed)"
                )
            }
            WorkMode.SHIZUKU -> {
                val hasTargets = ShizukuHelper.getTargetApps(this).isNotEmpty()
                setCheckboxState(cbSystemMock, enabled = true, checked = true, label = "系统GPS注入")
                setCheckboxState(
                    cbAntiDrift,
                    enabled = true,
                    checked = shizukuIsolateTargetApps && hasTargets,
                    label = "隔离应用扫描"
                )
                setCheckboxState(
                    cbIptables,
                    enabled = true,
                    checked = shizukuAutoSuppressScan,
                    label = "自动关扫描"
                )
                setCheckboxState(cbVpn, enabled = true, checked = true, label = "VPN阻断(推荐)")
                setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·需Root+LSPosed)")
            }
            WorkMode.ROOTLESS_BASIC -> {
                setCheckboxState(cbSystemMock, enabled = true, checked = true, label = "系统GPS注入")
                setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                setCheckboxState(cbVpn, enabled = true, checked = true, label = "VPN阻断(推荐)")
                setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·需Root+LSPosed)")
            }
        }
        ConfigManager.save(this, buildCurrentConfig(enabled = false), syncTmpWithRoot = false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webMap.alpha = 0.0f
        webMap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        webMap.webChromeClient = WebChromeClient()
        webMap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (webMap.alpha < 1f) {
                    webMap.animate().alpha(1f).setDuration(220L).start()
                }
            }
        }
        webMap.addJavascriptInterface(MapJsBridge(), "AndroidBridge")
        webMap.loadUrl("file:///android_asset/map.html")
    }

    private fun checkEnvironmentStatus() {
        refreshShizukuState()
        updateEnvironmentUI()
        RootUtils.runRootTask {
            val rootOk = RootUtils.isRootAvailable()
            if (rootOk) {
                if (!RootUtils.isMockLocationAllowed(this)) {
                    RootUtils.grantMockAndLocationPermissions(packageName)
                }
                if (!isEnabled) {
                    RootUtils.disableIptablesBlock()
                }
            } else if (ShizukuHelper.hasPermission()) {
                if (!RootUtils.isMockLocationAllowed(this)) {
                    ShizukuHelper.grantMockAndLocationPermissions(this)
                }
                shizukuAutoGrantTried = true
                if (!isEnabled) {
                    ShizukuHelper.restoreAllAntiDriftStates(this)
                }
            }
            runOnUiThread {
                hasRoot = rootOk
                refreshShizukuState()
                rootCheckCompleted = true

                val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                uiPrefs.edit().putBoolean(KEY_CACHED_HAS_ROOT, rootOk).apply()

                val savedRaw = uiPrefs.getString(KEY_SELECTED_WORK_MODE, null)
                if (savedRaw == null) {
                    selectedMode = when {
                        hasRoot -> WorkMode.ROOT_LSPOSED
                        hasShizukuBinder || ShizukuHelper.isShizukuInstalled(this) -> WorkMode.SHIZUKU
                        else -> WorkMode.ROOTLESS_BASIC
                    }
                    applyModeDefaults(selectedMode)
                }

                updateEnvironmentUI()
                modeDialogRefreshCallback?.invoke()

                val skipStartupDialog = uiPrefs.getBoolean(KEY_SKIP_STARTUP_MODE_DIALOG, false)
                if (!startupDialogShown && !skipStartupDialog) {
                    startupDialogShown = true
                    showModeSelectionDialog(isAutoStartup = true)
                }
            }
        }
    }

    private fun isSystemHookReadyThisBoot(): Boolean {
        if (!ModuleStatus.isModuleActive()) return false

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                if (lm.isProviderEnabled(PROBE_PROVIDER)) {
                    return true
                }
                if (lm.sendExtraCommand(PROBE_PROVIDER, "ping", Bundle())) {
                    return true
                }
            }
        } catch (_: Throwable) {
        }

        return try {
            val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
            val hookedBoot = Settings.Global.getInt(
                contentResolver,
                ConfigManager.SETTINGS_GLOBAL_HOOKED_BOOT_KEY,
                -2
            )
            currentBoot >= 0 && hookedBoot == currentBoot
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyPillStyle(tv: TextView, textColorHex: String, bgColorHex: String, strokeColorHex: String) {
        tv.setTextColor(Color.parseColor(textColorHex))
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.parseColor(bgColorHex))
            setStroke(dp(1), Color.parseColor(strokeColorHex))
        }
    }

    private fun updateEnvironmentUI() {
        hasXposed = ModuleStatus.isModuleActive()
        hasSystemHookActive = isSystemHookReadyThisBoot()
        refreshShizukuState()

        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        if (!hasXposed && uiPrefs.getBoolean(KEY_XPOSED_REBOOT_PROMPTED, false)) {
            uiPrefs.edit().putBoolean(KEY_XPOSED_REBOOT_PROMPTED, false).apply()
        }

        if (!rootCheckCompleted) {
            tvRootStatus.text = "模式: 检测中..."
            applyPillStyle(tvRootStatus, "#475569", "#F8FAFC", "#E2E8F0")
            tvXposedStatus.text = "权限: 检测中..."
            applyPillStyle(tvXposedStatus, "#475569", "#F8FAFC", "#E2E8F0")
            return
        }

        if (selectedMode == WorkMode.ROOT_LSPOSED &&
            hasXposed &&
            !hasSystemHookActive &&
            !uiPrefs.getBoolean(KEY_XPOSED_REBOOT_PROMPTED, false)
        ) {
            uiPrefs.edit().putBoolean(KEY_XPOSED_REBOOT_PROMPTED, true).apply()
            showFirstActivationRebootDialog()
        }

        when (selectedMode) {
            WorkMode.ROOT_LSPOSED -> {
                if (hasRoot) {
                    tvRootStatus.text = "模式: Root完整版 ✓"
                    applyPillStyle(tvRootStatus, "#059669", "#ECFDF5", "#A7F3D0")
                } else {
                    tvRootStatus.text = "模式: Root完整版(缺Root)"
                    applyPillStyle(tvRootStatus, "#DC2626", "#FEF2F2", "#FECACA")
                }

                if (hasRoot && hasSystemHookActive) {
                    tvSubtitleMode.text = "系统级防漂移 · 零三方注入"
                    tvXposedStatus.text = "LSPosed: 已激活 ✓"
                    applyPillStyle(tvXposedStatus, "#059669", "#ECFDF5", "#A7F3D0")
                    cardDegradedBanner.visibility = View.GONE

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = true, label = "防基站/WiFi漂移")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                    setCheckboxState(cbStepSim, enabled = true, label = "步数模拟(实验性·步行/跑步)")
                } else if (hasRoot && hasXposed && !hasSystemHookActive) {
                    tvSubtitleMode.text = "LSPosed 已开启 · 重启后生效"
                    tvXposedStatus.text = "LSPosed: 待重启 ›"
                    applyPillStyle(tvXposedStatus, "#D97706", "#FFFBEB", "#FDE68A")

                    cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                    tvDegradedTitle.text = "LSPosed 已开启，重启手机后正式生效"
                    tvDegradedDesc.text = "请确认 LSPosed 已勾选「系统框架」与「电话服务」，重启后解锁。"
                    btnDegradedAction.text = "立即重启 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(待重启)")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                    setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·待重启)")
                } else if (hasRoot && !hasXposed) {
                    tvSubtitleMode.text = "Root 已就绪 · 待激活 LSPosed"
                    tvXposedStatus.text = "LSPosed: 未激活 ›"
                    applyPillStyle(tvXposedStatus, "#D97706", "#FFFBEB", "#FDE68A")

                    cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                    tvDegradedTitle.text = "LSPosed 模块未激活"
                    tvDegradedDesc.text = "请在 LSPosed 启用本模块（勾选系统框架与电话服务）并重启。"
                    btnDegradedAction.text = "激活向导 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                    setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·需LSPosed)")
                } else {
                    tvSubtitleMode.text = "未获取 Root · 可切换免 Root 模式"
                    tvXposedStatus.text = "Root: 未获取 ›"
                    applyPillStyle(tvXposedStatus, "#DC2626", "#FEF2F2", "#FECACA")

                    cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                    tvDegradedTitle.text = "未检测到 Root 权限"
                    tvDegradedDesc.text = "请点击右上角「切换模式」选择 Shizuku 或纯免 Root 模式。"
                    btnDegradedAction.text = "切换模式 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                    setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
                    setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·需Root+LSPosed)")
                }
            }

            WorkMode.SHIZUKU -> {
                val mockAllowed = RootUtils.isMockLocationAllowed(this)
                tvSubtitleMode.text = "免 Root · Shizuku 增强模式"
                tvRootStatus.text = "模式: Shizuku增强 ›"
                applyPillStyle(tvRootStatus, "#1D4ED8", "#EFF6FF", "#BFDBFE")

                cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                if (hasShizukuPermission) {
                    when {
                        mockAllowed -> {
                            tvXposedStatus.text = "Shizuku: 已就绪 ✓"
                            applyPillStyle(tvXposedStatus, "#059669", "#ECFDF5", "#A7F3D0")
                            tvDegradedTitle.text = "Shizuku 增强模式已就绪"
                            tvDegradedDesc.text =
                                "已自动放行模拟位置并关闭后台扫描，支持按需配置指定应用扫描隔离（${ShizukuHelper.getTargetAppsSummary(this)}）。"
                            btnDegradedAction.text = "配置隔离 ›"
                        }
                        !shizukuAutoGrantTried -> {
                            tvXposedStatus.text = "Shizuku: 配置中..."
                            applyPillStyle(tvXposedStatus, "#D97706", "#FFFBEB", "#FDE68A")
                            tvDegradedTitle.text = "正在自动放行模拟位置..."
                            tvDegradedDesc.text = "正在通过 Shizuku 配置模拟位置权限，请稍候。"
                            btnDegradedAction.text = "模式对比 ›"
                            grantPermissionsViaShizukuAsync()
                        }
                        else -> {
                            tvXposedStatus.text = "模拟位置: 需手动设 ›"
                            applyPillStyle(tvXposedStatus, "#DC2626", "#FEF2F2", "#FECACA")
                            tvDegradedTitle.text = "需手动设置模拟位置应用"
                            tvDegradedDesc.text = "当前系统限制自动授权，请在「开发者选项」中手动选择本应用。"
                            btnDegradedAction.text = "去开发者选项 ›"
                        }
                    }
                } else if (hasShizukuBinder) {
                    tvXposedStatus.text = "Shizuku: 待授权 ›"
                    applyPillStyle(tvXposedStatus, "#D97706", "#FFFBEB", "#FDE68A")
                    tvDegradedTitle.text = "Shizuku 运行中，请授予权限"
                    tvDegradedDesc.text = "点击右侧「立即授权」即可自动放行模拟位置与后台扫描管理。"
                    btnDegradedAction.text = "立即授权 ›"
                } else {
                    val installed = ShizukuHelper.isShizukuInstalled(this)
                    tvXposedStatus.text = if (installed) "Shizuku: 未启动 ›" else "Shizuku: 未安装 ›"
                    applyPillStyle(tvXposedStatus, "#DC2626", "#FEF2F2", "#FECACA")
                    tvDegradedTitle.text = if (installed) "Shizuku 服务未启动" else "未安装 Shizuku"
                    tvDegradedDesc.text = if (installed) {
                        "请打开 Shizuku 通过无线调试启动服务后返回。"
                    } else {
                        "安装并启动 Shizuku 后可自动授权，或切换为纯免 Root 模式。"
                    }
                    btnDegradedAction.text = if (installed) "打开Shizuku ›" else "获取Shizuku ›"
                }

                val hasTargets = ShizukuHelper.getTargetApps(this).isNotEmpty()
                setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                setCheckboxState(
                    cbAntiDrift,
                    enabled = true,
                    checked = shizukuIsolateTargetApps && hasTargets,
                    label = "隔离应用扫描"
                )
                setCheckboxState(
                    cbIptables,
                    enabled = true,
                    checked = shizukuAutoSuppressScan,
                    label = "自动关扫描"
                )
                setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
                setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·需Root+LSPosed)")
            }

            WorkMode.ROOTLESS_BASIC -> {
                val mockAllowed = RootUtils.isMockLocationAllowed(this)
                tvSubtitleMode.text = "纯免 Root · 基础模式"
                tvRootStatus.text = "模式: 纯免Root基础 ›"
                applyPillStyle(tvRootStatus, "#D97706", "#FFFBEB", "#FDE68A")

                cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                if (mockAllowed) {
                    tvXposedStatus.text = "模拟定位: 已授权 ✓"
                    applyPillStyle(tvXposedStatus, "#059669", "#ECFDF5", "#A7F3D0")
                    tvDegradedTitle.text = "纯免 Root 模式已就绪"
                    tvDegradedDesc.text = "已启用系统模拟定位与局部 DNS 阻断；建议在系统位置设置关闭 Wi-Fi/蓝牙扫描。"
                    btnDegradedAction.text = "防漂移指南 ›"
                } else {
                    tvXposedStatus.text = "模拟定位: 未设置 ›"
                    applyPillStyle(tvXposedStatus, "#DC2626", "#FEF2F2", "#FECACA")
                    tvDegradedTitle.text = "需先在开发者选项授权模拟位置"
                    tvDegradedDesc.text = "请在「开发者选项 → 选择模拟位置信息应用」中选中本应用。"
                    btnDegradedAction.text = "去授权设置 ›"
                }

                setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
                setCheckboxState(cbStepSim, enabled = false, checked = false, label = "步数模拟(实验性·需Root+LSPosed)")
            }
        }
        refreshStrategyToggleBadge()
    }

    private fun handleShizukuConnectAction() {
        refreshShizukuState()
        when {
            hasShizukuPermission -> {
                shizukuAutoGrantTried = false
                grantPermissionsViaShizukuAsync(showResultToast = true)
            }
            hasShizukuBinder -> {
                if (ShizukuHelper.shouldShowRationale()) {
                    toast("请在 Shizuku「已授权应用」中允许本应用")
                    ShizukuHelper.openShizukuApp(this)
                } else {
                    ShizukuHelper.requestPermission(REQ_SHIZUKU)
                }
            }
            else -> {
                val opened = ShizukuHelper.openShizukuApp(this)
                if (opened) {
                    toast("请在 Shizuku 启动无线调试服务后返回")
                } else {
                    toast("正在打开 Shizuku 下载页")
                }
            }
        }
    }

    private fun showTargetAppsConfigDialog() {
        val currentList = ShizukuHelper.getTargetApps(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        scroll.addView(root)

        val tvHint = TextView(this).apply {
            text = "开启漂移时通过 Shizuku 临时限制所选应用包名的 Wi-Fi 与蓝牙扫描权限（保留 GPS 权限通道），停止漂移自动恢复："
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvHint)

        val tvCustomLabel = TextView(this).apply {
            text = "目标应用包名（多个包名可用逗号或换行分隔，留空则不隔离）："
            setTextColor(Color.parseColor("#334155"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(tvCustomLabel)

        val etCustom = EditText(this).apply {
            hint = "如 com.example.app"
            setText(currentList.joinToString(", "))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(etCustom)

        AlertDialog.Builder(this)
            .setTitle("配置扫描隔离目标包名")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val result = LinkedHashSet<String>()
                etCustom.text.toString()
                    .split(Regex("[,，\\s]+"))
                    .map { it.trim() }
                    .filter { it.contains(".") }
                    .forEach { result.add(it) }

                val finalList = result.toList()
                ShizukuHelper.setTargetApps(this, finalList)
                shizukuIsolateTargetApps = finalList.isNotEmpty()
                getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_SHIZUKU_ISOLATE_TARGET_APPS, shizukuIsolateTargetApps)
                    .apply()
                updateEnvironmentUI()

                if (isEnabled && selectedMode == WorkMode.SHIZUKU) {
                    ShizukuHelper.runShizukuTask {
                        ShizukuHelper.restoreTargetAppLocationIsolation(this)
                        if (finalList.isNotEmpty()) {
                            val count = ShizukuHelper.applyTargetAppLocationIsolation(this, finalList)
                            runOnUiThread {
                                toast("已更新并隔离 $count 个指定应用")
                            }
                        } else {
                            runOnUiThread {
                                toast("已清除隔离目标并恢复权限")
                            }
                        }
                    }
                } else {
                    toast("已保存隔离目标配置")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModeSelectionDialog(isAutoStartup: Boolean) {
        modeDialog?.dismiss()
        refreshShizukuState()

        var pendingMode = selectedMode

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        scroll.addView(root)

        val tvIntro = TextView(this).apply {
            text = "选择运行模式（支持随时在顶部「切换模式」更改）："
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(tvIntro)

        data class ModeCardRefs(
            val mode: WorkMode,
            val card: LinearLayout,
            val radio: TextView,
            val statusBadge: TextView
        )

        val cardRefs = ArrayList<ModeCardRefs>()

        fun buildModeCard(
            mode: WorkMode,
            title: String,
            subtitleTag: String,
            pros: String,
            cons: String
        ): ModeCardRefs {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val radio = TextView(this).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dp(6), 0)
            }

            val tvTitle = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#0F172A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusBadge = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }

            topRow.addView(radio)
            topRow.addView(tvTitle)
            topRow.addView(statusBadge)
            card.addView(topRow)

            val tvSub = TextView(this).apply {
                text = subtitleTag
                setTextColor(Color.parseColor("#2563EB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(20), dp(2), 0, dp(4))
            }
            card.addView(tvSub)

            val tvPros = TextView(this).apply {
                text = pros
                setTextColor(Color.parseColor("#1E293B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(20), 0, 0, 0)
            }
            card.addView(tvPros)

            val tvCons = TextView(this).apply {
                text = cons
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(20), dp(3), 0, 0)
            }
            card.addView(tvCons)

            root.addView(card)
            return ModeCardRefs(mode, card, radio, statusBadge)
        }

        val cRoot = buildModeCard(
            WorkMode.ROOT_LSPOSED,
            "1. Root + LSPosed 完整模式",
            "最强防漂移 · 零三方应用注入",
            "✓ 系统层合成虚拟 GPS 与 12 颗工作卫星\n" +
                "✓ 抹除模拟位置标记，隔离周边 Wi-Fi 与基站\n" +
                "✓ 内核 iptables 阻断网络定位接口",
            "✗ 需已 Root 并在 LSPosed 启用模块后重启"
        )
        cardRefs.add(cRoot)

        val cShizuku = buildModeCard(
            WorkMode.SHIZUKU,
            "2. 免 Root + Shizuku 增强模式",
            "免 Root 推荐 · 自动授权 + 扫描管理",
            "✓ 无需 Root，Shizuku 自动放行模拟位置\n" +
                "✓ 漂移时自动关闭系统后台扫描，停止自动恢复\n" +
                "✓ 支持指定应用扫描隔离与局部 DNS 分流阻断",
            "✗ 受限于非 Root 权限，无法抹除底层模拟标记或隔离蜂窝基站"
        )
        cardRefs.add(cShizuku)

        val cBasic = buildModeCard(
            WorkMode.ROOTLESS_BASIC,
            "3. 纯免 Root 基础模式",
            "零外部依赖 · 开箱即用",
            "✓ 无需 Root 与 Shizuku，系统模拟定位 + 局部 VPN\n" +
                "✓ 仅拦截网络定位域名，正常网络通信畅通",
            "✗ 需手动在开发者选项授权并关闭后台扫描"
        )
        cardRefs.add(cBasic)

        val shizukuActionBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedCard("#EFF6FF", "#BFDBFE", 12)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        val tvShizukuHint = TextView(this).apply {
            setTextColor(Color.parseColor("#1E3A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnShizukuQuick = Button(this).apply {
            textSize = 11.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setPadding(dp(11), dp(4), dp(11), dp(4))
            minimumHeight = 0
            minHeight = dp(32)
            setOnClickListener {
                handleShizukuConnectAction()
            }
        }
        shizukuActionBox.addView(tvShizukuHint)
        shizukuActionBox.addView(btnShizukuQuick)
        root.addView(shizukuActionBox)

        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val cbRememberSkip = CheckBox(this).apply {
            text = "下次启动直接进入当前模式"
            isChecked = uiPrefs.getBoolean(KEY_SKIP_STARTUP_MODE_DIALOG, false)
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            setOnCheckedChangeListener { _, isChecked ->
                uiPrefs.edit().putBoolean(KEY_SKIP_STARTUP_MODE_DIALOG, isChecked).apply()
            }
        }
        root.addView(cbRememberSkip)

        fun updateCardsVisual() {
            refreshShizukuState()
            val mockAllowed = RootUtils.isMockLocationAllowed(this)

            for (item in cardRefs) {
                val isSelected = item.mode == pendingMode
                item.card.background = if (isSelected) {
                    createRoundedCard("#EFF6FF", "#2563EB", 13)
                } else {
                    createRoundedCard("#F8FAFC", "#E2E8F0", 13)
                }
                item.radio.text = if (isSelected) "◉" else "○"
                item.radio.setTextColor(
                    Color.parseColor(if (isSelected) "#2563EB" else "#94A3B8")
                )

                val (badgeText, badgeTextColor, badgeBg) = when (item.mode) {
                    WorkMode.ROOT_LSPOSED -> when {
                        !rootCheckCompleted -> Triple("检测中...", "#475569", "#E2E8F0")
                        hasRoot && hasSystemHookActive -> Triple("已就绪 ✓", "#059669", "#D1FAE5")
                        hasRoot && hasXposed -> Triple("需重启", "#D97706", "#FEF3C7")
                        hasRoot -> Triple("未激活", "#D97706", "#FEF3C7")
                        else -> Triple("无Root", "#DC2626", "#FEE2E2")
                    }
                    WorkMode.SHIZUKU -> when {
                        hasShizukuPermission && mockAllowed -> Triple("已就绪 ✓", "#059669", "#D1FAE5")
                        hasShizukuPermission -> Triple("待设模拟位置", "#D97706", "#FEF3C7")
                        hasShizukuBinder -> Triple("待授权", "#D97706", "#FEF3C7")
                        ShizukuHelper.isShizukuInstalled(this) -> Triple("未启动", "#DC2626", "#FEE2E2")
                        else -> Triple("未安装", "#64748B", "#E2E8F0")
                    }
                    WorkMode.ROOTLESS_BASIC -> when {
                        mockAllowed -> Triple("已就绪 ✓", "#059669", "#D1FAE5")
                        else -> Triple("需设模拟位置", "#D97706", "#FEF3C7")
                    }
                }
                item.statusBadge.text = badgeText
                item.statusBadge.setTextColor(Color.parseColor(badgeTextColor))
                item.statusBadge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor(badgeBg))
                }
            }

            if (pendingMode == WorkMode.SHIZUKU && !hasShizukuPermission) {
                shizukuActionBox.visibility = View.VISIBLE
                if (hasShizukuBinder) {
                    tvShizukuHint.text = "Shizuku 已运行，点击一键授权："
                    btnShizukuQuick.text = "授权"
                } else if (ShizukuHelper.isShizukuInstalled(this)) {
                    tvShizukuHint.text = "请先打开 Shizuku 启动服务："
                    btnShizukuQuick.text = "打开"
                } else {
                    tvShizukuHint.text = "尚未安装 Shizuku："
                    btnShizukuQuick.text = "下载"
                }
            } else {
                shizukuActionBox.visibility = View.GONE
            }
        }

        for (item in cardRefs) {
            item.card.setOnClickListener {
                pendingMode = item.mode
                updateCardsVisual()
            }
        }

        modeDialogRefreshCallback = { updateCardsVisual() }
        updateCardsVisual()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isAutoStartup) "选择运行模式" else "运行模式对比")
            .setView(scroll)
            .setPositiveButton("确定") { _, _ ->
                if (pendingMode == WorkMode.ROOT_LSPOSED && rootCheckCompleted && !hasRoot) {
                    toast("未检测到 Root，已切换为免 Root 模式")
                    val fallback = if (hasShizukuBinder || ShizukuHelper.isShizukuInstalled(this)) {
                        WorkMode.SHIZUKU
                    } else {
                        WorkMode.ROOTLESS_BASIC
                    }
                    saveSelectedMode(fallback)
                    applyModeDefaults(fallback)
                } else {
                    saveSelectedMode(pendingMode)
                    applyModeDefaults(pendingMode)
                    if (pendingMode == WorkMode.SHIZUKU) {
                        if (hasShizukuPermission) {
                            shizukuAutoGrantTried = false
                            grantPermissionsViaShizukuAsync(showResultToast = true)
                        } else if (hasShizukuBinder) {
                            ShizukuHelper.requestPermission(REQ_SHIZUKU)
                        }
                    }
                }
                updateEnvironmentUI()
            }
            .setNegativeButton(if (isAutoStartup) "保持当前" else "取消", null)
            .create()

        dialog.setOnDismissListener {
            modeDialogRefreshCallback = null
            if (modeDialog === dialog) {
                modeDialog = null
            }
        }
        modeDialog = dialog
        dialog.show()
    }

    private fun showFirstActivationRebootDialog() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        scroll.addView(root)

        val noticeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedCard("#FFFBEB", "#FDE68A", 12)
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        val tvTitle = TextView(this).apply {
            text = "LSPosed 模块已首次启用"
            setTextColor(Color.parseColor("#92400E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvBody = TextView(this).apply {
            text = "系统框架 (android) 与电话服务仅在开机时注入模块。请重启一次手机，重启后即可解锁防基站/Wi-Fi 与模拟标记抹除。"
            setTextColor(Color.parseColor("#78350F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        noticeCard.addView(tvTitle)
        noticeCard.addView(tvBody)
        root.addView(noticeCard)

        val builder = AlertDialog.Builder(this)
            .setTitle("需要重启手机")
            .setView(scroll)

        if (hasRoot) {
            builder.setPositiveButton("立即重启") { _, _ ->
                confirmAndRebootDevice()
            }
            builder.setNegativeButton("稍后", null)
        } else {
            builder.setPositiveButton("知道了", null)
        }
        builder.show()
    }

    private fun confirmAndRebootDevice() {
        AlertDialog.Builder(this)
            .setTitle("确认立即重启？")
            .setMessage("重启完成后打开本应用即可使用完整模式。")
            .setPositiveButton("确认") { _, _ ->
                toast("正在重启...")
                RootUtils.runRootTask {
                    val ok = RootUtils.rebootDevice()
                    if (!ok) {
                        runOnUiThread {
                            toast("自动重启失败，请手动重启")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setCheckboxState(
        box: CheckBox,
        enabled: Boolean,
        checked: Boolean? = null,
        label: String
    ) {
        suppressCheckboxCallback = true
        box.isEnabled = enabled
        box.alpha = if (enabled) 1.0f else 0.55f
        if (checked != null) {
            box.isChecked = checked
        }
        box.text = label
        suppressCheckboxCallback = false
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun createRoundedCard(bgColor: String, strokeColor: String, radiusDp: Int = 12): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bgColor))
            setStroke(dp(1), Color.parseColor(strokeColor))
        }
    }

    private fun addStatusCheckRow(
        container: LinearLayout,
        label: String,
        statusText: String,
        statusColor: String
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#334155"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvVal = TextView(this).apply {
            text = statusText
            setTextColor(Color.parseColor(statusColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(tvLabel)
        row.addView(tvVal)
        container.addView(row)
    }

    private fun addStepCard(
        container: LinearLayout,
        stepNum: String,
        title: String,
        detail: String,
        highlight: Boolean = false
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = if (highlight) {
                createRoundedCard("#FFFBEB", "#FDE68A", 12)
            } else {
                createRoundedCard("#F8FAFC", "#E2E8F0", 12)
            }
            setPadding(dp(11), dp(10), dp(11), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val badge = TextView(this).apply {
            text = stepNum
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (highlight) "#D97706" else "#2563EB"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(9)
                topMargin = dp(1)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvDetail = TextView(this).apply {
            text = detail
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
        }
        textCol.addView(tvTitle)
        textCol.addView(tvDetail)

        card.addView(badge)
        card.addView(textCol)
        container.addView(card)
    }

    private fun showEnvironmentGuideDialog() {
        val mockAllowed = RootUtils.isMockLocationAllowed(this)
        refreshShizukuState()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        scroll.addView(root)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedCard("#EFF6FF", "#BFDBFE", 12)
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        val statusHeader = TextView(this).apply {
            text = "运行环境自检"
            setTextColor(Color.parseColor("#1E3A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        statusCard.addView(statusHeader)

        when (selectedMode) {
            WorkMode.ROOT_LSPOSED -> {
                if (hasRoot && hasSystemHookActive) {
                    addStatusCheckRow(statusCard, "当前模式", "Root + LSPosed 完整版", "#059669")
                    addStatusCheckRow(statusCard, "系统GPS & 标记抹除", "已就绪", "#059669")
                    addStatusCheckRow(statusCard, "基站 / Wi-Fi 隔离", "已就绪", "#059669")
                    addStatusCheckRow(statusCard, "内核 iptables 阻断", "已就绪", "#059669")
                } else if (hasRoot && hasXposed) {
                    addStatusCheckRow(statusCard, "Root 权限", "已授权 ✓", "#059669")
                    addStatusCheckRow(statusCard, "LSPosed 模块", "已开启，待重启", "#D97706")
                    addStatusCheckRow(statusCard, "系统框架 Hook", "未加载 (需重启)", "#DC2626")
                } else {
                    addStatusCheckRow(statusCard, "Root 权限", if (hasRoot) "已授权 ✓" else "未获取 ✗", if (hasRoot) "#059669" else "#DC2626")
                    addStatusCheckRow(statusCard, "LSPosed 模块", if (hasXposed) "已开启" else "未激活", "#D97706")
                    addStatusCheckRow(statusCard, "标记抹除 / 基站隔离", "暂不可用", "#DC2626")
                }
            }
            WorkMode.SHIZUKU -> {
                addStatusCheckRow(statusCard, "当前模式", "免 Root + Shizuku 增强版", "#2563EB")
                addStatusCheckRow(
                    statusCard,
                    "1. Shizuku 授权",
                    when {
                        hasShizukuPermission -> "已授权 ✓"
                        hasShizukuBinder -> "待授权"
                        else -> "未启动 ✗"
                    },
                    if (hasShizukuPermission) "#059669" else "#DC2626"
                )
                addStatusCheckRow(
                    statusCard,
                    "2. 模拟位置权限",
                    when {
                        mockAllowed -> "已放行 ✓"
                        hasShizukuPermission && shizukuAutoGrantTried -> "需手动设置 ✗"
                        else -> "授权后自动放行"
                    },
                    if (mockAllowed) "#059669" else "#DC2626"
                )
                addStatusCheckRow(
                    statusCard,
                    "3. 指定应用扫描隔离",
                    ShizukuHelper.getTargetAppsSummary(this),
                    "#059669"
                )
                addStatusCheckRow(statusCard, "4. 自动关后台扫描", "支持 (停止自动恢复)", "#059669")
                addStatusCheckRow(statusCard, "5. 抹除底层模拟标记", "不支持 (需Root+LSPosed)", "#64748B")
            }
            WorkMode.ROOTLESS_BASIC -> {
                addStatusCheckRow(statusCard, "当前模式", "纯免 Root 基础版", "#D97706")
                addStatusCheckRow(
                    statusCard,
                    "1. 模拟位置权限",
                    if (mockAllowed) "已授权 ✓" else "未设置 ✗",
                    if (mockAllowed) "#059669" else "#DC2626"
                )
                addStatusCheckRow(statusCard, "2. 局部 DNS 阻断", "已就绪 (正常网络畅通)", "#059669")
                addStatusCheckRow(statusCard, "3. 抹除模拟标记 / 基站", "不支持 (需Root+LSPosed)", "#64748B")
            }
        }
        root.addView(statusCard)

        when (selectedMode) {
            WorkMode.SHIZUKU -> {
                addStepCard(
                    root,
                    "1",
                    if (hasShizukuPermission) "Shizuku 授权（已完成）" else "启动并授权 Shizuku",
                    "打开 Shizuku 通过「无线调试」启动服务后，返回本页点击授权。",
                    highlight = !hasShizukuPermission
                )
                addStepCard(
                    root,
                    "2",
                    "自动后台扫描管理 & 可选应用隔离",
                    "开启漂移时自动临时关闭系统后台 Wi-Fi/蓝牙扫描，并可按需配置指定应用包名的扫描权限隔离，停止漂移自动恢复。"
                )
                addStepCard(
                    root,
                    "!",
                    "免 Root 能力边界",
                    "免 Root 模式下无法抹除系统底层模拟位置标记或隔离蜂窝基站，强环境校验场景需在 Root + LSPosed 完整模式下使用。"
                )
            }
            WorkMode.ROOTLESS_BASIC -> {
                addStepCard(
                    root,
                    "1",
                    if (mockAllowed) "模拟位置（已完成）" else "在开发者选项中授权模拟位置",
                    "进入「设置 → 开发者选项 → 选择模拟位置信息应用」，选中「定位漂移助手」。",
                    highlight = !mockAllowed
                )
                addStepCard(
                    root,
                    "2",
                    "关闭系统 Wi-Fi / 蓝牙扫描减少回弹",
                    "在系统「设置 → 位置信息」关闭 Wi-Fi 扫描与蓝牙扫描，并清理目标应用后台后再开启漂移。"
                )
                addStepCard(
                    root,
                    "!",
                    "免 Root 能力边界",
                    "纯免 Root 模式适用于常规地图与位置测试；强环境校验场景需在 Root + LSPosed 完整模式下使用。"
                )
            }
            WorkMode.ROOT_LSPOSED -> {
                if (hasXposed && !hasSystemHookActive) {
                    addStepCard(
                        root,
                        "1",
                        "重启一次手机以加载 Hook",
                        "LSPosed 模块已开启，重启设备后完整功能生效。",
                        highlight = true
                    )
                } else if (!hasXposed) {
                    addStepCard(
                        root,
                        "1",
                        "在 LSPosed 启用模块并重启",
                        "勾选作用域「系统框架 (android)」与「电话服务 (com.android.phone)」后重启。",
                        highlight = true
                    )
                } else {
                    addStepCard(
                        root,
                        "1",
                        "推荐勾选策略",
                        "保持「系统GPS注入」「防基站/WiFi漂移」「内核阻断」三项开启即可。"
                    )
                    addStepCard(
                        root,
                        "2",
                        "实时切换、摇杆与轨迹模拟",
                        "漂移中支持悬浮摇杆实时控向，或提前绘制多点轨迹并按设定速度自动巡航。"
                    )
                }
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("环境自检与指引")
            .setView(scroll)
            .setNegativeButton("知道了", null)
            .setNeutralButton("切换模式") { _, _ ->
                showModeSelectionDialog(isAutoStartup = false)
            }

        when {
            selectedMode == WorkMode.SHIZUKU && !hasShizukuPermission -> {
                builder.setPositiveButton(if (hasShizukuBinder) "立即授权" else "打开 Shizuku") { _, _ ->
                    handleShizukuConnectAction()
                }
            }
            selectedMode == WorkMode.SHIZUKU && !mockAllowed -> {
                builder.setPositiveButton("打开开发者选项") { _, _ ->
                    openDeveloperOptions()
                }
            }
            selectedMode == WorkMode.SHIZUKU -> {
                builder.setPositiveButton("配置隔离目标") { _, _ ->
                    showTargetAppsConfigDialog()
                }
            }
            selectedMode == WorkMode.ROOTLESS_BASIC -> {
                builder.setPositiveButton("打开开发者选项") { _, _ ->
                    openDeveloperOptions()
                }
            }
            selectedMode == WorkMode.ROOT_LSPOSED && hasXposed && !hasSystemHookActive && hasRoot -> {
                builder.setPositiveButton("立即重启") { _, _ ->
                    confirmAndRebootDevice()
                }
            }
        }
        builder.show()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                toast("请连续点击版本号 7 次开启开发者选项")
            } catch (_: Throwable) {
                toast("请手动打开系统设置")
            }
        }
    }

    private fun refreshFavoritesCount() {
        val count = ConfigManager.getFavorites(this).size
        btnFavorites.text = if (count > 0) "收藏夹($count)" else "收藏夹"
    }

    private fun requestBasicPermissionsIfNeeded() {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    private fun startDrifting(autoStartRoute: Boolean = false) {
        if (!cbSystemMock.isChecked && !cbVpn.isChecked && !cbIptables.isChecked && !cbAntiDrift.isChecked) {
            toast("请至少勾选一项策略")
            return
        }

        when (selectedMode) {
            WorkMode.SHIZUKU -> {
                if (!hasShizukuPermission) {
                    toast("请先授权 Shizuku")
                    handleShizukuConnectAction()
                    return
                }
                val cfg = buildCurrentConfig(enabled = true)
                val suppressScan = shizukuAutoSuppressScan
                val isolateApps = shizukuIsolateTargetApps
                val alreadyMockAllowed = RootUtils.isMockLocationAllowed(this)

                isEnabled = true
                ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                refreshRunStatusUI(animate = true)
                if (alreadyMockAllowed) {
                    launchForegroundServices(cfg)
                    if (autoStartRoute) {
                        startRoutePlaybackService()
                    }
                }

                ShizukuHelper.runShizukuTask {
                    val mockOk = if (alreadyMockAllowed) {
                        true
                    } else {
                        ShizukuHelper.grantMockAndLocationPermissions(this)
                    }
                    shizukuAutoGrantTried = true

                    if (cfg.enableSystemMock && !mockOk) {
                        runOnUiThread {
                            isEnabled = false
                            ConfigManager.save(this, cfg.copy(enabled = false), syncTmpWithRoot = false)
                            stopVpnService()
                            updateEnvironmentUI()
                            refreshRunStatusUI(animate = true)
                            toast("请在开发者选项中手动选择本应用")
                            openDeveloperOptions()
                        }
                        return@runShizukuTask
                    }

                    val isolatedCount = ShizukuHelper.applyBatchAntiDrift(
                        this,
                        suppressScan = suppressScan,
                        isolateTargets = isolateApps
                    )

                    runOnUiThread {
                        if (!alreadyMockAllowed && isEnabled) {
                            launchForegroundServices(cfg)
                            if (autoStartRoute) {
                                startRoutePlaybackService()
                            }
                        }
                        updateEnvironmentUI()
                        if (!autoStartRoute) {
                            val msg = when {
                                isolatedCount > 0 -> "已开启（已隔离 $isolatedCount 个指定应用）"
                                suppressScan -> "已开启（已自动关扫描）"
                                else -> "已开启定位漂移"
                            }
                            toast(msg)
                        }
                    }
                }
            }

            WorkMode.ROOTLESS_BASIC -> {
                if (cbSystemMock.isChecked && !RootUtils.isMockLocationAllowed(this)) {
                    toast("请先在开发者选项授权模拟位置")
                    showEnvironmentGuideDialog()
                    return
                }
                isEnabled = true
                val cfg = buildCurrentConfig(enabled = true)
                ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                refreshRunStatusUI(animate = true)
                launchForegroundServices(cfg)
                if (autoStartRoute) {
                    startRoutePlaybackService()
                } else {
                    toast("已开启定位漂移")
                }
            }

            WorkMode.ROOT_LSPOSED -> {
                if (!hasRoot) {
                    if (cbSystemMock.isChecked && !RootUtils.isMockLocationAllowed(this)) {
                        toast("无 Root 权限，请切换为免 Root 模式")
                        showModeSelectionDialog(isAutoStartup = false)
                        return
                    }
                }
                isEnabled = true
                val cfg = buildCurrentConfig(enabled = true)
                ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                val json = ConfigManager.toJson(cfg).toString()
                refreshRunStatusUI(animate = true)
                launchForegroundServices(cfg)
                if (autoStartRoute) {
                    startRoutePlaybackService()
                } else {
                    toast("已开启定位漂移")
                }

                if (hasRoot) {
                    RootUtils.runRootTask {
                        RootUtils.applyRootConfigAndIptables(json, cfg.enableIptables)
                    }
                }
            }
        }
    }

    private fun launchForegroundServices(cfg: ConfigManager.DriftConfig) {
        if (cfg.enableSystemMock) {
            startMockLocationService()
        }
        if (cfg.enableVpn) {
            prepareAndStartVpn()
        }
    }

    private fun applyUpdatedCoordinates(showToast: Boolean = true) {
        if (selectedMode == WorkMode.ROOTLESS_BASIC &&
            cbSystemMock.isChecked &&
            !RootUtils.isMockLocationAllowed(this)
        ) {
            toast("请先在开发者选项授权模拟位置")
            showEnvironmentGuideDialog()
            return
        }

        val cfg = buildCurrentConfig(enabled = true)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()
        syncActiveServices(cfg, showToast)

        if (selectedMode == WorkMode.ROOT_LSPOSED && hasRoot) {
            RootUtils.runRootTask {
                RootUtils.applyRootConfigAndIptables(json, cfg.enableIptables)
            }
        }
    }

    private fun syncActiveServices(cfg: ConfigManager.DriftConfig, showToast: Boolean) {
        if (cfg.enableSystemMock) {
            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_UPDATE
            }
            startForegroundService(intent)
        } else {
            stopService(Intent(this, MockLocationService::class.java))
        }
        if (cfg.enableVpn && !DriftVpnService.isRunning) {
            prepareAndStartVpn()
        } else if (!cfg.enableVpn && DriftVpnService.isRunning) {
            stopVpnService()
        }
        if (showToast) {
            toast("已同步新选点")
        }
    }

    private fun stopDrifting() {
        isEnabled = false
        MockLocationService.resetRoutePlaybackState()
        val cfg = buildCurrentConfig(enabled = false)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

        refreshRunStatusUI(animate = true)
        refreshRoutePanelUI()
        stopService(Intent(this, MockLocationService::class.java))
        stopVpnService()
        toast("已停止定位漂移")

        if (hasRoot) {
            RootUtils.runRootTask {
                RootUtils.applyRootConfigAndIptables(json, enableBlock = false)
            }
        }
        ShizukuHelper.runShizukuTask {
            ShizukuHelper.restoreAllAntiDriftStates(this)
        }
    }

    private fun startMockLocationService() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBasicPermissionsIfNeeded()
            toast("请授予位置权限")
            return
        }
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQ_VPN)
        } else {
            val intent = Intent(this, DriftVpnService::class.java).apply {
                action = DriftVpnService.ACTION_START
            }
            startService(intent)
        }
    }

    private fun stopVpnService() {
        if (DriftVpnService.isRunning) {
            val intent = Intent(this, DriftVpnService::class.java).apply {
                action = DriftVpnService.ACTION_STOP
            }
            startService(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                val intent = Intent(this, DriftVpnService::class.java).apply {
                    action = DriftVpnService.ACTION_START
                }
                startService(intent)
            } else {
                suppressCheckboxCallback = true
                cbVpn.isChecked = false
                suppressCheckboxCallback = false
                refreshStrategyToggleBadge()
                if (isEnabled) {
                    ConfigManager.save(this, buildCurrentConfig(enabled = true), syncTmpWithRoot = false)
                }
                toast("未授予 VPN 权限")
            }
        } else if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startJoystickOverlayService()
            } else {
                toast("未授予悬浮窗权限")
            }
        }
    }

    private fun buildCurrentConfig(enabled: Boolean): ConfigManager.DriftConfig {
        val allowIptables = selectedMode == WorkMode.ROOT_LSPOSED && cbIptables.isChecked && hasRoot
        val allowAntiDrift = selectedMode == WorkMode.ROOT_LSPOSED && cbAntiDrift.isChecked && hasSystemHookActive
        val allowStepSim = selectedMode == WorkMode.ROOT_LSPOSED && cbStepSim.isChecked && hasRoot && hasSystemHookActive
        val currentSteps = ConfigManager.load(this).simStepCount
        return ConfigManager.DriftConfig(
            enabled = enabled,
            wgsLat = currentWgsLat,
            wgsLng = currentWgsLng,
            gcjLat = currentGcjLat,
            gcjLng = currentGcjLng,
            address = currentAddress,
            enableSystemMock = cbSystemMock.isChecked,
            blockNetworkDrift = allowAntiDrift,
            enableIptables = allowIptables,
            enableVpn = cbVpn.isChecked,
            enableJitter = true,
            updatedAt = System.currentTimeMillis(),
            enableStepSim = allowStepSim,
            simStepCount = currentSteps
        )
    }

    private fun updateCoordTexts() {
        val bd = CoordTransform.gcj02ToBd09(currentGcjLat, currentGcjLng)
        tvAddress.text = "当前选点: $currentAddress"
        tvCoords.text = String.format(
            Locale.US,
            "GCJ: %.5f, %.5f | WGS: %.5f, %.5f | BD: %.5f, %.5f",
            currentGcjLat,
            currentGcjLng,
            currentWgsLat,
            currentWgsLng,
            bd.lat,
            bd.lng
        )
    }

    private fun copyCurrentCoordsToClipboard() {
        val bd = CoordTransform.gcj02ToBd09(currentGcjLat, currentGcjLng)
        val text = String.format(
            Locale.US,
            "%s\nGCJ-02: %.6f, %.6f\nWGS-84: %.6f, %.6f\nBD-09: %.6f, %.6f",
            currentAddress,
            currentGcjLat,
            currentGcjLng,
            currentWgsLat,
            currentWgsLng,
            bd.lat,
            bd.lng
        )
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LocationDrifterCoords", text))
            toast("已复制三系坐标")
        } catch (_: Throwable) {
            toast("复制失败")
        }
    }

    private fun animateToggleDriftButton(toActive: Boolean, animate: Boolean) {
        btnColorAnimator?.cancel()
        val radius = dp(14).toFloat()
        val startStartColor = if (toActive) Color.parseColor("#3B82F6") else Color.parseColor("#EF4444")
        val endStartColor = if (toActive) Color.parseColor("#EF4444") else Color.parseColor("#3B82F6")
        val startEndColor = if (toActive) Color.parseColor("#1D4ED8") else Color.parseColor("#DC2626")
        val endEndColor = if (toActive) Color.parseColor("#DC2626") else Color.parseColor("#1D4ED8")

        if (!animate) {
            btnToggleDrift.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(endStartColor, endEndColor)
            ).apply {
                cornerRadius = radius
            }
            return
        }

        val evaluator = ArgbEvaluator()
        btnColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val c1 = evaluator.evaluate(f, startStartColor, endStartColor) as Int
                val c2 = evaluator.evaluate(f, startEndColor, endEndColor) as Int
                btnToggleDrift.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(c1, c2)
                ).apply {
                    cornerRadius = radius
                }
            }
            start()
        }
    }

    private fun startStatusPulse() {
        if (statusPulseAnimator?.isRunning == true) return
        statusPulseAnimator = ObjectAnimator.ofFloat(tvRunStatus, View.ALPHA, 1.0f, 0.55f, 1.0f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusPulse() {
        statusPulseAnimator?.cancel()
        statusPulseAnimator = null
        tvRunStatus.alpha = 1.0f
    }

    private fun refreshRunStatusUI(animate: Boolean = false) {
        val stateChanged = lastRenderedEnabled != null && lastRenderedEnabled != isEnabled
        lastRenderedEnabled = isEnabled
        val shouldAnimate = animate && stateChanged

        if (shouldAnimate) {
            TransitionManager.endTransitions(bottomControlPanel)
        }

        if (isEnabled) {
            tvRunStatus.text = when (selectedMode) {
                WorkMode.ROOT_LSPOSED -> "● 运行中"
                WorkMode.SHIZUKU -> "● Shizuku运行"
                WorkMode.ROOTLESS_BASIC -> "● 基础运行"
            }
            applyPillStyle(tvRunStatus, "#059669", "#ECFDF5", "#A7F3D0")
            btnToggleDrift.text = "停止定位漂移"
            animateToggleDriftButton(toActive = true, animate = shouldAnimate)
            if (btnUpdatePoint.visibility != View.VISIBLE) {
                btnUpdatePoint.alpha = if (shouldAnimate) 0f else 1f
                btnUpdatePoint.visibility = View.VISIBLE
                if (shouldAnimate) {
                    btnUpdatePoint.animate().alpha(1f).setDuration(200L).start()
                }
            }
            startStatusPulse()
        } else {
            tvRunStatus.text = "● 未开启"
            applyPillStyle(tvRunStatus, "#64748B", "#F8FAFC", "#E2E8F0")
            btnToggleDrift.text = "开启定位漂移"
            animateToggleDriftButton(toActive = false, animate = shouldAnimate)
            btnUpdatePoint.visibility = View.GONE
            stopStatusPulse()
        }
    }

    private fun showManualCoordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        val etInput = EditText(this).apply {
            hint = "如 39.9088,116.3974"
            val clipText = getClipboardText()
            if (!clipText.isNullOrBlank() && clipText.contains(",")) {
                setText(clipText.trim())
            }
        }
        layout.addView(etInput)

        val rgType = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val rbGcj = RadioButton(this).apply {
            id = View.generateViewId()
            text = "GCJ-02"
            isChecked = true
        }
        val rbWgs = RadioButton(this).apply {
            id = View.generateViewId()
            text = "WGS-84"
        }
        val rbBd = RadioButton(this).apply {
            id = View.generateViewId()
            text = "BD-09"
        }
        rgType.addView(rbGcj)
        rgType.addView(rbWgs)
        rgType.addView(rbBd)
        layout.addView(rgType)

        AlertDialog.Builder(this)
            .setTitle("输入坐标")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val raw = etInput.text.toString().trim()
                val parts = raw.split(Regex("[,，\\s]+")).mapNotNull { it.toDoubleOrNull() }
                if (parts.size < 2) {
                    toast("坐标格式无效")
                    return@setPositiveButton
                }
                var lat = parts[0]
                var lng = parts[1]
                if (kotlin.math.abs(lat) > 60.0 && kotlin.math.abs(lng) <= 60.0) {
                    val tmp = lat
                    lat = lng
                    lng = tmp
                }

                val gcj: CoordTransform.LatLng
                val wgs: CoordTransform.LatLng
                when (rgType.checkedRadioButtonId) {
                    rbWgs.id -> {
                        wgs = CoordTransform.LatLng(lat, lng)
                        gcj = CoordTransform.wgs84ToGcj02(lat, lng)
                    }
                    rbBd.id -> {
                        gcj = CoordTransform.bd09ToGcj02(lat, lng)
                        wgs = CoordTransform.gcj02ToWgs84(gcj.lat, gcj.lng)
                    }
                    else -> {
                        gcj = CoordTransform.LatLng(lat, lng)
                        wgs = CoordTransform.gcj02ToWgs84(lat, lng)
                    }
                }

                applyPickedLocation(gcj.lat, gcj.lng, wgs.lat, wgs.lng, "手动坐标")
                syncMarkerToWebMap(gcj.lat, gcj.lng, "手动坐标")
                if (isEnabled) {
                    applyUpdatedCoordinates()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddFavoriteDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val etName = EditText(this).apply {
            setText(currentAddress)
            hint = "位置备注名"
        }
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("收藏当前点")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { "未命名地点" }
                ConfigManager.addFavorite(
                    this,
                    ConfigManager.FavoriteSpot(name, currentGcjLat, currentGcjLng, currentWgsLat, currentWgsLng)
                )
                refreshFavoritesCount()
                toast("已收藏：$name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavoritesDialog() {
        val list = ConfigManager.getFavorites(this)
        if (list.isEmpty()) {
            toast("收藏夹为空")
            return
        }
        val items = list.map {
            String.format(Locale.US, "%s  (%.4f, %.4f)", it.name, it.gcjLat, it.gcjLng)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("收藏夹")
            .setItems(items) { _, which ->
                val spot = list[which]
                applyPickedLocation(spot.gcjLat, spot.gcjLng, spot.wgsLat, spot.wgsLng, spot.name)
                syncMarkerToWebMap(spot.gcjLat, spot.gcjLng, spot.name)
                if (isEnabled) {
                    applyUpdatedCoordinates()
                }
            }
            .setNeutralButton("管理") { _, _ ->
                showDeleteFavoriteDialog(list)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDeleteFavoriteDialog(list: List<ConfigManager.FavoriteSpot>) {
        val items = list.map { "删除：${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("删除收藏")
            .setItems(items) { _, which ->
                val removedName = list[which].name
                ConfigManager.removeFavorite(this, which)
                refreshFavoritesCount()
                toast("已删除：$removedName")
            }
            .setNeutralButton("清空") { _, _ ->
                ConfigManager.clearFavorites(this)
                refreshFavoritesCount()
                toast("已清空收藏")
            }
            .setNegativeButton("返回") { _, _ ->
                showFavoritesDialog()
            }
            .show()
    }

    private fun getClipboardText(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyPickedLocation(
        gcjLat: Double,
        gcjLng: Double,
        wgsLat: Double,
        wgsLng: Double,
        address: String
    ) {
        currentGcjLat = gcjLat
        currentGcjLng = gcjLng
        currentWgsLat = wgsLat
        currentWgsLng = wgsLng
        currentAddress = address.ifBlank { "地图选点" }
        updateCoordTexts()

        if (!isEnabled) {
            ConfigManager.save(this, buildCurrentConfig(enabled = false), syncTmpWithRoot = false)
        }
    }

    private fun escapeJsString(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
    }

    private fun syncMarkerToWebMap(gcjLat: Double, gcjLng: Double, label: String) {
        val safeLabel = escapeJsString(label)
        val js = String.format(Locale.US, "window.setPoint(%.8f, %.8f, '%s')", gcjLat, gcjLng, safeLabel)
        webMap.evaluateJavascript(js, null)
    }

    private fun httpGetText(urlStr: String, timeoutMs: Int = 3200, referer: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                }
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun searchViaDomesticPoi(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
        val url = "https://map.baidu.com/?newmap=1&reqflag=pcmap&biz=1&from=webmap" +
            "&da_par=direct&pcevaname=pc4.1&qt=s&da_src=searchBox.button" +
            "&wd=$encodedQuery&c=1&src=0&wd2=&pn=0&sug=0"
        val body = httpGetText(url, timeoutMs = 3200, referer = "https://map.baidu.com/") ?: return emptyList()
        return try {
            val rootObj = JSONObject(body)
            val list = ArrayList<PlaceSearchResult>()

            val contentArr = rootObj.optJSONArray("content")
            if (contentArr != null) {
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim().ifBlank { fallbackName }
                    val addr = item.optString("addr").trim()
                    val mcX = item.optDouble("x", Double.NaN).let {
                        if (it.isNaN()) item.optString("diPointX").toDoubleOrNull() ?: Double.NaN else it
                    }
                    val mcY = item.optDouble("y", Double.NaN).let {
                        if (it.isNaN()) item.optString("diPointY").toDoubleOrNull() ?: Double.NaN else it
                    }
                    if (mcX.isNaN() || mcY.isNaN() || (mcX == 0.0 && mcY == 0.0)) continue
                    val gcj = CoordTransform.bd09McToGcj02(mcX, mcY)
                    if (gcj.lat.isNaN() || gcj.lng.isNaN()) continue
                    list.add(PlaceSearchResult(gcj.lat, gcj.lng, name, addr.ifBlank { name }))
                }
            }

            if (list.isEmpty()) {
                val contentObj = rootObj.optJSONObject("content")
                if (contentObj != null) {
                    val cname = contentObj.optString("cname", fallbackName).trim().ifBlank { fallbackName }
                    val geo = contentObj.optString("geo")
                    val coordPair = geo.substringAfter("|", "").substringBefore(";")
                    val xy = coordPair.split(",")
                    if (xy.size >= 2) {
                        val mcX = xy[0].toDoubleOrNull()
                        val mcY = xy[1].toDoubleOrNull()
                        if (mcX != null && mcY != null) {
                            val gcj = CoordTransform.bd09McToGcj02(mcX, mcY)
                            list.add(PlaceSearchResult(gcj.lat, gcj.lng, cname, cname))
                        }
                    }
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchViaDomesticSuggest(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
        val url = "https://map.baidu.com/su?wd=$encodedQuery&cid=1&type=0&newmap=1"
        val body = httpGetText(url, timeoutMs = 3000, referer = "https://map.baidu.com/") ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("s") ?: return emptyList()
            val list = ArrayList<PlaceSearchResult>()
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i)
                if (raw.isBlank()) continue
                val parts = raw.split("$")
                if (parts.size < 5) continue
                val title = parts[0].trim().ifBlank { fallbackName }
                val area = parts[1].trim()
                val sub = parts[2].trim()
                val mcX = parts[3].trim().toDoubleOrNull() ?: continue
                val mcY = parts[4].trim().toDoubleOrNull() ?: continue
                if (mcX == 0.0 && mcY == 0.0) continue
                val gcj = CoordTransform.bd09McToGcj02(mcX, mcY)
                if (gcj.lat.isNaN() || gcj.lng.isNaN()) continue
                val detail = listOf(area, sub, title)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · ")
                list.add(PlaceSearchResult(gcj.lat, gcj.lng, title, detail))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchViaArcGis(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
        val url = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates" +
            "?f=json&singleLine=$encodedQuery&maxLocations=5&outFields=PlaceName,Match_addr"
        val body = httpGetText(url, timeoutMs = 3200) ?: return emptyList()
        return try {
            val candidates = JSONObject(body).optJSONArray("candidates") ?: return emptyList()
            val list = ArrayList<PlaceSearchResult>()
            for (i in 0 until candidates.length()) {
                val item = candidates.optJSONObject(i) ?: continue
                val loc = item.optJSONObject("location") ?: continue
                val wgsLat = loc.optDouble("y", Double.NaN)
                val wgsLng = loc.optDouble("x", Double.NaN)
                if (wgsLat.isNaN() || wgsLng.isNaN()) continue
                val gcj = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
                val fullAddr = item.optString("address", fallbackName).trim()
                val placeName = item.optJSONObject("attributes")?.optString("PlaceName").orEmpty().trim()
                val label = placeName.ifBlank { fullAddr.substringBefore(",").ifBlank { fallbackName } }
                list.add(PlaceSearchResult(gcj.lat, gcj.lng, label, fullAddr))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchViaPhoton(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
        val url = "https://photon.komoot.io/api/?limit=5&q=$encodedQuery"
        val body = httpGetText(url, timeoutMs = 3200) ?: return emptyList()
        return try {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val list = ArrayList<PlaceSearchResult>()
            for (i in 0 until features.length()) {
                val feat = features.optJSONObject(i) ?: continue
                val coords = feat.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val wgsLng = coords.optDouble(0, Double.NaN)
                val wgsLat = coords.optDouble(1, Double.NaN)
                if (wgsLat.isNaN() || wgsLng.isNaN()) continue
                val gcj = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
                val props = feat.optJSONObject("properties")
                val name = props?.optString("name").orEmpty().ifBlank { fallbackName }
                val detail = listOfNotNull(
                    props?.optString("country"),
                    props?.optString("state"),
                    props?.optString("city"),
                    name
                ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                list.add(PlaceSearchResult(gcj.lat, gcj.lng, name, detail))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun searchViaSystemGeocoder(query: String): List<PlaceSearchResult> {
        return try {
            if (!Geocoder.isPresent()) return emptyList()
            val rawList = Geocoder(this, Locale.CHINA).getFromLocationName(query, 5) ?: return emptyList()
            val list = ArrayList<PlaceSearchResult>()
            for (addr in rawList) {
                if (!addr.hasLatitude() || !addr.hasLongitude()) continue
                val gcj = CoordTransform.wgs84ToGcj02(addr.latitude, addr.longitude)
                val name = (addr.featureName ?: addr.locality ?: query).ifBlank { query }
                val line = addr.getAddressLine(0).orEmpty().ifBlank { name }
                list.add(PlaceSearchResult(gcj.lat, gcj.lng, name, line))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchViaBuiltinCities(query: String): List<PlaceSearchResult> {
        val cleaned = query.trim().removeSuffix("市").removeSuffix("省").removeSuffix("特别行政区")
        if (cleaned.isEmpty()) return emptyList()
        val matched = BUILTIN_CITIES_GCJ02.filter {
            it.first == cleaned || cleaned.startsWith(it.first) || it.first.startsWith(cleaned)
        }
        return matched.map {
            PlaceSearchResult(it.second.first, it.second.second, it.first, "离线城市坐标")
        }
    }

    private fun performPlaceSearch(query: String, gen: Int) {
        searchPool.execute {
            val encoded = try {
                URLEncoder.encode(query, "UTF-8")
            } catch (_: Throwable) {
                query
            }

            val fPoi = searchPool.submit(Callable { searchViaDomesticPoi(encoded, query) })
            val fSug = searchPool.submit(Callable { searchViaDomesticSuggest(encoded, query) })
            val fArcGis = searchPool.submit(Callable { searchViaArcGis(encoded, query) })
            val fPhoton = searchPool.submit(Callable { searchViaPhoton(encoded, query) })

            val deadline = System.currentTimeMillis() + 4200L
            var candidates: List<PlaceSearchResult> = emptyList()

            while (System.currentTimeMillis() < deadline) {
                if (searchGeneration.get() != gen) break

                val poiRes = if (fPoi.isDone) runCatching { fPoi.get() }.getOrDefault(emptyList()) else emptyList()
                if (poiRes.isNotEmpty()) {
                    candidates = poiRes
                    break
                }

                val sugRes = if (fSug.isDone) runCatching { fSug.get() }.getOrDefault(emptyList()) else emptyList()
                if (sugRes.isNotEmpty()) {
                    candidates = sugRes
                    break
                }

                val bothDomesticDone = fPoi.isDone && fSug.isDone
                val arcRes = if (fArcGis.isDone) runCatching { fArcGis.get() }.getOrDefault(emptyList()) else emptyList()
                val phoRes = if (fPhoton.isDone) runCatching { fPhoton.get() }.getOrDefault(emptyList()) else emptyList()

                if (bothDomesticDone && (arcRes.isNotEmpty() || phoRes.isNotEmpty())) {
                    candidates = arcRes.ifEmpty { phoRes }
                    break
                }
                if (bothDomesticDone && fArcGis.isDone && fPhoton.isDone) {
                    break
                }

                try {
                    Thread.sleep(60L)
                } catch (_: InterruptedException) {
                    break
                }
            }

            if (candidates.isEmpty()) {
                candidates = runCatching { fPoi.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
                    .ifEmpty { runCatching { fSug.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList()) }
                    .ifEmpty { runCatching { fArcGis.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList()) }
                    .ifEmpty { runCatching { fPhoton.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList()) }
                    .ifEmpty { searchViaBuiltinCities(query) }
                    .ifEmpty {
                        val fSys = searchPool.submit(Callable { searchViaSystemGeocoder(query) })
                        runCatching { fSys.get(1500, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
                    }
            }

            fPoi.cancel(true)
            fSug.cancel(true)
            fArcGis.cancel(true)
            fPhoton.cancel(true)

            runOnUiThread {
                if (isFinishing || isDestroyed || searchGeneration.get() != gen) return@runOnUiThread
                btnSearchPlace.isEnabled = true
                btnSearchPlace.text = "定位"
                if (candidates.isNotEmpty()) {
                    showNativeCandidates(candidates.take(5))
                } else {
                    hideSearchCandidates()
                    toast("未找到「$query」")
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class MapJsBridge {
        @JavascriptInterface
        fun onLocationPicked(
            gcjLat: Double,
            gcjLng: Double,
            wgsLat: Double,
            wgsLng: Double,
            address: String
        ) {
            runOnUiThread {
                hideSearchCandidates()
                applyPickedLocation(gcjLat, gcjLng, wgsLat, wgsLng, address)
            }
        }

        @JavascriptInterface
        fun onRouteChanged(pointsJson: String, totalMeters: Double) {
            runOnUiThread {
                val parsed = RouteManager.parsePointsJson(pointsJson)
                activeRoutePoints = parsed
                routeTotalMeters = totalMeters
                RouteManager.setActiveRoutePoints(this@MainActivity, parsed)
                refreshRoutePanelUI()
            }
        }

        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                syncMarkerToWebMap(currentGcjLat, currentGcjLng, currentAddress)
                if (activeRoutePoints.isNotEmpty()) {
                    syncRouteToWebMap(activeRoutePoints)
                }
            }
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                toast(msg)
            }
        }
    }
}
