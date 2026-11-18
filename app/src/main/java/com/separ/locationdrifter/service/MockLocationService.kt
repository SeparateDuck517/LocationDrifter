package com.separ.locationdrifter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.separ.locationdrifter.MainActivity
import com.separ.locationdrifter.R
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.util.RootUtils
import com.separ.locationdrifter.util.ShizukuHelper
import java.util.Locale
import kotlin.random.Random

/**
 * 持续向系统 LocationManager 注入虚拟位置：
 * - 当 Root + LSPosed system_server Hook 已激活时，由 system_server 原生下发无 Mock 标记的虚拟坐标与 12 颗卫星，
 *   不再注册系统 TestProvider，彻底规避 OEM 框架对 MockLocationProvider 的风控标记；
 * - 当处于免 Root / Shizuku 模式时，自动注册 TestProvider 持续推送虚拟坐标。
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_MOCK"
        const val ACTION_UPDATE = "com.separ.locationdrifter.action.UPDATE_MOCK"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_MOCK"

        private const val CHANNEL_ID = "location_drifter_channel"
        private const val NOTIFICATION_ID = 10086
        private const val UPDATE_INTERVAL_MS = 250L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isMockingEffective: Boolean = false
            private set
    }

    private lateinit var locationManager: LocationManager
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val activeProviders = ArrayList<String>()

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val cfg = ConfigManager.load(this@MockLocationService)
            if (!cfg.enabled || !cfg.enableSystemMock) {
                stopSelf()
                return
            }
            if (RootUtils.isSystemServerHookActive(this@MockLocationService)) {
                if (activeProviders.isNotEmpty()) {
                    clearTestProviders()
                }
                isMockingEffective = true
            } else {
                if (activeProviders.isEmpty() && RootUtils.isMockLocationAllowed(this@MockLocationService)) {
                    setupTestProviders()
                    updateNotification(cfg)
                }
                pushLocationToProviders(cfg)
            }
            workerHandler?.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ShizukuHelper.runShizukuTask {
                    ShizukuHelper.restoreAllAntiDriftStates(this)
                }
                stopMocking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val cfg = ConfigManager.load(this)
                startForegroundSafe(buildNotification(cfg))
                if (!isRunning && cfg.enabled && cfg.enableSystemMock) {
                    startMocking(cfg)
                } else {
                    workerHandler?.post {
                        if (RootUtils.isSystemServerHookActive(this)) {
                            clearTestProviders()
                            isMockingEffective = true
                        } else {
                            if (activeProviders.isEmpty()) {
                                setupTestProviders()
                            }
                            pushLocationToProviders(cfg)
                        }
                        updateNotification(cfg)
                    }
                }
            }
            else -> {
                val cfg = ConfigManager.load(this)
                startForegroundSafe(buildNotification(cfg))
                startMocking(cfg)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val cfg = ConfigManager.load(this)
            if (cfg.enabled) {
                ConfigManager.save(this, cfg.copy(enabled = false), syncTmpWithRoot = false)
            }
        } catch (_: Throwable) {
        }
        ShizukuHelper.runShizukuTask {
            ShizukuHelper.restoreAllAntiDriftStates(this)
        }
        stopMocking()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startMocking(cfg: ConfigManager.DriftConfig) {
        if (isRunning) {
            updateNotification(cfg)
            return
        }
        isRunning = true

        if (workerThread == null) {
            workerThread = HandlerThread("MockLocationWorker").apply { start() }
            workerHandler = Handler(workerThread!!.looper)
        }

        workerHandler?.post {
            if (RootUtils.isSystemServerHookActive(this)) {
                // system_server Hook 已接管，不注册 TestProvider 避免留下 Mock 痕迹
                clearTestProviders()
                isMockingEffective = true
                updateNotification(cfg)
            } else {
                if (!RootUtils.isMockLocationAllowed(this)) {
                    if (RootUtils.isRootAvailable()) {
                        RootUtils.grantMockAndLocationPermissions(packageName)
                    } else if (ShizukuHelper.hasPermission()) {
                        ShizukuHelper.grantMockAndLocationPermissions(this)
                    }
                }
                setupTestProviders()
                updateNotification(cfg)
                pushLocationToProviders(cfg)
            }
            workerHandler?.removeCallbacks(pushRunnable)
            workerHandler?.postDelayed(pushRunnable, UPDATE_INTERVAL_MS)
        }
    }

    private fun setupTestProviders() {
        activeProviders.clear()
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused"
        )
        for (provider in candidates) {
            try {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Throwable) {
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val props = ProviderProperties.Builder()
                        .setHasNetworkRequirement(false)
                        .setHasSatelliteRequirement(false)
                        .setHasCellRequirement(false)
                        .setHasMonetaryCost(false)
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build()
                    locationManager.addTestProvider(provider, props)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        true,
                        Criteria.POWER_LOW,
                        Criteria.ACCURACY_FINE
                    )
                }
                locationManager.setTestProviderEnabled(provider, true)
                activeProviders.add(provider)
            } catch (_: Throwable) {
            }
        }
        isMockingEffective = activeProviders.isNotEmpty()
    }

    private fun clearTestProviders() {
        for (provider in activeProviders) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Throwable) {
            }
        }
        activeProviders.clear()
    }

    private fun pushLocationToProviders(cfg: ConfigManager.DriftConfig) {
        val jitterLat = if (cfg.enableJitter) Random.nextDouble(-0.000003, 0.000003) else 0.0
        val jitterLng = if (cfg.enableJitter) Random.nextDouble(-0.000003, 0.000003) else 0.0
        val lat = cfg.wgsLat + jitterLat
        val lng = cfg.wgsLng + jitterLng
        val jitterSpeed = if (cfg.enableJitter) Random.nextDouble(0.03, 0.16).toFloat() else 0.0f
        val jitterBearing = if (cfg.enableJitter) Random.nextDouble(0.0, 359.9).toFloat() else 0.0f

        for (provider in activeProviders) {
            try {
                val loc = Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 36.0
                    accuracy = if (provider == LocationManager.GPS_PROVIDER) 2.8f else 12.0f
                    speed = jitterSpeed
                    bearing = jitterBearing
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bearingAccuracyDegrees = 1.2f
                        speedAccuracyMetersPerSecond = 0.15f
                        verticalAccuracyMeters = 1.8f
                    }
                    extras = Bundle().apply {
                        putInt("satellites", 14)
                        putInt("maxCn0", 44)
                        putInt("meanCn0", 36)
                        putFloat("hdop", 0.8f)
                        putFloat("vdop", 1.1f)
                        putFloat("pdop", 1.3f)
                    }
                }
                locationManager.setTestProviderLocation(provider, loc)
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopMocking() {
        isRunning = false
        isMockingEffective = false
        workerHandler?.removeCallbacksAndMessages(null)
        clearTestProviders()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "虚拟定位运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示当前虚拟定位与防网络漂移运行状态"
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(cfg: ConfigManager.DriftConfig) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(cfg))
    }

    private fun buildNotification(cfg: ConfigManager.DriftConfig): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sysHookActive = RootUtils.isSystemServerHookActive(this)
        val missingAuth = isRunning && !sysHookActive && !isMockingEffective && !RootUtils.isMockLocationAllowed(this)
        val title = if (missingAuth) {
            "定位漂移助手（等待模拟位置授权）"
        } else {
            "定位漂移助手运行中"
        }
        val content = if (missingAuth) {
            "请在系统「开发者选项 → 选择模拟位置信息应用」中选中本应用，或使用 Shizuku 自动授权"
        } else {
            String.format(
                Locale.US,
                "目标: %s (%.5f, %.5f)",
                cfg.address.ifBlank { "已选点" },
                cfg.gcjLat,
                cfg.gcjLng
            )
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher),
                    "停止",
                    stopPending
                ).build()
            )
            .build()
    }
}
