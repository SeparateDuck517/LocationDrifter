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
import com.separ.locationdrifter.xposed.ModuleStatus
import org.json.JSONArray
import org.json.JSONObject
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

    private data class PlaceSearchResult(
        val gcjLat: Double,
        val gcjLng: Double,
        val label: String,
        val detail: String = ""
    )

    private lateinit var tvSubtitleMode: TextView
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
    private var rootCheckCompleted = false
    private var rootlessDefaultsApplied = false
    private var isEnabled = false
    private var suppressCheckboxCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLightSystemBars()
        setContentView(R.layout.activity_main)

        initViews()
        loadSavedState()
        bindCheckboxListeners()
        initWebView()
        checkEnvironmentStatus()
        requestBasicPermissionsIfNeeded()
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
        updateEnvironmentUI()
        refreshRunStatusUI()
        refreshFavoritesCount()
    }

    private fun initViews() {
        tvSubtitleMode = findViewById(R.id.tvSubtitleMode)
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

        val openGuideClick = View.OnClickListener {
            showEnvironmentGuideDialog()
        }
        btnHelpGuide.setOnClickListener(openGuideClick)
        cardDegradedBanner.setOnClickListener(openGuideClick)
        btnDegradedAction.setOnClickListener(openGuideClick)
        tvRootStatus.setOnClickListener(openGuideClick)
        tvXposedStatus.setOnClickListener(openGuideClick)

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
        val listener = CompoundButton.OnCheckedChangeListener { _, _ ->
            if (suppressCheckboxCallback) return@OnCheckedChangeListener
            if (isEnabled) {
                applyUpdatedCoordinates(showToast = false)
            } else {
                ConfigManager.save(this, buildCurrentConfig(enabled = false), syncTmpWithRoot = false)
            }
        }
        val lockedTouchExplain = View.OnTouchListener { v, event ->
            if (!v.isEnabled && event.action == MotionEvent.ACTION_UP) {
                val reason = if (v.id == R.id.cbIptables) {
                    "「内核阻断」依赖 Root 权限，免 Root 模式下请使用「VPN阻断(推荐)」替代"
                } else {
                    "「防基站/WiFi漂移」依赖 Root + LSPosed 系统框架注入，免 Root 模式下不可用"
                }
                toast(reason)
                showEnvironmentGuideDialog()
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
        updateEnvironmentUI()
        RootUtils.runRootTask {
            val rootOk = RootUtils.isRootAvailable()
            if (rootOk) {
                RootUtils.grantMockAndLocationPermissions(packageName)
            }
            runOnUiThread {
                hasRoot = rootOk
                rootCheckCompleted = true
                updateEnvironmentUI()
            }
        }
    }

    private fun updateEnvironmentUI() {
        // 严格依赖模块自 Hook 状态（New XSharedPreferences 在模块开关关闭时仍可能返回 true，不可作为激活依据）
        hasXposed = ModuleStatus.isModuleActive()

        if (!rootCheckCompleted) {
            tvRootStatus.text = "Root: 检测中..."
            tvRootStatus.setTextColor(Color.parseColor("#475569"))
            tvXposedStatus.text = if (hasXposed) "LSPosed: 已激活 ✓" else "LSPosed: 检测中..."
            tvXposedStatus.setTextColor(
                if (hasXposed) Color.parseColor("#059669") else Color.parseColor("#475569")
            )
            return
        }

        if (hasRoot && hasXposed) {
            // 完整模式：Root + LSPosed
            tvSubtitleMode.text = "零三方应用注入架构 · 系统级防漂移"
            tvRootStatus.text = "Root: 已授权 ✓"
            tvRootStatus.setTextColor(Color.parseColor("#059669"))
            tvXposedStatus.text = "LSPosed: 已激活 ✓"
            tvXposedStatus.setTextColor(Color.parseColor("#059669"))
            cardDegradedBanner.visibility = View.GONE

            setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
            setCheckboxState(cbAntiDrift, enabled = true, label = "防基站/WiFi漂移")
            setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
            setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
        } else if (hasRoot && !hasXposed) {
            // 仅 Root 模式（LSPosed 未激活）
            tvSubtitleMode.text = "Root 已就绪 · 等待 LSPosed 模块激活"
            tvRootStatus.text = "Root: 已授权 ✓"
            tvRootStatus.setTextColor(Color.parseColor("#059669"))
            tvXposedStatus.text = "LSPosed: 未激活 ›"
            tvXposedStatus.setTextColor(Color.parseColor("#D97706"))

            cardDegradedBanner.visibility = View.VISIBLE
            tvDegradedTitle.text = "已获取 Root，但 LSPosed 模块尚未激活"
            tvDegradedDesc.text =
                "当前仅可启用系统模拟定位与内核阻断，无法抹除 Mock 标记或底层隔离基站/Wi-Fi。点击查看激活与作用域配置步骤。"
            btnDegradedAction.text = "激活向导 ›"

            setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
            setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
            setCheckboxState(cbIptables, enabled = true, label = "内核阻断")
            setCheckboxState(cbVpn, enabled = true, label = "VPN阻断")
        } else {
            // 免 Root 降级兼容模式（无 Root / 无 Shizuku）
            val mockAllowed = RootUtils.isMockLocationAllowed(this)
            tvSubtitleMode.text = "免 Root 降级兼容 · 系统模拟定位 + RST-VPN"
            tvRootStatus.text = "模式: 免Root降级 ›"
            tvRootStatus.setTextColor(Color.parseColor("#D97706"))

            cardDegradedBanner.visibility = View.VISIBLE
            if (mockAllowed) {
                tvXposedStatus.text = "模拟定位: 已授权 ✓"
                tvXposedStatus.setTextColor(Color.parseColor("#059669"))
                tvDegradedTitle.text = "免 Root 降级模式已就绪"
                tvDegradedDesc.text =
                    "已启用系统GPS + 局部RST-VPN（主动回发 TCP RST 加速回落 GPS，不拦截正常上网）。受限于无Root，无法隐藏 isMock 标记或屏蔽周边 Wi-Fi/基站。"
                btnDegradedAction.text = "防漂移指南 ›"
            } else {
                tvXposedStatus.text = "模拟定位: 未设置 ›"
                tvXposedStatus.setTextColor(Color.parseColor("#DC2626"))
                tvDegradedTitle.text = "⚠️ 需先完成 1 步设置：授权模拟位置应用"
                tvDegradedDesc.text =
                    "当前无 Root 权限，请先在系统「开发者选项 → 选择模拟位置信息应用」中选中「定位漂移助手」，返回即可开启。"
                btnDegradedAction.text = "去授权设置 ›"
            }

            setCheckboxState(cbSystemMock, enabled = true, label = "系统GPS注入")
            setCheckboxState(cbAntiDrift, enabled = false, checked = false, label = "防基站(需LSPosed)")
            setCheckboxState(cbIptables, enabled = false, checked = false, label = "内核阻断(需Root)")
            val autoEnableVpn = if (!rootlessDefaultsApplied && !isEnabled) {
                rootlessDefaultsApplied = true
                true
            } else {
                null
            }
            setCheckboxState(cbVpn, enabled = true, checked = autoEnableVpn, label = "VPN阻断(推荐)")
        }
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
     * 统一结构化环境自检与使用指引弹窗（自动适配完整模式、仅Root模式、免Root降级模式）
     */
    private fun showEnvironmentGuideDialog() {
        val mockAllowed = RootUtils.isMockLocationAllowed(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        scroll.addView(root)

        // 1. 当前环境实时自检卡片
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

        if (hasRoot && hasXposed) {
            addStatusCheckRow(statusCard, "运行架构", "Root + LSPosed 完整模式", "#059669")
            addStatusCheckRow(statusCard, "系统GPS & 模拟标记抹除", "已就绪 (零三方应用注入)", "#059669")
            addStatusCheckRow(statusCard, "底层基站/Wi-Fi源头隔离", "已就绪 (Binder IPC 拦截)", "#059669")
            addStatusCheckRow(statusCard, "内核 iptables 阻断", "已就绪", "#059669")
        } else if (hasRoot && !hasXposed) {
            addStatusCheckRow(statusCard, "Root 权限", "已授权", "#059669")
            addStatusCheckRow(statusCard, "LSPosed 模块状态", "未激活 (需勾选作用域并重启)", "#D97706")
            addStatusCheckRow(statusCard, "系统GPS & 内核阻断", "可用", "#059669")
            addStatusCheckRow(statusCard, "Mock标记抹除 / 基站隔离", "暂不可用 (依赖 LSPosed)", "#DC2626")
        } else {
            addStatusCheckRow(statusCard, "运行架构", "免 Root 降级兼容模式", "#D97706")
            addStatusCheckRow(
                statusCard,
                "1. 模拟位置应用授权",
                if (mockAllowed) "已授权 ✓" else "未设置 (必须配置) ✗",
                if (mockAllowed) "#059669" else "#DC2626"
            )
            addStatusCheckRow(statusCard, "2. 局部 RST-VPN 通道", "支持 (免证书秒级拒止)", "#059669")
            addStatusCheckRow(statusCard, "3. 抹除isMock / 基站隔离", "不可用 (需Root+LSPosed)", "#64748B")
        }
        root.addView(statusCard)

        // 2. 分步指引卡片
        if (!hasRoot) {
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
                "局部 VPN 仅对腾讯等网络定位域名建立精准路由并主动回发 TCP RST，不会接管正常上网流量，可迫使定位 SDK 快速回落至虚拟 GPS。"
            )
            addStepCard(
                root,
                "3",
                "关键防漂移技巧：关闭系统 Wi-Fi / 蓝牙扫描",
                "免 Root 下系统仍会通过周边 Wi-Fi 热点辅助校准位置。建议前往系统「设置 → 位置信息 → 位置服务/定位精度」，关闭「Wi-Fi 扫描」与「蓝牙扫描」，可大幅减少坐标回弹。"
            )
            addStepCard(
                root,
                "!",
                "免 Root 模式能力边界说明",
                "适用于普通地图、打卡及不强校验系统底层模拟标记的应用；若目标 App 强检测 Location.isMock() 或读取基站指纹，需在 Root + LSPosed 完整模式下使用。"
            )
        } else if (!hasXposed) {
            addStepCard(
                root,
                "1",
                "在 LSPosed 中启用本模块",
                "打开 LSPosed 管理器 →「模块」→ 找到并开启「定位漂移助手」。",
                highlight = true
            )
            addStepCard(
                root,
                "2",
                "确认作用域仅勾选系统进程（零三方注入）",
                "作用域仅需勾选「系统框架 (android)」与「电话服务 (com.android.phone)」，无需勾选任何第三方业务 App，避免触发应用检测。"
            )
            addStepCard(
                root,
                "3",
                "重启手机或执行软重启生效",
                "修改系统框架作用域后，重启一次设备即可解锁「防基站/WiFi漂移」与底层 Mock 标记抹除。"
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

        val builder = AlertDialog.Builder(this)
            .setTitle(if (hasRoot && hasXposed) "完整模式使用说明" else "环境自检与配置向导")
            .setView(scroll)
            .setNegativeButton("知道了", null)

        if (!hasRoot) {
            builder.setPositiveButton("打开开发者选项") { _, _ ->
                openDeveloperOptions()
            }
            builder.setNeutralButton("系统位置设置") { _, _ ->
                openLocationSourceSettings()
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

    private fun openLocationSourceSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Throwable) {
            toast("请手动打开系统「设置 → 位置信息」")
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
        if (!hasRoot && cbSystemMock.isChecked && !RootUtils.isMockLocationAllowed(this)) {
            toast("请先在开发者选项中授权模拟位置应用")
            showEnvironmentGuideDialog()
            return
        }
        if (!cbSystemMock.isChecked && !cbVpn.isChecked && !cbIptables.isChecked && !cbAntiDrift.isChecked) {
            toast("请至少启用一项定位或防漂移策略")
            return
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
            toast("已开启免 Root 降级漂移（如遇回弹可点右上角「使用指引」）")
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
        if (!hasRoot && cbSystemMock.isChecked && !RootUtils.isMockLocationAllowed(this)) {
            toast("请先在开发者选项中授权模拟位置应用")
            showEnvironmentGuideDialog()
            return
        }

        val cfg = buildCurrentConfig(enabled = true)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

        if (hasRoot) {
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
        return ConfigManager.DriftConfig(
            enabled = enabled,
            wgsLat = currentWgsLat,
            wgsLng = currentWgsLng,
            gcjLat = currentGcjLat,
            gcjLng = currentGcjLng,
            address = currentAddress,
            enableSystemMock = cbSystemMock.isChecked,
            blockNetworkDrift = cbAntiDrift.isChecked && hasXposed,
            enableIptables = cbIptables.isChecked && hasRoot,
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
            tvRunStatus.text = if (hasRoot) "● 漂移生效中" else "● 降级运行中"
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
