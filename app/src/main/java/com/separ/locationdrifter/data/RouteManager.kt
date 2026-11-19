package com.separ.locationdrifter.data

import android.content.Context
import com.separ.locationdrifter.util.CoordTransform
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轨迹规划与持久化管理器：
 * 1) 维护当前待执行轨迹（支持单次 / 循环 / 往返模式与可自定义速度档位）
 * 2) 持久化用户保存的常用轨迹列表
 * 3) 提供高精度 Haversine 距离、方位角与沿线插值计算
 */
object RouteManager {

    private const val PREFS_NAME = "route_planner_prefs"
    private const val KEY_ACTIVE_POINTS = "active_route_points"
    private const val KEY_ACTIVE_SPEED = "active_route_speed"
    private const val KEY_ACTIVE_LOOP_MODE = "active_route_loop_mode"
    private const val KEY_SAVED_ROUTES = "saved_routes_json"
    private const val KEY_JOYSTICK_SPEED = "joystick_speed_mps"
    private const val KEY_CUSTOM_WALK_SPEED = "custom_walk_speed"
    private const val KEY_CUSTOM_RUN_SPEED = "custom_run_speed"
    private const val KEY_CUSTOM_BIKE_SPEED = "custom_bike_speed"
    private const val KEY_CUSTOM_DRIVE_SPEED = "custom_drive_speed"

    const val DEFAULT_WALK_SPEED = 1.5f
    const val DEFAULT_RUN_SPEED = 4.0f
    const val DEFAULT_BIKE_SPEED = 8.0f
    const val DEFAULT_DRIVE_SPEED = 16.0f

    @Volatile var walkSpeedMps: Float = DEFAULT_WALK_SPEED
        private set
    @Volatile var runSpeedMps: Float = DEFAULT_RUN_SPEED
        private set
    @Volatile var bikeSpeedMps: Float = DEFAULT_BIKE_SPEED
        private set
    @Volatile var driveSpeedMps: Float = DEFAULT_DRIVE_SPEED
        private set

    const val LOOP_ONCE = 0
    const val LOOP_REPEAT = 1
    const val LOOP_PING_PONG = 2

    private const val EARTH_RADIUS_M = 6371000.0

    data class RoutePoint(
        val gcjLat: Double,
        val gcjLng: Double,
        val wgsLat: Double,
        val wgsLng: Double
    )

    data class SavedRoute(
        val name: String,
        val points: List<RoutePoint>,
        val speedMps: Float = 4.0f,
        val loopMode: Int = LOOP_ONCE
    )

    data class RouteSample(
        val wgsLat: Double,
        val wgsLng: Double,
        val gcjLat: Double,
        val gcjLng: Double,
        val bearingDeg: Float,
        val traveledMeters: Double,
        val totalMeters: Double
    )

    fun initCustomSpeeds(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        walkSpeedMps = prefs.getFloat(KEY_CUSTOM_WALK_SPEED, DEFAULT_WALK_SPEED).coerceIn(0.3f, 3.5f)
        runSpeedMps = prefs.getFloat(KEY_CUSTOM_RUN_SPEED, DEFAULT_RUN_SPEED).coerceIn(2.0f, 8.0f)
        bikeSpeedMps = prefs.getFloat(KEY_CUSTOM_BIKE_SPEED, DEFAULT_BIKE_SPEED).coerceIn(4.0f, 18.0f)
        driveSpeedMps = prefs.getFloat(KEY_CUSTOM_DRIVE_SPEED, DEFAULT_DRIVE_SPEED).coerceIn(8.0f, 40.0f)
    }

