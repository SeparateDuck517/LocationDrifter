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
import android.location.Geocoder
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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

    private lateinit var tvRootStatus: TextView
    private lateinit var tvXposedStatus: TextView
    private lateinit var tvRunStatus: TextView
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
        refreshXposedStatus()
        refreshRunStatusUI()
        refreshFavoritesCount()
    }

    private fun initViews() {
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvXposedStatus = findViewById(R.id.tvXposedStatus)
        tvRunStatus = findViewById(R.id.tvRunStatus)
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

    private fun bindCheckboxListeners() {
        val listener = CompoundButton.OnCheckedChangeListener { _, _ ->
            if (suppressCheckboxCallback) return@OnCheckedChangeListener
            if (isEnabled) {
                applyUpdatedCoordinates(showToast = false)
            } else {
                ConfigManager.save(this, buildCurrentConfig(enabled = false), syncTmpWithRoot = false)
            }
        }
        cbSystemMock.setOnCheckedChangeListener(listener)
        cbAntiDrift.setOnCheckedChangeListener(listener)
        cbIptables.setOnCheckedChangeListener(listener)
        cbVpn.setOnCheckedChangeListener(listener)
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
        refreshXposedStatus()
        RootUtils.runRootTask {
            val rootOk = RootUtils.isRootAvailable()
            if (rootOk) {
                RootUtils.grantMockAndLocationPermissions(packageName)
            }
            runOnUiThread {
                hasRoot = rootOk
                tvRootStatus.text = if (rootOk) "Root: 已授权" else "Root: 未授权"
                tvRootStatus.setTextColor(if (rootOk) Color.parseColor("#059669") else Color.parseColor("#DC2626"))
            }
        }
    }

    private fun refreshXposedStatus() {
        val active = ModuleStatus.isModuleActive() || ConfigManager.isWorldReadableSupported(this)
        tvXposedStatus.text = if (active) "LSPosed: 已激活" else "LSPosed: 未激活"
        tvXposedStatus.setTextColor(if (active) Color.parseColor("#059669") else Color.parseColor("#DC2626"))
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
        isEnabled = true
        val cfg = buildCurrentConfig(enabled = true)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

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
                if (cfg.enableSystemMock) {
                    startMockLocationService()
                }
                if (cfg.enableVpn) {
                    prepareAndStartVpn()
                }
                refreshRunStatusUI()
                toast("已开启定位漂移")
            }
        }
    }

    private fun applyUpdatedCoordinates(showToast: Boolean = true) {
        val cfg = buildCurrentConfig(enabled = true)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

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
        }
    }

    private fun stopDrifting() {
        isEnabled = false
        val cfg = buildCurrentConfig(enabled = false)
        ConfigManager.save(this, cfg, syncTmpWithRoot = false)
        val json = ConfigManager.toJson(cfg).toString()

        stopService(Intent(this, MockLocationService::class.java))
        stopVpnService()

        RootUtils.runRootTask {
            if (RootUtils.isRootAvailable()) {
                RootUtils.writeTmpConfigJson(json)
                RootUtils.disableIptablesBlock()
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
            blockNetworkDrift = cbAntiDrift.isChecked,
            enableIptables = cbIptables.isChecked,
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
            tvRunStatus.text = "● 漂移生效中"
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
