package com.separ.locationdrifter.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 常用中国坐标系转换工具：WGS-84 / GCJ-02 / BD-09 / BD-09MC (百度墨卡托)
 */
object CoordTransform {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    private val MC_BAND = doubleArrayOf(
        12890594.86, 8362377.87, 5591021.0, 3481989.83, 1678043.12, 0.0
    )

    private val MC_TO_LL = arrayOf(
        doubleArrayOf(1.410526172116255e-8, 0.00000898305509648872, -1.9939833816331, 200.9824383106796, -187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198, -0.03801003308653, 17337981.2),
        doubleArrayOf(-7.435856389565537e-9, 0.000008983055097726239, -0.78625201886289, 96.32687599759846, -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887, 2.28786674699375, 10260144.86),
        doubleArrayOf(-3.030883460898826e-8, 0.00000898305509983578, 0.30071316287616, 59.74293618442277, 7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584, 0.32710905363475, 6856817.37),
        doubleArrayOf(-1.981981304930552e-8, 0.000008983055099779535, 0.03278182852591, 40.31678527705744, 0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204, -0.04625736007561, 4482777.06),
        doubleArrayOf(3.09191371068437e-9, 0.000008983055096812155, 0.00006995724062, 23.10934304144901, -0.00023663490511, -0.6321817810242, -0.00663494467273, 0.03430082397953, -0.00466043876332, 2555164.4),
        doubleArrayOf(2.890871144776878e-9, 0.000008983055095805407, -3.068298e-8, 7.47137025468032, -0.00000353937994, -0.02145144861037, -0.00001234426596, 0.00010322952773, -0.00000323890364, 826088.5)
    )

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

    /**
     * 百度平面墨卡托坐标 (BD-09MC，自动兼容厘米与米单位) 转 BD-09 经纬度
     */
    fun bd09McToBd09(rawMcX: Double, rawMcY: Double): LatLng {
        val mcX = if (abs(rawMcX) > 25_000_000.0) rawMcX / 100.0 else rawMcX
        val mcY = if (abs(rawMcY) > 25_000_000.0) rawMcY / 100.0 else rawMcY
        val x = abs(mcX)
        val y = abs(mcY)

        var coeff = MC_TO_LL[MC_TO_LL.lastIndex]
        for (i in MC_BAND.indices) {
            if (y >= MC_BAND[i]) {
                coeff = MC_TO_LL[i]
                break
            }
        }

        val lngAbs = coeff[0] + coeff[1] * x
        val t = y / coeff[9]
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        val t5 = t4 * t
        val t6 = t5 * t
        val latAbs = coeff[2] + coeff[3] * t + coeff[4] * t2 +
            coeff[5] * t3 + coeff[6] * t4 + coeff[7] * t5 + coeff[8] * t6

        val lng = if (mcX < 0) -lngAbs else lngAbs
        val lat = if (mcY < 0) -latAbs else latAbs
        return LatLng(lat, lng)
    }

    fun bd09McToGcj02(rawMcX: Double, rawMcY: Double): LatLng {
        val bd = bd09McToBd09(rawMcX, rawMcY)
        return bd09ToGcj02(bd.lat, bd.lng)
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
