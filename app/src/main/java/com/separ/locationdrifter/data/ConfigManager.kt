package com.separ.locationdrifter.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.separ.locationdrifter.util.RootUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 统一管理当前选点坐标、开关状态、实验性步数模拟与收藏地点，同时异步同步到：
 * 1) Settings.Global (system_server 等系统进程零延迟实时读取)
 * 2) LSPosed MODE_WORLD_READABLE SharedPreferences
 * 3) /data/local/tmp/location_drifter.json (通过 RootUtils 串行队列落盘兜底)
 */
object ConfigManager {

    const val PREFS_NAME = "location_config"
    const val TMP_CONFIG_PATH = "/data/local/tmp/location_drifter.json"
    const val SETTINGS_GLOBAL_KEY = "location_drifter_cfg"
    const val SETTINGS_GLOBAL_HOOKED_BOOT_KEY = "location_drifter_hooked_boot"
    const val SETTINGS_GLOBAL_STEP_GUARD_KEY = "location_drifter_step_hook_guard"

    const val KEY_ENABLED = "enabled"
    const val KEY_WGS_LAT = "wgs_lat"
    const val KEY_WGS_LNG = "wgs_lng"
    const val KEY_GCJ_LAT = "gcj_lat"
    const val KEY_GCJ_LNG = "gcj_lng"
    const val KEY_ADDRESS = "address"
    const val KEY_ENABLE_SYSTEM_MOCK = "enable_system_mock"
    const val KEY_BLOCK_NETWORK_DRIFT = "block_network_drift"
    const val KEY_ENABLE_IPTABLES = "enable_iptables"
    const val KEY_ENABLE_VPN = "enable_vpn"
    const val KEY_JITTER = "enable_jitter"
    const val KEY_ENABLE_STEP_SIM = "enable_step_sim"
    const val KEY_SIM_STEP_COUNT = "sim_step_count"
    const val KEY_FAVORITES = "favorites"
    const val KEY_UPDATED_AT = "updated_at"

    private const val DEFAULT_GCJ_LAT = 39.908823
    private const val DEFAULT_GCJ_LNG = 116.397470
    private const val DEFAULT_WGS_LAT = 39.907418
    private const val DEFAULT_WGS_LNG = 116.391227

    private val settingsSyncExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedPrefsPair: Pair<SharedPreferences, Boolean>? = null

    @Volatile
    private var lastRootTmpSyncMs: Long = 0L

    private var fractionalStepAccumulator: Double = 0.0

    data class DriftConfig(
        val enabled: Boolean,
        val wgsLat: Double,
        val wgsLng: Double,
        val gcjLat: Double,
        val gcjLng: Double,
        val address: String,
        val enableSystemMock: Boolean,
        val blockNetworkDrift: Boolean,
        val enableIptables: Boolean,
        val enableVpn: Boolean,
        val enableJitter: Boolean,
        val updatedAt: Long,
        val enableStepSim: Boolean = false,
        val simStepCount: Long = 0L
    )

    data class FavoriteSpot(
        val name: String,
        val gcjLat: Double,
        val gcjLng: Double,
        val wgsLat: Double,
        val wgsLng: Double
    )

