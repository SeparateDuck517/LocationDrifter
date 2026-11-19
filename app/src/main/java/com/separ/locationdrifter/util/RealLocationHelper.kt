package com.separ.locationdrifter.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import com.separ.locationdrifter.R
import com.separ.locationdrifter.data.ConfigManager
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 获取设备当前真实位置并同步至地图选点（方便在附近快速选点）：
 * - 优先读取缓存中最新且非模拟的 GPS/Fused/Network 定位实现秒级落点；
 * - 若无可用缓存或缓存较旧，自动发起单次实时定位请求并在 8 秒后超时释放。
 */
object RealLocationHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val FRESH_AGE_MS = 2 * 60 * 1000L
    private const val REQUEST_TIMEOUT_MS = 8000L

    fun hasLocationPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun locateCurrentPosition(
        activity: Activity,
        silent: Boolean,
        onLocated: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!hasLocationPermission(activity)) {
            if (!silent) {
                Toast.makeText(activity, "请先授予位置权限以定位当前位置", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val bestCached = getBestLastKnownRealLocation(lm)
        if (bestCached != null) {
            applyRealLocationToMap(activity, bestCached, silent)
            onLocated?.invoke()
            val ageMs = System.currentTimeMillis() - bestCached.time
            if (ageMs in 0..FRESH_AGE_MS) {
                return
            }
        }

        // 缓存缺失或较旧时，向系统发起一次轻量实时定位
        requestSingleRealFix(activity, lm) { freshLoc ->
            if (activity.isFinishing || activity.isDestroyed) return@requestSingleRealFix
            if (freshLoc != null) {
                applyRealLocationToMap(activity, freshLoc, silent)
                onLocated?.invoke()
            } else if (bestCached == null && !silent) {
                val msg = if (ConfigManager.load(activity).enabled) {
                    "当前正在漂移中，请先停止漂移后再获取真实位置"
                } else {
                    "暂未获取到当前位置，请确认已开启系统定位服务"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownRealLocation(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused",
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (!isValidRealLocation(loc)) continue
                if (best == null || loc.time > best.time) {
                    best = loc
                }
            } catch (_: Throwable) {
            }
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun isValidRealLocation(loc: Location): Boolean {
        if (loc.latitude.isNaN() || loc.longitude.isNaN()) return false
        if (kotlin.math.abs(loc.latitude) < 0.01 && kotlin.math.abs(loc.longitude) < 0.01) return false
        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            loc.isFromMockProvider
        }
        return !isMock
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleRealFix(
        activity: Activity,
        lm: LocationManager,
        callback: (Location?) -> Unit
    ) {
        val provider = pickAvailableProvider(lm)
        if (provider == null) {
            callback(null)
            return
        }

        val done = AtomicBoolean(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancelSignal = CancellationSignal()
            val timeoutTask = Runnable {
                if (done.compareAndSet(false, true)) {
                    try {
                        cancelSignal.cancel()
                    } catch (_: Throwable) {
                    }
                    callback(null)
                }
            }
            mainHandler.postDelayed(timeoutTask, REQUEST_TIMEOUT_MS)
            try {
                lm.getCurrentLocation(provider, cancelSignal, activity.mainExecutor) { loc ->
                    if (done.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeoutTask)
                        callback(if (loc != null && isValidRealLocation(loc)) loc else null)
                    }
                }
            } catch (_: Throwable) {
                if (done.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutTask)
                    callback(null)
                }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (done.compareAndSet(false, true)) {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Throwable) {
                        }
                        callback(if (isValidRealLocation(location)) location else null)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            mainHandler.postDelayed({
                if (done.compareAndSet(false, true)) {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Throwable) {
                    }
                    callback(null)
                }
            }, REQUEST_TIMEOUT_MS)
            try {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: Throwable) {
                if (done.compareAndSet(false, true)) {
                    callback(null)
                }
            }
        }
    }

    private fun pickAvailableProvider(lm: LocationManager): String? {
        val candidates = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            "fused"
        )
        for (p in candidates) {
            try {
                if (lm.allProviders.contains(p) && lm.isProviderEnabled(p)) {
                    return p
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun applyRealLocationToMap(activity: Activity, loc: Location, silent: Boolean) {
        val gcj = CoordTransform.wgs84ToGcj02(loc.latitude, loc.longitude)
        dispatchPointToWebMap(activity, gcj.lat, gcj.lng, "当前位置", retries = 8)
        if (!silent) {
            Toast.makeText(activity, "已定位到当前位置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchPointToWebMap(
        activity: Activity,
        gcjLat: Double,
        gcjLng: Double,
        label: String,
        retries: Int
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val webMap = activity.findViewById<WebView>(R.id.webMap) ?: return
        val js = String.format(
            Locale.US,
            "(function(){ if (window.setPoint) { window.setPoint(%.8f, %.8f, '%s'); return 'ok'; } return 'wait'; })()",
            gcjLat,
            gcjLng,
            label
        )
        webMap.evaluateJavascript(js) { res ->
            if (res != "\"ok\"" && retries > 0 && !activity.isFinishing && !activity.isDestroyed) {
                webMap.postDelayed({
                    dispatchPointToWebMap(activity, gcjLat, gcjLng, label, retries - 1)
                }, 350L)
            }
        }
    }
}
