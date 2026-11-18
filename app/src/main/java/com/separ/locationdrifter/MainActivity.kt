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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.LocationManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        private const val KEY_SHIZUKU_ISOLATE_TARGET_APPS = "shizuku_isolate_target_apps"
        private const val KEY_VPN_DEFAULT_MIGRATED_V2 = "vpn_default_migrated_v2"
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
    private var shizukuIsolateTargetApps = true
    private var isEnabled = false
    private var suppressCheckboxCallback = false
    private var isImeVisible = false

    private var modeDialog: AlertDialog? = null
    private var modeDialogRefreshCallback: (() -> Unit)? = null

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

    override fun onDestroy() {
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
        refreshRunStatusUI()
        refreshFavoritesCount()
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

        webMap = findViewById(R.id.webMap)
        cbSystemMock = findViewById(R.id.cbSystemMock)
        cbAntiDrift = findViewById(R.id.cbAntiDrift)
        cbIptables = findViewById(R.id.cbIptables)
        cbVpn = findViewById(R.id.cbVpn)

        btnFavorites = findViewById(R.id.btnFavorites)
        btnToggleDrift = findViewById(R.id.btnToggleDrift)
        btnUpdatePoint = findViewById(R.id.btnUpdatePoint)

        setupNativeSearchBar()

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
        candidatesScroll.visibility = View.VISIBLE
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
                                toast("已隔离 $count 个目标应用扫描并重启生效")
                            }
                        } else {
                            ShizukuHelper.restoreTargetAppLocationIsolation(this)
                            runOnUiThread {
                                toast("已恢复目标应用原始权限")
                            }
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
        shizukuIsolateTargetApps = uiPrefs.getBoolean(KEY_SHIZUKU_ISOLATE_TARGET_APPS, true)

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

        val migratedV2 = uiPrefs.getBoolean(KEY_VPN_DEFAULT_MIGRATED_V2, false)
        if (!migratedV2 && (selectedMode == WorkMode.SHIZUKU || selectedMode == WorkMode.ROOTLESS_BASIC)) {
            cbVpn.isChecked = true
            uiPrefs.edit().putBoolean(KEY_VPN_DEFAULT_MIGRATED_V2, true).apply()
        } else {
            cbVpn.isChecked = cfg.enableVpn
        }
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
                    label = if (hasRoot && hasSystemHookActive) "防基站/WiFi漂移" else "防基站(待重启)"
                )
                setCheckboxState(cbIptables, enabled = hasRoot, checked = hasRoot, label = "内核阻断")
                setCheckboxState(cbVpn, enabled = true, checked = false, label = "VPN阻断")
            }
            WorkMode.SHIZUKU -> {
                setCheckboxState(cbSystemMock, enabled = true, checked = true, label = "系统GPS注入")
                setCheckboxState(
                    cbAntiDrift,
                    enabled = true,
                    checked = shizukuIsolateTargetApps,
                    label = "隔离微信WiFi/基站"
                )
                setCheckboxState(
                    cbIptables,
                    enabled = true,
                    checked = shizukuAutoSuppressScan,
                    label = "自动关扫描"
                )
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
                if (!isEnabled) {
                    RootUtils.disableIptablesBlock()
                }
            } else if (ShizukuHelper.hasPermission()) {
                ShizukuHelper.grantMockAndLocationPermissions(this)
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
                    tvSubtitleMode.text = "系统级防漂移 · 零三方注入"
                    tvXposedStatus.text = "LSPosed: 已激活 ✓"
                    tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                    cardDegradedBanner.visibility = View.GONE

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = true, label = "防基站/WiFi漂移")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                } else if (hasRoot && hasXposed && !hasSystemHookActive) {
                    tvSubtitleMode.text = "LSPosed 已开启 · 重启后生效"
                    tvXposedStatus.text = "LSPosed: 待重启 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#D97706"))

                    cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                    tvDegradedTitle.text = "LSPosed 已开启，重启手机后正式生效"
                    tvDegradedDesc.text = "请确认 LSPosed 已勾选「系统框架」与「电话服务」，重启后解锁。"
                    btnDegradedAction.text = "立即重启 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(待重启)")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                } else if (hasRoot && !hasXposed) {
                    tvSubtitleMode.text = "Root 已就绪 · 待激活 LSPosed"
                    tvXposedStatus.text = "LSPosed: 未激活 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#D97706"))

                    cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                    tvDegradedTitle.text = "LSPosed 模块未激活"
                    tvDegradedDesc.text = "请在 LSPosed 启用本模块（勾选系统框架与电话服务）并重启。"
                    btnDegradedAction.text = "激活向导 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                    setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
                } else {
                    tvSubtitleMode.text = "未获取 Root · 可切换免 Root 模式"
                    tvXposedStatus.text = "Root: 未获取 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))

                    cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                    tvDegradedTitle.text = "未检测到 Root 权限"
                    tvDegradedDesc.text = "请点击右上角「切换模式」选择 Shizuku 或纯免 Root 模式。"
                    btnDegradedAction.text = "切换模式 ›"

                    setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                    setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
                    setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
                    setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
                }
            }

            WorkMode.SHIZUKU -> {
                val mockAllowed = RootUtils.isMockLocationAllowed(this)
                tvSubtitleMode.text = "免 Root · Shizuku 增强模式"
                tvRootStatus.text = "模式: Shizuku增强 ›"
                tvRootStatus.setTextColor(Color.parseColor("#2563EB"))

                cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                if (hasShizukuPermission) {
                    when {
                        mockAllowed -> {
                            tvXposedStatus.text = "Shizuku: 已就绪 ✓"
                            tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                            tvDegradedTitle.text = "Shizuku 增强模式已就绪"
                            tvDegradedDesc.text =
                                "自动放行模拟位置、关后台扫描，开启漂移时自动清缓存并隔离目标应用（${ShizukuHelper.getTargetAppsSummary(this)}）硬件扫描。"
                            btnDegradedAction.text = "配置隔离 ›"
                        }
                        !shizukuAutoGrantTried -> {
                            tvXposedStatus.text = "Shizuku: 配置中..."
                            tvXposedStatus.setTextColor(Color.parseColor("#D97706"))
                            tvDegradedTitle.text = "正在自动放行模拟位置..."
                            tvDegradedDesc.text = "正在通过 Shizuku 配置模拟位置权限，请稍候。"
                            btnDegradedAction.text = "模式对比 ›"
                            grantPermissionsViaShizukuAsync()
                        }
                        else -> {
                            tvXposedStatus.text = "模拟位置: 需手动设 ›"
                            tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                            tvDegradedTitle.text = "需手动设置模拟位置应用"
                            tvDegradedDesc.text = "当前系统限制自动授权，请在「开发者选项」中手动选择本应用。"
                            btnDegradedAction.text = "去开发者选项 ›"
                        }
                    }
                } else if (hasShizukuBinder) {
                    tvXposedStatus.text = "Shizuku: 待授权 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#D97706"))
                    tvDegradedTitle.text = "Shizuku 运行中，请授予权限"
                    tvDegradedDesc.text = "点击右侧「立即授权」即可自动放行模拟位置与目标应用硬件隔离。"
                    btnDegradedAction.text = "立即授权 ›"
                } else {
                    val installed = ShizukuHelper.isShizukuInstalled(this)
                    tvXposedStatus.text = if (installed) "Shizuku: 未启动 ›" else "Shizuku: 未安装 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                    tvDegradedTitle.text = if (installed) "Shizuku 服务未启动" else "未安装 Shizuku"
                    tvDegradedDesc.text = if (installed) {
                        "请打开 Shizuku 通过无线调试启动服务后返回。"
                    } else {
                        "安装并启动 Shizuku 后可自动授权，或切换为纯免 Root 模式。"
                    }
                    btnDegradedAction.text = if (installed) "打开Shizuku ›" else "获取Shizuku ›"
                }

                setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
                setCheckboxState(
                    cbAntiDrift,
                    enabled = true,
                    checked = shizukuIsolateTargetApps,
                    label = "隔离微信WiFi/基站"
                )
                setCheckboxState(
                    cbIptables,
                    enabled = true,
                    checked = shizukuAutoSuppressScan,
                    label = "自动关扫描"
                )
                setCheckboxState(cbVpn, enabled = true, label = "VPN阻断(推荐)")
            }

            WorkMode.ROOTLESS_BASIC -> {
                val mockAllowed = RootUtils.isMockLocationAllowed(this)
                tvSubtitleMode.text = "纯免 Root · 基础模式"
                tvRootStatus.text = "模式: 纯免Root基础 ›"
                tvRootStatus.setTextColor(Color.parseColor("#D97706"))

                cardDegradedBanner.visibility = if (isImeVisible) View.GONE else View.VISIBLE
                if (mockAllowed) {
                    tvXposedStatus.text = "模拟定位: 已授权 ✓"
                    tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                    tvDegradedTitle.text = "纯免 Root 模式已就绪"
                    tvDegradedDesc.text = "建议：1.系统位置设置关闭 Wi-Fi/蓝牙扫描；2.彻底划掉目标应用后再开启漂移。"
                    btnDegradedAction.text = "防漂移指南 ›"
                } else {
                    tvXposedStatus.text = "模拟定位: 未设置 ›"
                    tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                    tvDegradedTitle.text = "需先在开发者选项授权模拟位置"
                    tvDegradedDesc.text = "请在「开发者选项 → 选择模拟位置信息应用」中选中本应用。"
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
        val currentList = ShizukuHelper.getTargetApps(this).toMutableList()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        scroll.addView(root)

        val tvHint = TextView(this).apply {
            text = "开启漂移时通过 Shizuku 临时限制所选应用的 Wi-Fi/蓝牙扫描权限、清空定位 SDK 缓存并重载进程（保留 GPS 权限，不影响聊天），停止漂移自动恢复："
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvHint)

        val presetCheckboxes = ArrayList<Pair<String, CheckBox>>()
        for (preset in ShizukuHelper.PRESET_TARGET_APPS) {
            val cb = CheckBox(this).apply {
                text = "${preset.displayName} (${preset.packageName})"
                isChecked = preset.packageName in currentList
                setTextColor(Color.parseColor("#0F172A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
            }
            root.addView(cb)
            presetCheckboxes.add(preset.packageName to cb)
        }

        val customPackages = currentList.filter { pkg ->
            ShizukuHelper.PRESET_TARGET_APPS.none { it.packageName == pkg }
        }

        val tvCustomLabel = TextView(this).apply {
            text = "附加自定义应用包名（多个可用逗号分隔）："
            setTextColor(Color.parseColor("#334155"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(tvCustomLabel)

        val etCustom = EditText(this).apply {
            hint = "如 com.autonavi.minimap"
            setText(customPackages.joinToString(", "))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(etCustom)

        AlertDialog.Builder(this)
            .setTitle("配置硬件定位感知隔离应用")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val result = LinkedHashSet<String>()
                for ((pkg, box) in presetCheckboxes) {
                    if (box.isChecked) result.add(pkg)
                }
                etCustom.text.toString()
                    .split(Regex("[,，\\s]+"))
                    .map { it.trim() }
                    .filter { it.contains(".") }
                    .forEach { result.add(it) }

                val finalList = result.toList()
                ShizukuHelper.setTargetApps(this, finalList)
                updateEnvironmentUI()

                if (isEnabled && selectedMode == WorkMode.SHIZUKU && shizukuIsolateTargetApps) {
                    ShizukuHelper.runShizukuTask {
                        ShizukuHelper.restoreTargetAppLocationIsolation(this)
                        val count = ShizukuHelper.applyTargetAppLocationIsolation(this, finalList)
                        runOnUiThread {
                            toast("已更新并隔离 $count 个目标应用")
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
            "最强防漂移 · 零三方应用注入",
            "✓ 系统层合成虚拟 GPS 与 10 颗工作卫星\n" +
                "✓ 抹除 isMock 标记，隔离周边 Wi-Fi 与基站\n" +
                "✓ 内核 iptables 拒止网络定位接口",
            "✗ 需已 Root 并在 LSPosed 启用模块后重启"
        )
        cardRefs.add(cRoot)

        val cShizuku = buildModeCard(
            WorkMode.SHIZUKU,
            "2. 免 Root + Shizuku 增强模式",
            "免 Root 推荐 · 自动授权 + 清理缓存 + 隔离扫描",
            "✓ 无需 Root，Shizuku 自动放行模拟位置\n" +
                "✓ 自动限制微信等应用的 Wi-Fi/蓝牙扫描，清定位缓存并重载进程\n" +
                "✓ 自动关闭后台扫描 + 局部 DNS 分流阻断网络定位",
            "✗ 无法抹除系统底层 isMock 标记（强风控应用可能拒收）"
        )
        cardRefs.add(cShizuku)

        val cBasic = buildModeCard(
            WorkMode.ROOTLESS_BASIC,
            "3. 纯免 Root 基础模式",
            "零外部依赖 · 开箱即用",
            "✓ 无需 Root 与 Shizuku，系统模拟定位 + 局部 VPN\n" +
                "✓ 仅拦截网络定位域名，正常聊天与上网畅通",
            "✗ 需手动授权模拟位置、关闭后台扫描并重启目标应用"
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
            background = createRoundedCard("#FFFBEB", "#FDE68A", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
                    "3. 目标应用硬件隔离",
                    ShizukuHelper.getTargetAppsSummary(this),
                    "#059669"
                )
                addStatusCheckRow(statusCard, "4. 自动关扫描 & 清缓存", "支持 (停止自动恢复)", "#059669")
                addStatusCheckRow(statusCard, "5. 抹除底层 isMock", "不支持 (需Root+LSPosed)", "#64748B")
            }
            WorkMode.ROOTLESS_BASIC -> {
                addStatusCheckRow(statusCard, "当前模式", "纯免 Root 基础版", "#D97706")
                addStatusCheckRow(
                    statusCard,
                    "1. 模拟位置权限",
                    if (mockAllowed) "已授权 ✓" else "未设置 ✗",
                    if (mockAllowed) "#059669" else "#DC2626"
                )
                addStatusCheckRow(statusCard, "2. 局部 DNS 阻断", "已就绪 (不拦截正常聊天)", "#059669")
                addStatusCheckRow(statusCard, "3. 抹除isMock / 基站", "不支持 (需Root+LSPosed)", "#64748B")
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
                    "目标应用扫描隔离 & 自动清缓存重载",
                    "开启漂移时自动限制目标应用（默认微信）的 Wi-Fi/蓝牙扫描，清空腾讯定位缓存并重启目标应用以加载虚拟坐标，停止漂移自动恢复。"
                )
                addStepCard(
                    root,
                    "!",
                    "关于微信强风控校验说明",
                    "免 Root 下系统仍会给模拟坐标打上 isMock 标记；若微信在当前系统版本严格拒收带 isMock 标记的 GPS，仅 Root + LSPosed 或虚拟沙盒容器可抹除该标记。"
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
                    "开启前先关闭后台扫描并划掉目标应用",
                    "在系统「设置 → 位置信息」关闭 Wi-Fi/蓝牙扫描，并彻底划掉微信等目标应用后再开启漂移。"
                )
                addStepCard(
                    root,
                    "!",
                    "免 Root 能力边界",
                    "纯免 Root 无法隐藏 isMock 标记或基站信息，适用于高德/百度地图等普通应用；微信等强风控应用需在 Root + LSPosed 模式下使用。"
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
                        "实时切换地点",
                        "漂移中重新选点后点击「同步新选点」，600ms 内全局生效。"
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

                ShizukuHelper.runShizukuTask {
                    val mockOk = ShizukuHelper.grantMockAndLocationPermissions(this)
                    shizukuAutoGrantTried = true
                    if (cfg.enableSystemMock && !mockOk) {
                        runOnUiThread {
                            updateEnvironmentUI()
                            toast("请在开发者选项中手动选择本应用")
                            openDeveloperOptions()
                        }
                        return@runShizukuTask
                    }
                    if (suppressScan) {
                        ShizukuHelper.applyAntiDriftScanSuppression(this)
                    }
                    val isolatedCount = if (isolateApps) {
                        ShizukuHelper.applyTargetAppLocationIsolation(this, forceRestartTarget = true)
                    } else {
                        0
                    }
                    runOnUiThread {
                        isEnabled = true
                        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
                        updateEnvironmentUI()
                        launchForegroundServices(cfg)
                        refreshRunStatusUI()
                        val msg = when {
                            isolatedCount > 0 -> "已开启（已隔离并重置 $isolatedCount 个目标应用）"
                            suppressScan -> "已开启（已自动关扫描）"
                            else -> "已开启定位漂移"
                        }
                        toast(msg)
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
                launchForegroundServices(cfg)
                refreshRunStatusUI()
                toast("已开启定位漂移")
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
            toast("请先在开发者选项授权模拟位置")
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
            toast("已同步新选点")
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
            ShizukuHelper.restoreAllAntiDriftStates(this)
        }

        refreshRunStatusUI()
        toast("已停止定位漂移")
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
                if (isEnabled) {
                    ConfigManager.save(this, buildCurrentConfig(enabled = true), syncTmpWithRoot = false)
                }
                toast("未授予 VPN 权限")
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
            toast("已复制三系坐标")
        } catch (_: Throwable) {
            toast("复制失败")
        }
    }

    private fun refreshRunStatusUI() {
        if (isEnabled) {
            tvRunStatus.text = when (selectedMode) {
                WorkMode.ROOT_LSPOSED -> "● 运行中"
                WorkMode.SHIZUKU -> "● Shizuku运行"
                WorkMode.ROOTLESS_BASIC -> "● 基础运行"
            }
            tvRunStatus.setTextColor(Color.parseColor("#059669"))
            btnToggleDrift.text = "停止定位漂移"
            btnToggleDrift.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            btnUpdatePoint.visibility = View.VISIBLE
        } else {
            tvRunStatus.text = "● 未开启"
            tvRunStatus.setTextColor(Color.parseColor("#64748B"))
            btnToggleDrift.text = "开启定位漂移"
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

    private fun searchViaBaiduPoi(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
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

    private fun searchViaBaiduSuggest(encodedQuery: String, fallbackName: String): List<PlaceSearchResult> {
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

            val fBaiduPoi = searchPool.submit(Callable { searchViaBaiduPoi(encoded, query) })
            val fBaiduSug = searchPool.submit(Callable { searchViaBaiduSuggest(encoded, query) })
            val fArcGis = searchPool.submit(Callable { searchViaArcGis(encoded, query) })
            val fPhoton = searchPool.submit(Callable { searchViaPhoton(encoded, query) })

            val deadline = System.currentTimeMillis() + 4200L
            var candidates: List<PlaceSearchResult> = emptyList()

            while (System.currentTimeMillis() < deadline) {
                if (searchGeneration.get() != gen) break

                val poiRes = if (fBaiduPoi.isDone) runCatching { fBaiduPoi.get() }.getOrDefault(emptyList()) else emptyList()
                if (poiRes.isNotEmpty()) {
                    candidates = poiRes
                    break
                }

                val sugRes = if (fBaiduSug.isDone) runCatching { fBaiduSug.get() }.getOrDefault(emptyList()) else emptyList()
                if (sugRes.isNotEmpty()) {
                    candidates = sugRes
                    break
                }

                val bothDomesticDone = fBaiduPoi.isDone && fBaiduSug.isDone
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
                candidates = runCatching { fBaiduPoi.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
                    .ifEmpty { runCatching { fBaiduSug.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList()) }
                    .ifEmpty { runCatching { fArcGis.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList()) }
                    .ifEmpty { runCatching { fPhoton.get(100, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList()) }
                    .ifEmpty { searchViaBuiltinCities(query) }
                    .ifEmpty {
                        val fSys = searchPool.submit(Callable { searchViaSystemGeocoder(query) })
                        runCatching { fSys.get(1500, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
                    }
            }

            fBaiduPoi.cancel(true)
            fBaiduSug.cancel(true)
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
        fun onMapReady() {
            runOnUiThread {
                syncMarkerToWebMap(currentGcjLat, currentGcjLng, currentAddress)
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