    fun saveCustomSpeeds(
        context: Context,
        walk: Float,
        run: Float,
        bike: Float,
        drive: Float
    ) {
        walkSpeedMps = walk.coerceIn(0.3f, 3.5f)
        runSpeedMps = run.coerceIn(2.0f, 8.0f)
        bikeSpeedMps = bike.coerceIn(4.0f, 18.0f)
        driveSpeedMps = drive.coerceIn(8.0f, 40.0f)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CUSTOM_WALK_SPEED, walkSpeedMps)
            .putFloat(KEY_CUSTOM_RUN_SPEED, runSpeedMps)
            .putFloat(KEY_CUSTOM_BIKE_SPEED, bikeSpeedMps)
            .putFloat(KEY_CUSTOM_DRIVE_SPEED, driveSpeedMps)
            .apply()
    }

    fun resetCustomSpeeds(context: Context) {
        saveCustomSpeeds(context, DEFAULT_WALK_SPEED, DEFAULT_RUN_SPEED, DEFAULT_BIKE_SPEED, DEFAULT_DRIVE_SPEED)
    }

    fun getSpeedPresets(): List<Pair<Float, String>> = listOf(
        walkSpeedMps to "步行 ${String.format(java.util.Locale.US, "%.1f", walkSpeedMps)}m/s",
        runSpeedMps to "跑步 ${String.format(java.util.Locale.US, "%.1f", runSpeedMps)}m/s",
        bikeSpeedMps to "骑行 ${String.format(java.util.Locale.US, "%.1f", bikeSpeedMps)}m/s",
        driveSpeedMps to "驾车 ${String.format(java.util.Locale.US, "%.1f", driveSpeedMps)}m/s"
    )

    fun speedShortLabel(speedMps: Float): String {
        val tiers = listOf(
            walkSpeedMps to "步行",
            runSpeedMps to "跑步",
            bikeSpeedMps to "骑行",
            driveSpeedMps to "驾车"
        )
        val closest = tiers.minByOrNull { kotlin.math.abs(it.first - speedMps) }?.second ?: "跑步"
        return "$closest ${String.format(java.util.Locale.US, "%.1f", speedMps)}m/s"
    }

    fun loopModeLabel(loopMode: Int): String {
        return when (loopMode) {
            LOOP_REPEAT -> "循环"
            LOOP_PING_PONG -> "往返"
            else -> "单次"
        }
    }

    fun nextSpeedPreset(currentSpeed: Float): Float {
        val presets = getSpeedPresets()
        val idx = presets.indexOfFirst { kotlin.math.abs(it.first - currentSpeed) < 0.25f }
        val nextIdx = if (idx < 0) 0 else (idx + 1) % presets.size
        return presets[nextIdx].first
    }

    fun nextLoopMode(currentMode: Int): Int {
        return (currentMode + 1) % 3
    }

    fun getJoystickSpeed(context: Context): Float {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_JOYSTICK_SPEED, runSpeedMps).coerceIn(0.3f, 40.0f)
    }

    fun setJoystickSpeed(context: Context, speedMps: Float) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_JOYSTICK_SPEED, speedMps.coerceIn(0.3f, 40.0f))
            .apply()
    }

    fun getActiveRoutePoints(context: Context): List<RoutePoint> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACTIVE_POINTS, null) ?: return emptyList()
        return parsePointsJson(raw)
    }

    fun setActiveRoutePoints(context: Context, points: List<RoutePoint>) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_POINTS, pointsToJsonArray(points).toString())
            .apply()
    }

    fun getActiveRouteSpeed(context: Context): Float {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_ACTIVE_SPEED, runSpeedMps).coerceIn(0.3f, 40.0f)
    }

    fun setActiveRouteSpeed(context: Context, speedMps: Float) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_ACTIVE_SPEED, speedMps.coerceIn(0.3f, 40.0f))
            .apply()
    }

    fun getActiveLoopMode(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ACTIVE_LOOP_MODE, LOOP_ONCE).coerceIn(0, 2)
    }

    fun setActiveLoopMode(context: Context, loopMode: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACTIVE_LOOP_MODE, loopMode.coerceIn(0, 2))
            .apply()
    }

    fun getSavedRoutes(context: Context): List<SavedRoute> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SAVED_ROUTES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<SavedRoute>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pts = parsePointsJsonArray(o.optJSONArray("points"))
                if (pts.size < 2) continue
                result.add(
                    SavedRoute(
                        name = o.optString("name", "未命名轨迹"),
                        points = pts,
                        speedMps = o.optDouble("speedMps", 4.0).toFloat(),
                        loopMode = o.optInt("loopMode", LOOP_ONCE)
                    )
                )
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveRoute(context: Context, route: SavedRoute) {
        if (route.points.size < 2) return
        val list = getSavedRoutes(context).toMutableList()
        list.removeAll { it.name == route.name }
        list.add(0, route)
        writeSavedRoutes(context, list.take(25))
    }

    fun removeSavedRoute(context: Context, index: Int) {
        val list = getSavedRoutes(context).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            writeSavedRoutes(context, list)
        }
    }

    fun clearSavedRoutes(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SAVED_ROUTES)
            .apply()
    }

    private fun writeSavedRoutes(context: Context, list: List<SavedRoute>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("speedMps", r.speedMps.toDouble())
                put("loopMode", r.loopMode)
                put("points", pointsToJsonArray(r.points))
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED_ROUTES, arr.toString())
            .apply()
    }

    fun parsePointsJson(rawJson: String): List<RoutePoint> {
        return try {
            parsePointsJsonArray(JSONArray(rawJson))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parsePointsJsonArray(arr: JSONArray?): List<RoutePoint> {
        if (arr == null) return emptyList()
        val list = ArrayList<RoutePoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val gcjLat = o.optDouble("gcjLat", Double.NaN)
            val gcjLng = o.optDouble("gcjLng", Double.NaN)
            if (gcjLat.isNaN() || gcjLng.isNaN()) continue
            var wgsLat = o.optDouble("wgsLat", Double.NaN)
            var wgsLng = o.optDouble("wgsLng", Double.NaN)
            if (wgsLat.isNaN() || wgsLng.isNaN()) {
                val wgs = CoordTransform.gcj02ToWgs84(gcjLat, gcjLng)
                wgsLat = wgs.lat
                wgsLng = wgs.lng
            }
            list.add(RoutePoint(gcjLat, gcjLng, wgsLat, wgsLng))
        }
        return list
    }

    fun pointsToJsonArray(points: List<RoutePoint>): JSONArray {
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONObject().apply {
                put("gcjLat", p.gcjLat)
                put("gcjLng", p.gcjLng)
                put("wgsLat", p.wgsLat)
                put("wgsLng", p.wgsLng)
            })
        }
        return arr
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val sinDLat = sin(dLat / 2.0)
        val sinDLng = sin(dLng / 2.0)
        val a = sinDLat * sinDLat + cos(rLat1) * cos(rLat2) * sinDLng * sinDLng
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_M * c
    }

    fun totalDistanceMeters(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until points.lastIndex) {
            sum += distanceMeters(
                points[i].wgsLat, points[i].wgsLng,
                points[i + 1].wgsLat, points[i + 1].wgsLng
            )
        }
        return sum
    }

    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return (((deg % 360.0) + 360.0) % 360.0).toFloat()
    }

    /**
     * 按距离起点的弧长沿折线插值采样坐标与航向
     */
    fun sampleAlongRoute(
        points: List<RoutePoint>,
        distanceFromStart: Double,
        reverse: Boolean = false
    ): RouteSample? {
        if (points.size < 2) return null
        val ordered = if (reverse) points.asReversed() else points
        val total = totalDistanceMeters(ordered)
        if (total <= 0.01) {
            val p = ordered.first()
            return RouteSample(p.wgsLat, p.wgsLng, p.gcjLat, p.gcjLng, 0f, 0.0, 0.0)
        }

        val targetDist = distanceFromStart.coerceIn(0.0, total)
        var acc = 0.0
        for (i in 0 until ordered.lastIndex) {
            val a = ordered[i]
            val b = ordered[i + 1]
            val segLen = distanceMeters(a.wgsLat, a.wgsLng, b.wgsLat, b.wgsLng)
            if (segLen <= 0.001) continue
            if (acc + segLen >= targetDist || i == ordered.lastIndex - 1) {
                val frac = ((targetDist - acc) / segLen).coerceIn(0.0, 1.0)
                val wgsLat = a.wgsLat + (b.wgsLat - a.wgsLat) * frac
                val wgsLng = a.wgsLng + (b.wgsLng - a.wgsLng) * frac
                val gcj = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
                val bearing = bearingDegrees(a.wgsLat, a.wgsLng, b.wgsLat, b.wgsLng)
                return RouteSample(wgsLat, wgsLng, gcj.lat, gcj.lng, bearing, targetDist, total)
            }
            acc += segLen
        }

        val last = ordered.last()
        val prev = ordered[ordered.lastIndex - 1]
        val bearing = bearingDegrees(prev.wgsLat, prev.wgsLng, last.wgsLat, last.wgsLng)
        return RouteSample(last.wgsLat, last.wgsLng, last.gcjLat, last.gcjLng, bearing, total, total)
    }

    /**
     * 基于当前位置与方向向量计算位移后的新坐标（供悬浮摇杆使用）
     * @param dx 东向分量 [-1, 1]（东正西负）
     * @param dy 北向分量 [-1, 1]（北正南负）
     */
    fun stepByVector(
        wgsLat: Double,
        wgsLng: Double,
        dx: Float,
        dy: Float,
        speedMps: Float,
        dtSeconds: Double
    ): Triple<Double, Double, Float> {
        val mag = sqrt((dx * dx + dy * dy).toDouble()).coerceIn(0.0, 1.0)
        if (mag < 0.05) {
            return Triple(wgsLat, wgsLng, 0f)
        }
        val distMeters = speedMps * dtSeconds * mag
        val bearingRad = atan2(dx.toDouble(), dy.toDouble()) // 0 = North, PI/2 = East
        val dNorth = distMeters * cos(bearingRad)
        val dEast = distMeters * sin(bearingRad)

        val dLat = (dNorth / EARTH_RADIUS_M) * (180.0 / PI)
        val cosLat = cos(Math.toRadians(wgsLat)).coerceAtLeast(0.05)
        val dLng = (dEast / (EARTH_RADIUS_M * cosLat)) * (180.0 / PI)

        val newLat = (wgsLat + dLat).coerceIn(-85.0, 85.0)
        var newLng = wgsLng + dLng
        if (newLng > 180.0) newLng -= 360.0
        if (newLng < -180.0) newLng += 360.0

        val bearingDeg = (((Math.toDegrees(bearingRad) % 360.0) + 360.0) % 360.0).toFloat()
        return Triple(newLat, newLng, bearingDeg)
    }

    fun formatDistance(meters: Double): String {
        return if (meters >= 1000.0) {
            String.format(java.util.Locale.US, "%.2fkm", meters / 1000.0)
        } else {
            String.format(java.util.Locale.US, "%.0fm", meters)
        }
    }
}