    @Suppress("DEPRECATION")
    @SuppressLint("WorldReadableFiles")
    fun getPrefsAndWorldReadableState(context: Context): Pair<SharedPreferences, Boolean> {
        cachedPrefsPair?.let { return it }
        val appCtx = context.applicationContext ?: context
        val pair = try {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
            prefs to true
        } catch (_: SecurityException) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs to false
        }
        cachedPrefsPair = pair
        return pair
    }

    fun isWorldReadableSupported(context: Context): Boolean {
        return getPrefsAndWorldReadableState(context).second
    }

    fun load(context: Context): DriftConfig {
        val (prefs, _) = getPrefsAndWorldReadableState(context)
        return DriftConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            wgsLat = prefs.getString(KEY_WGS_LAT, null)?.toDoubleOrNull() ?: DEFAULT_WGS_LAT,
            wgsLng = prefs.getString(KEY_WGS_LNG, null)?.toDoubleOrNull() ?: DEFAULT_WGS_LNG,
            gcjLat = prefs.getString(KEY_GCJ_LAT, null)?.toDoubleOrNull() ?: DEFAULT_GCJ_LAT,
            gcjLng = prefs.getString(KEY_GCJ_LNG, null)?.toDoubleOrNull() ?: DEFAULT_GCJ_LNG,
            address = prefs.getString(KEY_ADDRESS, "北京天安门") ?: "北京天安门",
            enableSystemMock = prefs.getBoolean(KEY_ENABLE_SYSTEM_MOCK, true),
            blockNetworkDrift = prefs.getBoolean(KEY_BLOCK_NETWORK_DRIFT, true),
            enableIptables = prefs.getBoolean(KEY_ENABLE_IPTABLES, true),
            enableVpn = prefs.getBoolean(KEY_ENABLE_VPN, false),
            enableJitter = prefs.getBoolean(KEY_JITTER, true),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            enableStepSim = prefs.getBoolean(KEY_ENABLE_STEP_SIM, false),
            simStepCount = prefs.getLong(KEY_SIM_STEP_COUNT, 0L)
        )
    }

    fun save(context: Context, config: DriftConfig, syncTmpWithRoot: Boolean = true) {
        val appCtx = context.applicationContext ?: context
        val (prefs, _) = getPrefsAndWorldReadableState(appCtx)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_WGS_LAT, config.wgsLat.toString())
            .putString(KEY_WGS_LNG, config.wgsLng.toString())
            .putString(KEY_GCJ_LAT, config.gcjLat.toString())
            .putString(KEY_GCJ_LNG, config.gcjLng.toString())
            .putString(KEY_ADDRESS, config.address)
            .putBoolean(KEY_ENABLE_SYSTEM_MOCK, config.enableSystemMock)
            .putBoolean(KEY_BLOCK_NETWORK_DRIFT, config.blockNetworkDrift)
            .putBoolean(KEY_ENABLE_IPTABLES, config.enableIptables)
            .putBoolean(KEY_ENABLE_VPN, config.enableVpn)
            .putBoolean(KEY_JITTER, config.enableJitter)
            .putBoolean(KEY_ENABLE_STEP_SIM, config.enableStepSim)
            .putLong(KEY_SIM_STEP_COUNT, config.simStepCount)
            .putLong(KEY_UPDATED_AT, now)
            .apply()

        val json = toJson(config.copy(updatedAt = now)).toString()
        settingsSyncExecutor.execute {
            try {
                Settings.Global.putString(appCtx.contentResolver, SETTINGS_GLOBAL_KEY, json)
            } catch (_: Throwable) {
            }
        }

        if (syncTmpWithRoot) {
            RootUtils.runRootTask {
                RootUtils.writeTmpConfigJson(json)
            }
        }
    }

    /**
     * 按移动距离与速度区间换算自然步数增量（实验性步数模拟）：
     * - 步行 / 跑步区间 (0.4 ~ 6.2 m/s) 自动按配速换算步幅并叠加微小自然抖动；
     * - 骑行 (>= 6.5 m/s) 与驾车模式默认不累加步数，避免运动风控异常。
     */
    @Synchronized
    fun accumulateSimulatedSteps(
        context: Context,
        distanceMeters: Double,
        speedMps: Float
    ): Long {
        if (distanceMeters <= 0.0) return 0L
        val appCtx = context.applicationContext ?: context
        val current = load(appCtx)
        if (!current.enabled || !current.enableStepSim) return current.simStepCount

        // 仅步行与跑步速度区间计步；骑行与驾车不累加步数
        if (speedMps < 0.4f || speedMps > 6.2f) return current.simStepCount

        val strideMeters = (0.68 + (speedMps.toDouble() - 1.2).coerceAtLeast(0.0) * 0.24)
            .coerceIn(0.68, 1.42)
        val jitter = Random.nextDouble(0.95, 1.05)
        fractionalStepAccumulator += (distanceMeters / strideMeters) * jitter

        val wholeSteps = fractionalStepAccumulator.toLong()
        if (wholeSteps <= 0L) return current.simStepCount
        fractionalStepAccumulator -= wholeSteps.toDouble()

        val newTotal = current.simStepCount + wholeSteps
        val (prefs, _) = getPrefsAndWorldReadableState(appCtx)
        prefs.edit().putLong(KEY_SIM_STEP_COUNT, newTotal).apply()
        return newTotal
    }

    /**
     * 高频实时移动（摇杆 / 轨迹模拟）专用轻量坐标更新：
     * 仅更新坐标、步数与时间戳，异步写入 Settings.Global（供 system_server 实时读取），
     * 并对 Root 兜底文件写入进行 1.2s 节流，避免高频拉起 su 子进程。
     */
    fun updateLiveCoordinates(
        context: Context,
        wgsLat: Double,
        wgsLng: Double,
        gcjLat: Double,
        gcjLng: Double,
        address: String? = null
    ): DriftConfig {
        val appCtx = context.applicationContext ?: context
        val current = load(appCtx)
        val now = System.currentTimeMillis()
        val updated = current.copy(
            wgsLat = wgsLat,
            wgsLng = wgsLng,
            gcjLat = gcjLat,
            gcjLng = gcjLng,
            address = address ?: current.address,
            updatedAt = now
        )
        val (prefs, _) = getPrefsAndWorldReadableState(appCtx)
        prefs.edit()
            .putString(KEY_WGS_LAT, updated.wgsLat.toString())
            .putString(KEY_WGS_LNG, updated.wgsLng.toString())
            .putString(KEY_GCJ_LAT, updated.gcjLat.toString())
            .putString(KEY_GCJ_LNG, updated.gcjLng.toString())
            .putString(KEY_ADDRESS, updated.address)
            .putLong(KEY_UPDATED_AT, now)
            .apply()

        val json = toJson(updated).toString()
        settingsSyncExecutor.execute {
            try {
                Settings.Global.putString(appCtx.contentResolver, SETTINGS_GLOBAL_KEY, json)
            } catch (_: Throwable) {
            }
        }

        if (updated.enabled && updated.blockNetworkDrift && now - lastRootTmpSyncMs >= 1200L) {
            lastRootTmpSyncMs = now
            RootUtils.runRootTask {
                RootUtils.writeTmpConfigJson(json)
            }
        }
        return updated
    }

    fun toJson(config: DriftConfig): JSONObject {
        return JSONObject().apply {
            put(KEY_ENABLED, config.enabled)
            put(KEY_WGS_LAT, config.wgsLat)
            put(KEY_WGS_LNG, config.wgsLng)
            put(KEY_GCJ_LAT, config.gcjLat)
            put(KEY_GCJ_LNG, config.gcjLng)
            put(KEY_ADDRESS, config.address)
            put(KEY_BLOCK_NETWORK_DRIFT, config.blockNetworkDrift)
            put(KEY_JITTER, config.enableJitter)
            put(KEY_ENABLE_STEP_SIM, config.enableStepSim)
            put(KEY_SIM_STEP_COUNT, config.simStepCount)
            put(KEY_UPDATED_AT, config.updatedAt)
        }
    }

    fun getFavorites(context: Context): List<FavoriteSpot> {
        val (prefs, _) = getPrefsAndWorldReadableState(context)
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<FavoriteSpot>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    FavoriteSpot(
                        name = o.optString("name", "收藏位置"),
                        gcjLat = o.getDouble("gcjLat"),
                        gcjLng = o.getDouble("gcjLng"),
                        wgsLat = o.getDouble("wgsLat"),
                        wgsLng = o.getDouble("wgsLng")
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addFavorite(context: Context, spot: FavoriteSpot) {
        val current = getFavorites(context).toMutableList()
        current.removeAll { it.name == spot.name }
        current.add(0, spot)
        val arr = JSONArray()
        current.take(30).forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("gcjLat", s.gcjLat)
                put("gcjLng", s.gcjLng)
                put("wgsLat", s.wgsLat)
                put("wgsLng", s.wgsLng)
            })
        }
        val (prefs, _) = getPrefsAndWorldReadableState(context)
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    fun removeFavorite(context: Context, index: Int) {
        val current = getFavorites(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            val arr = JSONArray()
            current.forEach { s ->
                arr.put(JSONObject().apply {
                    put("name", s.name)
                    put("gcjLat", s.gcjLat)
                    put("gcjLng", s.gcjLng)
                    put("wgsLat", s.wgsLat)
                    put("wgsLng", s.wgsLng)
                })
            }
            val (prefs, _) = getPrefsAndWorldReadableState(context)
            prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
        }
    }

    fun clearFavorites(context: Context) {
        val (prefs, _) = getPrefsAndWorldReadableState(context)
        prefs.edit().remove(KEY_FAVORITES).apply()
    }
}
