package com.separ.locationdrifter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
import com.separ.locationdrifter.service.DriftVpnService
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
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 101
        private const val REQ_VPN = 102
        private const val REQ_SHIZUKU = 103
        private const val UI_PREFS_NAME = "ui_state_prefs"
        private const val KEY_XPOSED_REBOOT_PROMPTED = "xposed_reboot_prompted"
        private const val KEY_SELECTED_WORK_MODE = "selected_work_mode"
        private const val KEY_SKIP_STARTUP_MODE_DIALOG = "skip_startup_mode_dialog"
        private const val KEY_SHIZUKU_AUTO_SUPPRESS_SCAN = "shizuku_auto_suppress_scan"
        private val searchExecutor = Executors.newSingleThreadExecutor()

        // 常见直辖市、省会及计划单列市 GCJ-02 离线兜底坐标（弱网或离线时搜索城市名依然秒级定位）
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
            "郑州" to (34.746599 to 113.625368),
            "济南" to (36.651216 to 117.120098),
            "青岛" to (36.067108 to 120.382609),
            "苏州" to (31.298974 to 120.585289),
            "天津" to (39.084158 to 117.200983),
            "合肥" to (31.820586 to 117.227239),
            "福州" to (26.074508 to 119.296494),
            "厦门" to (24.479834 to 118.089425),
            "昆明" to (24.880095 to 102.832891),
            "贵阳" to (26.647661 to 106.630154),
            "南宁" to (22.817002 to 108.366543),
            "海口" to (20.044002 to 110.198293),
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
    private lateinit var tvAddress: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnCopyCoord: TextView

    private lateinit var webMap: WebView
    private lateinit var cbSystemMock: CheckBox
    private lateinit var cbAntiDrift: CheckBox
    private lateinit var cbIptables: CheckBox
    private lateinit var cbVpn: CheckBox

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
    private var isEnabled = false
    private var suppressCheckboxCallback = false

    private var modeDialog: AlertDialog? = null
    private var modeDialogRefreshCallback: (() -> Unit)? = null

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
        loadSavedState()
        bindCheckboxListeners()
        initWebView()
        checkEnvironmentStatus()
        requestBasicPermissionsIfNeeded()
    }

    override fun onDestroy() {
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
        ShizukuHelper.runShizukuTask {
            val ok = ShizukuHelper.grantMockAndLocationPermissions(this)
            shizukuAutoGrantTried = true
            runOnUiThread {
                updateEnvironmentUI()
                modeDialogRefreshCallback?.invoke()
                if (showResultToast) {
                    if (ok) {
                        toast("Shizuku 已自动放行模拟位置权限 ✓")
                    } else {
                        toast("当前系统 ROM 限制自动修改模拟位置，请在开发者选项中手动选择本应用")
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
        refreshShizukuState()
        if (hasShizukuPermission && selectedMode == WorkMode.SHIZUKU && !RootUtils.isMockLocationAllowed(this) && !shizukuAutoGrantTried) {
            grantPermissionsViaShizukuAsync()
        }
        updateEnvironmentUI()
        refreshRunStatusUI()
        refreshFavoritesCount()
        modeDialogRefreshCallback?.invoke()
    }

    private fun initViews() {
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
        tvAddress = findViewById(R.id.tvAddress)
        tvCoords = findViewById(R.id.tvCoords)
        btnCopyCoord = findViewById(R.id.btnCopyCoord)

        webMap = findViewById(R.id.webMap)
        cbSystemMock = findViewById(R.id.cbSystemMock)
        cbAntiDrift = findViewById(R.id.cbAntiDrift)
        cbIptables = findViewById(R.id.cbIptables)
        cbVpn = findViewById(R.id.cbVpn)

        btnFavorites = findViewById(R.id.btnFavorites)
        btnToggleDrift = findViewById(R.id.btnToggleDrift)
        btnUpdatePoint = findViewById(R.id.btnUpdatePoint)

        btnCopyCoord.setOnClickListener { copyCurrentCoordsToClipboard() }
        findViewById<Button>(R.id.btnInputCoord).setOnClickListener { showManualCoordDialog() }
        findViewById<Button>(R.id.btnAddFavorite).setOnClickListener { showAddFavoriteDialog() }
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
            if (isEnabled) {
                stopDrifting()
            } else {
                startDrifting()
            }
        }

        btnUpdatePoint.setOnClickListener {
            if (isEnabled) {
                applyUpdatedCoordinates()
            }
        }
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
                    if (isChecked) {
                        ShizukuHelper.runShizukuTask {
                            ShizukuHelper.applyAntiDriftScanSuppression(this)
                        }
                    } else {
                        ShizukuHelper.runShizukuTask {
                            ShizukuHelper.restoreAntiDriftScanSuppression(this)
                        }
                    }
                }
                return@OnCheckedChangeListener
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
                    v.id == R.id.cbIptables ->
                        "「内核阻断」需要 Root 权限，免 Root 模式下请开启「VPN阻断(推荐)」"
                    selectedMode == WorkMode.ROOT_LSPOSED && hasXposed && !hasSystemHookActive ->
                        "LSPosed 模块已激活，需先重启手机加载系统 Hook 后才可开启「防基站/WiFi漂移」"
                    else ->
                        "「防基站/WiFi漂移」仅在「Root + LSPosed 完整模式」下可用，可点顶部「切换模式」查看区别"
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

        for (box in listOf(cbSystemMock, cbAntiDrift, cbIptables, cbVpn)) {
            box.setOnCheckedChangeListener(listener)
            box.setOnTouchListener(lockedTouchExplain)
        }
    }

    private fun loadSavedState() {
        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        WorkMode.fromKey(uiPrefs.getString(KEY_SELECTED_WORK_MODE, null))?.let {
            selectedMode = it
        }
        shizukuAutoSuppressScan = uiPrefs.getBoolean(KEY_SHIZUKU_AUTO_SUPPRESS_SCAN, true)

        suppressCheckboxCallback = true
        val cfg = ConfigManager.load(this)
        isEnabled = cfg.enabled
        currentGcjLat = cfg.gcjLat
        currentGcjLng = cfg.gcjLng
        currentWgsLat = cfg.wgsLat
        currentWgsLng = cfg.wgsLng
        currentAddress = cfg.address

        cbSystemMock.isChecked = cfg.enableSystemMock
        cbAntiDrift.isChecked = cfg.blockNetworkDrift
        cbIptables.isChecked = cfg.enableIptables
        cbVpn.isChecked = cfg.enableVpn
        suppressCheckboxCallback = false

        updateCoordTexts()
        refreshRunStatusUI()
        refreshFavoritesCount()
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
                    label = if (hasRoot && hasSystemHookActive) "防基站/WiFi漂移" else "防基站(待激活/重启)"
                )
                setCheckboxState(cbIptables, enabled = hasRoot, checked = hasRoot, label = "内核阻断")
                setCheckboxState(cbVpn, enabled = true, checked = false, label = "VPN阻断")
            }
            WorkMode.SHIZUKU -> {
                setCheckboxState(cbSystemMock, enabled = true, checked = true, label = "系统GPS注入")
                setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                setCheckboxState(
                    cbIptables,
                    enabled = true,
                    checked = shizukuAutoSuppressScan,
                    label = "自动关扫描(Shizuku)"
                )
                // 免 Root 下默认开启局部 RST-VPN 阻断腾讯/高德/百度网络定位接口，使微信等应用快速回落虚拟 GPS
                setCheckboxState(cbVpn, enabled = true, checked = true, label = "VPN阻断(推荐)")
            }
            WorkMode.ROOTLESS_BASIC -> {
                setCheckboxState(cbSystemMock, enabled = true, checked = true, label = "系统GPS注入")
                setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                setCheckboxState(cbVpn, enabled = true, checked = true, label = "VPN阻断(推荐)")
            }
        }
        ConfigManager.save(this, buildCurrentConfig(enabled = false), syncTmpWithRoot = false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webMap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webMap.webChromeClient = WebChromeClient()
        webMap.webViewClient = WebViewClient()
        webMap.addJavascriptInterface(MapJsBridge(), "AndroidBridge")
        webMap.loadUrl("file:///android_asset/map.html")
    }

    private fun checkEnvironmentStatus() {
        refreshShizukuState()
        updateEnvironmentUI()
        RootUtils.runRootTask {
            val rootOk = RootUtils.isRootAvailable()
            if (rootOk) {
                RootUtils.grantMockAndLocationPermissions(packageName)
            } else if (ShizukuHelper.hasPermission()) {
                ShizukuHelper.grantMockAndLocationPermissions(this)
                shizukuAutoGrantTried = true
            }
            runOnUiThread {
                hasRoot = rootOk
                refreshShizukuState()
                rootCheckCompleted = true

                val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
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
        return try {
            val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            val hookedBoot = Settings.Global.getInt(
                contentResolver,
                ConfigManager.SETTINGS_GLOBAL_HOOKED_BOOT_KEY,
                -2
            )
            currentBoot > 0 && hookedBoot == currentBoot
        } catch (_: Throwable) {
            true
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
            tvRootStatus.text = "模式: 检测环境中..."
            tvRootStatus.setTextColor(Color.parseColor("#475569"))
            tvXposedStatus.text = "权限: 检测中..."
            tvXposedStatus.setTextColor(Color.parseColor("#475569"))
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
                tvRootStatus.text = if (hasRoot) "模式: Root完整版 ✓" else "模式: Root完整版(缺Root)"
                tvRootStatus.setTextColor(
                    Color.parseColor(if (hasRoot) "#059669" else "#DC2626")
                )

                if (hasRoot && hasSystemHookActive) {
                    tvSubtitleMode.text = "零三方应用注入架构 · 系统级防漂移"
                    tvXposedStatus.text = "LSPosed: 已激活 ✓"
                    tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                    cardDegradedBanner.visibility = View.GONE

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = true, label = "防基站/WiFi漂移")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                } else if (hasRoot && hasXposed && !hasSystemHookActive) {
                    tvSubtitleMode.text = "LSPosed 已激活 · 需重启手机加载系统 Hook"
                    tvXposedStatus.text = "LSPosed: 待重启 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#D97706"))

                    cardDegradedBanner.visibility = View.VISIBLE
                    tvDegradedTitle.text = "⚠️ LSPosed 模块已激活，重启手机后正式生效"
                    tvDegradedDesc.text =
                        "模块开关已打开，但系统框架 (android) 尚未加载 Hook。请重启一次手机，否则底层基站/Wi-Fi隔离与 Mock 标记抹除不会生效。"
                    btnDegradedAction.text = "立即重启 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(待重启)")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                } else if (hasRoot && !hasXposed) {
                    tvSubtitleMode.text = "Root 已就绪 · 等待 LSPosed 模块激活"
                    tvXposedStatus.text = "LSPosed: 未激活 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#D97706"))

                    cardDegradedBanner.visibility = View.VISIBLE
                    tvDegradedTitle.text = "已获取 Root，但 LSPosed 模块尚未激活"
                    tvDegradedDesc.text =
                        "当前仅可启用系统模拟定位与内核阻断，无法抹除 Mock 标记或底层隔离基站/Wi-Fi。点击查看激活步骤，或点「切换模式」。"
                    btnDegradedAction.text = "激活向导 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                } else {
                    tvSubtitleMode.text = "当前设备未获取 Root · 建议切换为 Shizuku 或免 Root 模式"
                    tvXposedStatus.text = "Root: 未获取 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))

                    cardDegradedBanner.visibility = View.VISIBLE
                    tvDegradedTitle.text = "⚠️ 未检测到 Root 权限"
                    tvDegradedDesc.text =
                        "当前设备无 Root，无法运行系统框架 Hook。建议点击右上角「切换模式」选择「Shizuku 增强模式」或「纯免 Root 模式」。"
                    btnDegradedAction.text = "切换模式 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                    setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
                }
            }

            WorkMode.SHIZUKU -> {
                val mockAllowed = RootUtils.isMockLocationAllowed(this)
                tvSubtitleMode.text = "免 Root · Shizuku 自动授权 + 防扫描 + RST-VPN"
                tvRootStatus.text = "模式: Shizuku增强 ›"
                tvRootStatus.setTextColor(Color.parseColor("#2563EB"))

                cardDegradedBanner.visibility = View.VISIBLE
                if (hasShizukuPermission) {
                    when {
                        mockAllowed -> {
                            tvXposedStatus.text = "Shizuku: 已就绪 ✓"
                            tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                            tvDegradedTitle.text = "Shizuku 免 Root 增强模式已就绪"
                            tvDegradedDesc.text =
                                "已自动放行模拟位置；漂移时自动临时关闭 Wi-Fi/蓝牙扫描，配合局部 RST-VPN 阻断腾讯/高德等网络定位接口（微信/高德均生效）。"
                            btnDegradedAction.text = "模式对比 ›"
                        }
                        !shizukuAutoGrantTried -> {
                            tvXposedStatus.text = "Shizuku: 自动放行中..."
                            tvXposedStatus.setTextColor(Color.parseColor("#D97706"))
                            tvDegradedTitle.text = "正在通过 Shizuku 自动放行模拟位置..."
                            tvDegradedDesc.text =
                                "正在调用系统 AppOps 接口配置模拟位置权限，请稍候。"
                            btnDegradedAction.text = "模式对比 ›"
                            grantPermissionsViaShizukuAsync()
                        }
                        else -> {
                            tvXposedStatus.text = "模拟位置: 需手动设 ›"
                            tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                            tvDegradedTitle.text = "⚠️ 需在开发者选项中手动勾选模拟位置应用"
                            tvDegradedDesc.text =
                                "Shizuku 已连接（自动关扫描正常），但当前手机系统 ROM 限制 ADB 权限自动修改模拟位置。请点右侧前往「开发者选项 → 选择模拟位置信息应用」选中本应用。"
                            btnDegradedAction.text = "去开发者选项 ›"
                        }
                    }
                } else if (hasShizukuBinder) {
                    tvXposedStatus.text = "Shizuku: 待授权 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#D97706"))
                    tvDegradedTitle.text = "检测到 Shizuku 服务运行中，请授予权限"
                    tvDegradedDesc.text =
                        "点击右侧「立即授权」放行 Shizuku 后，即可自动配置模拟定位并自动关闭系统 Wi-Fi/蓝牙扫描。"
                    btnDegradedAction.text = "立即授权 ›"
                } else {
                    val installed = ShizukuHelper.isShizukuInstalled(this)
                    tvXposedStatus.text = if (installed) "Shizuku: 未启动 ›" else "Shizuku: 未安装 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                    tvDegradedTitle.text = if (installed) {
                        "⚠️ Shizuku 服务尚未启动"
                    } else {
                        "⚠️ 未安装 Shizuku 应用"
                    }
                    tvDegradedDesc.text = if (installed) {
                        "请先打开 Shizuku 应用并通过「无线调试」启动服务，然后返回本页面；若暂不使用 Shizuku，可切换至「纯免 Root 模式」。"
                    } else {
                        "请先安装并启动 Shizuku（支持 Android 11+ 无线调试免电脑启动），或点右上角「切换模式」改用纯免 Root 模式。"
                    }
                    btnDegradedAction.text = if (installed) "打开Shizuku ›" else "获取Shizuku ›"
                }

                setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                setCheckboxState(
                    cbIptables,
                    enabled = true,
                    checked = shizukuAutoSuppressScan,
                    label = "自动关扫描(Shizuku)"
                )
                setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
            }

            WorkMode.ROOTLESS_BASIC -> {
                val mockAllowed = RootUtils.isMockLocationAllowed(this)
                tvSubtitleMode.text = "纯免 Root 基础模式 · 系统模拟定位 + RST-VPN"
                tvRootStatus.text = "模式: 纯免Root基础 ›"
                tvRootStatus.setTextColor(Color.parseColor("#D97706"))

                cardDegradedBanner.visibility = View.VISIBLE
                if (mockAllowed) {
                    tvXposedStatus.text = "模拟定位: 已授权 ✓"
                    tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                    tvDegradedTitle.text = "纯免 Root 基础模式已就绪"
                    tvDegradedDesc.text =
                        "已启用系统GPS + 局部RST-VPN（主动回发 TCP RST 阻断网络定位接口，支持微信与高德地图）。建议在系统位置服务中关闭 Wi-Fi/蓝牙扫描。"
                    btnDegradedAction.text = "防漂移指南 ›"
                } else {
                    tvXposedStatus.text = "模拟定位: 未设置 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                    tvDegradedTitle.text = "⚠️ 需先在开发者选项中授权模拟位置"
                    tvDegradedDesc.text =
                        "当前为纯免 Root 模式，请先前往系统「开发者选项 → 选择模拟位置信息应用」选中本应用；若已安装 Shizuku，可点「切换模式」自动授权。"
                    btnDegradedAction.text = "去授权设置 ›"
                }

                setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
            }
        }
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
                    toast("请在 Shizuku 应用的「已授权应用」中允许定位漂移助手")
                    ShizukuHelper.openShizukuApp(this)
                } else {
                    ShizukuHelper.requestPermission(REQ_SHIZUKU)
                }
            }
            else -> {
                val opened = ShizukuHelper.openShizukuApp(this)
                if (opened) {
                    toast("请在 Shizuku 中通过「无线调试」启动服务后返回")
                } else {
                    toast("正在打开 Shizuku 官方下载页")
                }
            }
        }
    }

    /**
     * 启动时及点击「切换模式」时弹出的三模式选择与差异对比界面
     */
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
            text = "请根据设备权限选择运行模式（支持随时在顶部「切换模式」更改）："
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
                setPadding(dp(12), dp(10), dp(12), dp(10))
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
                setPadding(dp(7), dp(2), dp(7), dp(2))
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
            "最强防漂移 · 零三方应用注入架构",
            "✓ system_server 合成虚拟 GPS + 10 颗北斗/GPS 工作卫星\n" +
                "✓ 底层抹除 isMock 标记，源头隔离周边 Wi-Fi 与蜂窝基站\n" +
                "✓ 内核 iptables REJECT 秒级拒止网络定位接口",
            "✗ 门槛：设备需已 Root 且在 LSPosed 中启用模块并重启"
        )
        cardRefs.add(cRoot)

        val cShizuku = buildModeCard(
            WorkMode.SHIZUKU,
            "2. 免 Root + Shizuku 增强模式",
            "免 Root 推荐 · 自动授权 + 自动防扫描 + RST-VPN",
            "✓ 无需 Root，通过 Shizuku 自动放行模拟位置权限\n" +
                "✓ 漂移时自动临时关闭系统 Wi-Fi/蓝牙扫描防回弹，停止自动还原\n" +
                "✓ 局部 RST-VPN 精准拒止网络定位接口，微信/高德均有效",
            "✗ 差别：受 SELinux 限制，无法抹除 isMock 标记或隐藏蜂窝基站"
        )
        cardRefs.add(cShizuku)

        val cBasic = buildModeCard(
            WorkMode.ROOTLESS_BASIC,
            "3. 纯免 Root 基础降级模式",
            "零外部依赖 · 无需 Root 也无需 Shizuku",
            "✓ 开箱即用，依赖系统公开模拟位置 API 与局部 RST-VPN\n" +
                "✓ 局部 VPN 仅拦截定位域名并放行聊天通道，微信/高德均有效",
            "✗ 差别：需手动去开发者选项授权并手动关扫描；无法隐藏 isMock/基站"
        )
        cardRefs.add(cBasic)

        val shizukuActionBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedCard("#EFF6FF", "#BFDBFE", 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
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
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
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
            text = "下次启动直接进入已选模式（仍可点顶部「切换模式」更改）"
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
                    createRoundedCard("#EFF6FF", "#2563EB", 11)
                } else {
                    createRoundedCard("#F8FAFC", "#E2E8F0", 11)
                }
                item.radio.text = if (isSelected) "◉" else "○"
                item.radio.setTextColor(
                    Color.parseColor(if (isSelected) "#2563EB" else "#94A3B8")
                )

                val (badgeText, badgeTextColor, badgeBg) = when (item.mode) {
                    WorkMode.ROOT_LSPOSED -> when {
                        !rootCheckCompleted -> Triple("检测中...", "#475569", "#E2E8F0")
                        hasRoot && hasSystemHookActive -> Triple("已就绪 ✓", "#059669", "#D1FAE5")
                        hasRoot && hasXposed -> Triple("需重启生效", "#D97706", "#FEF3C7")
                        hasRoot -> Triple("LSPosed未开", "#D97706", "#FEF3C7")
                        else -> Triple("无Root权限", "#DC2626", "#FEE2E2")
                    }
                    WorkMode.SHIZUKU -> when {
                        hasShizukuPermission && mockAllowed -> Triple("已就绪 ✓", "#059669", "#D1FAE5")
                        hasShizukuPermission -> Triple("已授权·待设模拟位置", "#D97706", "#FEF3C7")
                        hasShizukuBinder -> Triple("运行中·待授权", "#D97706", "#FEF3C7")
                        ShizukuHelper.isShizukuInstalled(this) -> Triple("未启动", "#DC2626", "#FEE2E2")
                        else -> Triple("未安装", "#64748B", "#E2E8F0")
                    }
                    WorkMode.ROOTLESS_BASIC -> when {
                        mockAllowed -> Triple("已就绪 ✓", "#059669", "#D1FAE5")
                        else -> Triple("可用(需设模拟位置)", "#D97706", "#FEF3C7")
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
                    tvShizukuHint.text = "Shizuku 服务已运行，点击右侧按钮一键授权："
                    btnShizukuQuick.text = "授权 Shizuku"
                } else if (ShizukuHelper.isShizukuInstalled(this)) {
                    tvShizukuHint.text = "已安装 Shizuku，需先打开它启动无线调试服务："
                    btnShizukuQuick.text = "打开 Shizuku"
                } else {
                    tvShizukuHint.text = "尚未安装 Shizuku（Android 11+ 支持免电脑无线调试）："
                    btnShizukuQuick.text = "下载 Shizuku"
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
            .setTitle(if (isAutoStartup) "选择运行模式" else "运行模式选择与能力对比")
            .setView(scroll)
            .setPositiveButton("确认使用该模式") { _, _ ->
                if (pendingMode == WorkMode.ROOT_LSPOSED && rootCheckCompleted && !hasRoot) {
                    toast("当前设备未检测到 Root，已自动为你切换为免 Root 模式")
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
            background = createRoundedCard("#FFFBEB", "#FDE68A", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val tvTitle = TextView(this).apply {
            text = "检测到 LSPosed 模块已首次启用"
            setTextColor(Color.parseColor("#92400E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvBody = TextView(this).apply {
            text = "由于本工具采用「零三方应用注入」架构，仅挂载在系统框架 (android) 与电话服务 (com.android.phone) 进程中，系统进程仅在开机启动时加载模块。\n\n请重启一次手机，重启后顶部状态将自动变为「LSPosed: 已激活 ✓」，底层防漂移与 Mock 标记抹除即可生效。"
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
            .setTitle("需要重启手机以生效")
            .setView(scroll)

        if (hasRoot) {
            builder.setPositiveButton("立即重启手机") { _, _ ->
                confirmAndRebootDevice()
            }
            builder.setNegativeButton("稍后手动重启", null)
        } else {
            builder.setPositiveButton("知道了，稍后重启", null)
        }
        builder.show()
    }

    private fun confirmAndRebootDevice() {
        AlertDialog.Builder(this)
            .setTitle("确认立即重启？")
            .setMessage("手机将立即重启，重启完成后打开「定位漂移助手」即可使用完整模式。")
            .setPositiveButton("确认重启") { _, _ ->
                toast("正在重启设备...")
                RootUtils.runRootTask {
                    val ok = RootUtils.rebootDevice()
                    if (!ok) {
                        runOnUiThread {
                            toast("自动重启失败，请长按电源键手动重启手机")
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
        box.alpha = if (enabled) 1.0f else 0.52f
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

    private fun createRoundedCard(bgColor: String, strokeColor: String, radiusDp: Int = 10): GradientDrawable {
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
                createRoundedCard("#FFFBEB", "#FDE68A", 10)
            } else {
                createRoundedCard("#F8FAFC", "#E2E8F0", 10)
            }
            setPadding(dp(10), dp(9), dp(10), dp(9))
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

    /**
     * 统一结构化环境自检与使用指引弹窗（自动适配当前所选模式）
     */
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
            background = createRoundedCard("#EFF6FF", "#BFDBFE", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val statusHeader = TextView(this).apply {
            text = "当前运行环境实时自检"
            setTextColor(Color.parseColor("#1E3A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        statusCard.addView(statusHeader)

        when (selectedMode) {
            WorkMode.ROOT_LSPOSED -> {
                if (hasRoot && hasSystemHookActive) {
                    addStatusCheckRow(statusCard, "当前模式", "Root + LSPosed 完整模式", "#059669")
                    addStatusCheckRow(statusCard, "系统GPS & 模拟标记抹除", "已就绪 (零三方应用注入)", "#059669")
                    addStatusCheckRow(statusCard, "底层基站/Wi-Fi源头隔离", "已就绪 (Binder IPC 拦截)", "#059669")
                    addStatusCheckRow(statusCard, "内核 iptables 阻断", "已就绪", "#059669")
                } else if (hasRoot && hasXposed) {
                    addStatusCheckRow(statusCard, "Root 权限", "已授权 ✓", "#059669")
                    addStatusCheckRow(statusCard, "LSPosed 模块开关", "已开启，等待重启生效", "#D97706")
                    addStatusCheckRow(statusCard, "系统框架 (android) Hook", "尚未加载 (需重启设备)", "#DC2626")
                } else {
                    addStatusCheckRow(statusCard, "Root 权限", if (hasRoot) "已授权 ✓" else "未获取 ✗", if (hasRoot) "#059669" else "#DC2626")
                    addStatusCheckRow(statusCard, "LSPosed 模块状态", if (hasXposed) "已开启" else "未激活", "#D97706")
                    addStatusCheckRow(statusCard, "Mock标记抹除 / 基站隔离", "暂不可用", "#DC2626")
                }
            }
            WorkMode.SHIZUKU -> {
                addStatusCheckRow(statusCard, "当前模式", "免 Root + Shizuku 增强模式", "#2563EB")
                addStatusCheckRow(
                    statusCard,
                    "1. Shizuku 服务与授权",
                    when {
                        hasShizukuPermission -> "已授权 ✓"
                        hasShizukuBinder -> "运行中·待授权"
                        else -> "未启动/未安装 ✗"
                    },
                    if (hasShizukuPermission) "#059669" else "#DC2626"
                )
                addStatusCheckRow(
                    statusCard,
                    "2. 模拟位置权限",
                    when {
                        mockAllowed -> "已自动放行 ✓"
                        hasShizukuPermission && shizukuAutoGrantTried -> "需在开发者选项手动设置 ✗"
                        else -> "授权 Shizuku 后自动放行"
                    },
                    if (mockAllowed) "#059669" else "#DC2626"
                )
                addStatusCheckRow(statusCard, "3. 自动关Wi-Fi/蓝牙扫描", "支持 (停止漂移自动恢复)", "#059669")
                addStatusCheckRow(statusCard, "4. 局部 RST-VPN 阻断", "已适配微信/高德双栈定位接口", "#059669")
                addStatusCheckRow(statusCard, "5. 抹除isMock / 基站隔离", "不支持 (受SELinux隔离限制)", "#64748B")
            }
            WorkMode.ROOTLESS_BASIC -> {
                addStatusCheckRow(statusCard, "当前模式", "纯免 Root 基础降级模式", "#D97706")
                addStatusCheckRow(
                    statusCard,
                    "1. 模拟位置应用授权",
                    if (mockAllowed) "已授权 ✓" else "未设置 (必须配置) ✗",
                    if (mockAllowed) "#059669" else "#DC2626"
                )
                addStatusCheckRow(statusCard, "2. 局部 RST-VPN 通道", "已适配微信/高德双栈定位接口", "#059669")
                addStatusCheckRow(statusCard, "3. 抹除isMock / 基站隔离", "不支持 (需Root+LSPosed)", "#64748B")
            }
        }
        root.addView(statusCard)

        when (selectedMode) {
            WorkMode.SHIZUKU -> {
                addStepCard(
                    root,
                    "1",
                    if (hasShizukuPermission) "Shizuku 授权（已完成）" else "启动并授权 Shizuku（免 Root 推荐）",
                    "Android 11 及以上设备可直接在手机上打开 Shizuku，通过系统「无线调试」一键启动服务（无需连接电脑），返回本应用后点击授权即可。",
                    highlight = !hasShizukuPermission
                )
                addStepCard(
                    root,
                    "2",
                    if (mockAllowed) "模拟位置权限（已放行）" else "确认模拟位置权限",
                    if (mockAllowed) {
                        "已成功放行模拟位置权限，开启漂移时还会自动临时关闭系统「Wi-Fi 扫描」与「蓝牙扫描」以防室内回弹。"
                    } else {
                        "若当前手机系统 ROM 限制 Shizuku 自动修改模拟位置权限，请点击下方「打开开发者选项」，在「选择模拟位置信息应用」中手动选中「定位漂移助手」。"
                    },
                    highlight = hasShizukuPermission && !mockAllowed
                )
                addStepCard(
                    root,
                    "3",
                    "保持开启「VPN阻断(推荐)」以支持微信定位",
                    "局部 RST-VPN 仅对腾讯/高德/百度网络定位域名建立双栈主机路由并立即回发 TCP RST，同时放行微信消息通道，迫使微信内置定位 SDK 回落至虚拟 GPS。"
                )
            }
            WorkMode.ROOTLESS_BASIC -> {
                addStepCard(
                    root,
                    "1",
                    if (mockAllowed) "模拟位置权限（已完成）" else "开启开发者选项并授权模拟位置（必做）",
                    "打开系统「设置 → 关于手机」，连续点击「版本号」7 次开启开发者选项；随后进入「开发者选项 → 选择模拟位置信息应用」，选中「定位漂移助手」。",
                    highlight = !mockAllowed
                )
                addStepCard(
                    root,
                    "2",
                    "保持开启「系统GPS注入 + VPN阻断(推荐)」",
                    "局部 VPN 仅对腾讯/高德/百度网络定位域名建立精准双栈路由并主动回发 TCP RST，不会阻断微信聊天与正常上网，可迫使微信等应用快速回落至虚拟 GPS。"
                )
                addStepCard(
                    root,
                    "3",
                    "手动关闭系统 Wi-Fi / 蓝牙扫描减少回弹",
                    "前往系统「设置 → 位置信息 → 位置服务/定位精度」，关闭「Wi-Fi 扫描」与「蓝牙扫描」（或切换为 Shizuku 模式自动接管）。"
                )
            }
            WorkMode.ROOT_LSPOSED -> {
                if (hasXposed && !hasSystemHookActive) {
                    addStepCard(
                        root,
                        "1",
                        "重启一次手机以加载系统级 Hook（必做）",
                        "LSPosed 模块开关已打开，但 system_server 与电话服务进程仅在开机时注入模块。点击下方「立即重启手机」或手动重启后，完整功能即刻解锁。",
                        highlight = true
                    )
                } else if (!hasXposed) {
                    addStepCard(
                        root,
                        "1",
                        "在 LSPosed 中启用本模块并勾选系统作用域",
                        "打开 LSPosed 管理器 →「模块」→ 启用「定位漂移助手」，确保仅勾选「系统框架 (android)」与「电话服务 (com.android.phone)」，随后重启一次手机。",
                        highlight = true
                    )
                } else {
                    addStepCard(
                        root,
                        "1",
                        "推荐策略组合",
                        "建议同时保持勾选「系统GPS注入」、「防基站/WiFi漂移」与「内核阻断」（无需再开启 VPN 阻断，避免重复拦截）。"
                    )
                    addStepCard(
                        root,
                        "2",
                        "实时改点无需重启目标应用",
                        "漂移运行期间，直接在地图重新选点或从收藏夹切换地点后点击「同步新选点」，system_server 会在 600ms 内同步下发新坐标与 10 颗虚拟卫星。"
                    )
                }
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("环境自检与配置向导")
            .setView(scroll)
            .setNegativeButton("知道了", null)
            .setNeutralButton("切换模式") { _, _ ->
                showModeSelectionDialog(isAutoStartup = false)
            }

        when {
            selectedMode == WorkMode.SHIZUKU && !hasShizukuPermission -> {
                builder.setPositiveButton(if (hasShizukuBinder) "立即授权 Shizuku" else "打开 Shizuku") { _, _ ->
                    handleShizukuConnectAction()
                }
            }
            selectedMode == WorkMode.SHIZUKU && !mockAllowed -> {
                builder.setPositiveButton("打开开发者选项") { _, _ ->
                    openDeveloperOptions()
                }
            }
            selectedMode == WorkMode.ROOTLESS_BASIC -> {
                builder.setPositiveButton("打开开发者选项") { _, _ ->
                    openDeveloperOptions()
                }
            }
            selectedMode == WorkMode.ROOT_LSPOSED && hasXposed && !hasSystemHookActive && hasRoot -> {
                builder.setPositiveButton("立即重启手机") { _, _ ->
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
                toast("请进入「关于手机」连续点击版本号 7 次开启开发者选项")
            } catch (_: Throwable) {
                toast("无法自动跳转设置页，请手动打开系统设置")
            }
        }
    }

    private fun refreshFavoritesCount() {
        val count = ConfigManager.getFavorites(this).size
        btnFavorites.text = if (count > 0) "收藏夹 ($count)" else "收藏夹"
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

    private fun startDrifting() {
        if (!cbSystemMock.isChecked && !cbVpn.isChecked && !cbIptables.isChecked && !cbAntiDrift.isChecked) {
            toast("请至少启用一项定位或防漂移策略")
            return
        }

        when (selectedMode) {
            WorkMode.SHIZUKU -> {
                if (!hasShizukuPermission) {
                    toast("请先完成 Shizuku 授权，或点击顶部「切换模式」")
                    handleShizukuConnectAction()
                    return
                }
                val cfg = buildCurrentConfig(enabled = true)
                val suppressScan = shizukuAutoSuppressScan

                ShizukuHelper.runShizukuTask {
                    val mockOk = ShizukuHelper.grantMockAndLocationPermissions(this)
                    shizukuAutoGrantTried = true
                    if (cfg.enableSystemMock && !mockOk) {
                        runOnUiThread {
                            updateEnvironmentUI()
                            toast("当前系统限制 Shizuku 自动放行模拟位置，请在开发者选项中手动选中本应用")
                            openDeveloperOptions()
                        }
                        return@runShizukuTask
                    }
                    if (suppressScan) {
                        ShizukuHelper.applyAntiDriftScanSuppression(this)
                    }
                    runOnUiThread {
                        isEnabled = true
                        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                        updateEnvironmentUI()
                        launchForegroundServices(cfg)
                        refreshRunStatusUI()
                        toast(
                            if (suppressScan) {
                                "已开启 Shizuku 增强漂移（已自动关闭后台扫描）"
                            } else {
                                "已开启 Shizuku 增强漂移"
                            }
                        )
                    }
                }
            }

            WorkMode.ROOTLESS_BASIC -> {
                if (cbSystemMock.isChecked && !RootUtils.isMockLocationAllowed(this)) {
                    toast("请先在开发者选项中授权模拟位置应用")
                    showEnvironmentGuideDialog()
                    return
                }
                isEnabled = true
                val cfg = buildCurrentConfig(enabled = true)
                ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                launchForegroundServices(cfg)
                refreshRunStatusUI()
                toast("已开启纯免 Root 基础漂移")
            }

            WorkMode.ROOT_LSPOSED -> {
                if (!hasRoot) {
                    if (cbSystemMock.isChecked && !RootUtils.isMockLocationAllowed(this)) {
                        toast("当前无 Root 权限，请先切换为 Shizuku 或免 Root 模式")
                        showModeSelectionDialog(isAutoStartup = false)
                        return
                    }
                }
                isEnabled = true
                val cfg = buildCurrentConfig(enabled = true)
                ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                val json = ConfigManager.toJson(cfg).toString()

                if (hasRoot) {
                    RootUtils.runRootTask {
                        if (RootUtils.isRootAvailable()) {
                            RootUtils.grantMockAndLocationPermissions(packageName)
                            RootUtils.writeTmpConfigJson(json)
                            if (cfg.enableIptables) {
                                RootUtils.enableIptablesBlock()
                            } else {
                                RootUtils.disableIptablesBlock()
                            }
                        }
                        runOnUiThread {
                            launchForegroundServices(cfg)
                            refreshRunStatusUI()
                            toast("已开启定位漂移")
                        }
                    }
                } else {
                    launchForegroundServices(cfg)
                    refreshRunStatusUI()
                    toast("已开启定位漂移")
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
            toast("请先在开发者选项中授权模拟位置应用")
            showEnvironmentGuideDialog()
            return
        }

        val cfg = buildCurrentConfig(enabled = true)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

        if (selectedMode == WorkMode.ROOT_LSPOSED && hasRoot) {
            RootUtils.runRootTask {
                if (RootUtils.isRootAvailable()) {
                    RootUtils.writeTmpConfigJson(json)
                    if (cfg.enableIptables) {
                        RootUtils.enableIptablesBlock()
                    } else {
                        RootUtils.disableIptablesBlock()
                    }
                }
                runOnUiThread {
                    syncActiveServices(cfg, showToast)
                }
            }
        } else {
            syncActiveServices(cfg, showToast)
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
            toast("已实时更新漂移坐标")
        }
    }

    private fun stopDrifting() {
        isEnabled = false
        val cfg = buildCurrentConfig(enabled = false)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

        stopService(Intent(this, MockLocationService::class.java))
        stopVpnService()

        if (hasRoot) {
            RootUtils.runRootTask {
                if (RootUtils.isRootAvailable()) {
                    RootUtils.writeTmpConfigJson(json)
                    RootUtils.disableIptablesBlock()
                }
            }
        }
        ShizukuHelper.runShizukuTask {
            ShizukuHelper.restoreAntiDriftScanSuppression(this)
        }

        refreshRunStatusUI()
        toast("已停止定位漂移，恢复真实位置")
    }

    private fun startMockLocationService() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBasicPermissionsIfNeeded()
            toast("请授予位置权限以启动系统模拟定位服务")
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
                if (isEnabled) {
                    ConfigManager.save(this, buildCurrentConfig(enabled = true), syncTmpWithRoot = false)
                }
                toast("未授予 VPN 权限，已跳过 VPN 防漂移通道")
            }
        }
    }

    private fun buildCurrentConfig(enabled: Boolean): ConfigManager.DriftConfig {
        val allowIptables = selectedMode == WorkMode.ROOT_LSPOSED && cbIptables.isChecked && hasRoot
        val allowAntiDrift = selectedMode == WorkMode.ROOT_LSPOSED && cbAntiDrift.isChecked && hasSystemHookActive
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
            updatedAt = System.currentTimeMillis()
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
            toast("已复制三系坐标到剪切板")
        } catch (_: Throwable) {
            toast("复制失败")
        }
    }

    private fun refreshRunStatusUI() {
        if (isEnabled) {
            tvRunStatus.text = when (selectedMode) {
                WorkMode.ROOT_LSPOSED -> "● 漂移生效中"
                WorkMode.SHIZUKU -> "● Shizuku运行中"
                WorkMode.ROOTLESS_BASIC -> "● 基础运行中"
            }
            tvRunStatus.setTextColor(Color.parseColor("#059669"))
            btnToggleDrift.text = "一键停止漂移"
            btnToggleDrift.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            btnUpdatePoint.visibility = View.VISIBLE
        } else {
            tvRunStatus.text = "● 未开启"
            tvRunStatus.setTextColor(Color.parseColor("#64748B"))
            btnToggleDrift.text = "一键开启定位漂移"
            btnToggleDrift.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
            btnUpdatePoint.visibility = View.GONE
        }
    }

    private fun showManualCoordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        val etInput = EditText(this).apply {
            hint = "格式: 纬度,经度 或 经度,纬度 (如 39.9088,116.3974)"
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
            text = "GCJ-02(火星)"
            isChecked = true
        }
        val rbWgs = RadioButton(this).apply {
            id = View.generateViewId()
            text = "WGS-84(GPS)"
        }
        val rbBd = RadioButton(this).apply {
            id = View.generateViewId()
            text = "BD-09(百度)"
        }
        rgType.addView(rbGcj)
        rgType.addView(rbWgs)
        rgType.addView(rbBd)
        layout.addView(rgType)

        AlertDialog.Builder(this)
            .setTitle("输入或从剪切板读取坐标")
            .setView(layout)
            .setPositiveButton("确定落点") { _, _ ->
                val raw = etInput.text.toString().trim()
                val parts = raw.split(Regex("[,，\\s]+")).mapNotNull { it.toDoubleOrNull() }
                if (parts.size < 2) {
                    toast("坐标格式无效，请输入类似 39.9088,116.3974")
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
            hint = "请输入位置备注名"
        }
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("收藏当前选点")
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
            toast("收藏夹为空，请先在地图选点后点击「收藏当前点」")
            return
        }
        val items = list.map {
            String.format(Locale.US, "%s  (%.4f, %.4f)", it.name, it.gcjLat, it.gcjLng)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("收藏夹（点击跳转）")
            .setItems(items) { _, which ->
                val spot = list[which]
                applyPickedLocation(spot.gcjLat, spot.gcjLng, spot.wgsLat, spot.wgsLng, spot.name)
                syncMarkerToWebMap(spot.gcjLat, spot.gcjLng, spot.name)
                if (isEnabled) {
                    applyUpdatedCoordinates()
                }
            }
            .setNeutralButton("删除/管理") { _, _ ->
                showDeleteFavoriteDialog(list)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDeleteFavoriteDialog(list: List<ConfigManager.FavoriteSpot>) {
        val items = list.map { "删除：${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("点击要删除的收藏项")
            .setItems(items) { _, which ->
                val removedName = list[which].name
                ConfigManager.removeFavorite(this, which)
                refreshFavoritesCount()
                toast("已删除：$removedName")
            }
            .setNeutralButton("清空全部") { _, _ ->
                ConfigManager.clearFavorites(this)
                refreshFavoritesCount()
                toast("已清空全部收藏")
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

    private fun httpGetText(urlStr: String, timeoutMs: Int = 5500): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LocationDrifter/1.1 (Android)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
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

    private fun searchViaArcGis(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
        val url = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates" +
            "?f=json&singleLine=$encodedQuery&maxLocations=5&outFields=PlaceName,Match_addr"
        val body = httpGetText(url) ?: return emptyList()
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
        val body = httpGetText(url) ?: return emptyList()
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

    private fun searchViaNominatim(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&q=$encodedQuery"
        val body = httpGetText(url) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val list = ArrayList<PlaceSearchResult>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val wgsLat = item.optString("lat").toDoubleOrNull() ?: continue
                val wgsLng = item.optString("lon").toDoubleOrNull() ?: continue
                val gcj = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
                val display = item.optString("display_name", fallbackName)
                val name = display.substringBefore(",").ifBlank { fallbackName }
                list.add(PlaceSearchResult(gcj.lat, gcj.lng, name, display))
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
            PlaceSearchResult(it.second.first, it.second.second, it.first, "内置离线城市坐标")
        }
    }

    private fun performPlaceSearch(query: String) {
        searchExecutor.execute {
            val encoded = try {
                URLEncoder.encode(query, "UTF-8")
            } catch (_: Throwable) {
                query
            }
            val candidates = searchViaArcGis(encoded, query).ifEmpty {
                searchViaPhoton(encoded, query).ifEmpty {
                    searchViaNominatim(encoded, query).ifEmpty {
                        searchViaSystemGeocoder(query).ifEmpty {
                            searchViaBuiltinCities(query)
                        }
                    }
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (candidates.isNotEmpty()) {
                    val arr = JSONArray()
                    candidates.take(5).forEach { c ->
                        arr.put(JSONObject().apply {
                            put("gcjLat", c.gcjLat)
                            put("gcjLng", c.gcjLng)
                            put("label", c.label)
                            put("detail", c.detail)
                        })
                    }
                    val quoted = JSONObject.quote(arr.toString())
                    webMap.evaluateJavascript("window.onSearchCandidates($quoted)", null)
                } else {
                    val err = escapeJsString("未找到「$query」，可尝试更详细地名或直接输入经纬度")
                    webMap.evaluateJavascript("window.onSearchResult(false, 0, 0, '', '$err')", null)
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
                applyPickedLocation(gcjLat, gcjLng, wgsLat, wgsLng, address)
            }
        }

        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                syncMarkerToWebMap(currentGcjLat, currentGcjLng, currentAddress)
            }
        }

        @JavascriptInterface
        fun searchPlace(query: String) {
            val q = query.trim()
            if (q.isNotEmpty()) {
                performPlaceSearch(q)
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
