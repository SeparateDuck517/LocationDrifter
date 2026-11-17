package com.separ.locationdrifter.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 常用中国坐标系转换工具：WGS-84 / GCJ-02 / BD-09
 */
object CoordTransform {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    data class LatLng(val lat: Double, val lng: Double)

    fun isOutOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    fun wgs84ToGcj02(lat: Double, lng: Double): LatLng {
        if (isOutOfChina(lat, lng)) return LatLng(lat, lng)
        val (dLat, dLng) = delta(lat, lng)
        return LatLng(lat + dLat, lng + dLng)
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): LatLng {
        if (isOutOfChina(lat, lng)) return LatLng(lat, lng)
        var wgsLat = lat
        var wgsLng = lng
        // 迭代逼近，精度优于 1e-7 度（约 1 厘米）
        repeat(6) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLng)
            val dLat = gcj.lat - lat
            val dLng = gcj.lng - lng
            wgsLat -= dLat
            wgsLng -= dLng
            if (abs(dLat) < 1e-8 && abs(dLng) < 1e-8) {
                return LatLng(wgsLat, wgsLng)
            }
        }
        return LatLng(wgsLat, wgsLng)
    }

    fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): LatLng {
        val z = sqrt(gcjLng * gcjLng + gcjLat * gcjLat) + 0.00002 * sin(gcjLat * X_PI)
        val theta = atan2(gcjLat, gcjLng) + 0.000003 * cos(gcjLng * X_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return LatLng(bdLat, bdLng)
    }

    fun wgs84ToBd09(wgsLat: Double, wgsLng: Double): LatLng {
        val gcj = wgs84ToGcj02(wgsLat, wgsLng)
        return gcj02ToBd09(gcj.lat, gcj.lng)
    }

    fun bd09ToGcj02(bdLat: Double, bdLng: Double): LatLng {
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLng = z * cos(theta)
        val gcjLat = z * sin(theta)
        return LatLng(gcjLat, gcjLng)
    }

    fun bd09ToWgs84(bdLat: Double, bdLng: Double): LatLng {
        val gcj = bd09ToGcj02(bdLat, bdLng)
        return gcj02ToWgs84(gcj.lat, gcj.lng)
    }

    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
