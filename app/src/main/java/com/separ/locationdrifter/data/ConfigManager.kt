package com.separ.locationdrifter.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.separ.locationdrifter.util.RootUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一管理当前选点坐标、开关状态与收藏地点，同时同步到：
 * 1) Settings.Global (system_server 等系统进程零延迟实时读取)
 * 2) LSPosed MODE_WORLD_READABLE SharedPreferences
 * 3) /data/local/tmp/location_drifter.json (通过 RootUtils 串行队列落盘兜底)
 */
object ConfigManager {

    const val PREFS_NAME = "location_config"
    const val TMP_CONFIG_PATH = "/data/local/tmp/location_drifter.json"
    const val SETTINGS_GLOBAL_KEY = "location_drifter_cfg"
    const val SETTINGS_GLOBAL_HOOKED_BOOT_KEY = "location_drifter_hooked_boot"

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
    const val KEY_FAVORITES = "favorites"
    const val KEY_UPDATED_AT = "updated_at"

    // 默认坐标：北京天安门 (GCJ-02 & WGS-84)
    private const val DEFAULT_GCJ_LAT = 39.908823
    private const val DEFAULT_GCJ_LNG = 116.397470
    private const val DEFAULT_WGS_LAT = 39.907418
    private const val DEFAULT_WGS_LNG = 116.391227

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
        val updatedAt: Long
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
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
            prefs to true
        } catch (_: SecurityException) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs to false
        }
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
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun save(context: Context, config: DriftConfig, syncTmpWithRoot: Boolean = true) {
        val (prefs, _) = getPrefsAndWorldReadableState(context)
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
            .putLong(KEY_UPDATED_AT, now)
            .commit()

        val json = toJson(config.copy(updatedAt = now)).toString()
        try {
            Settings.Global.putString(context.contentResolver, SETTINGS_GLOBAL_KEY, json)
        } catch (_: Throwable) {
        }

        if (syncTmpWithRoot) {
            RootUtils.runRootTask {
                RootUtils.writeTmpConfigJson(json)
            }
        }
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
