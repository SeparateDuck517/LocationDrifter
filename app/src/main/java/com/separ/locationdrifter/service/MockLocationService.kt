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
import android.os.Looper
import android.os.SystemClock
import com.separ.locationdrifter.MainActivity
import com.separ.locationdrifter.R
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.data.RouteManager
import com.separ.locationdrifter.util.RootUtils
import com.separ.locationdrifter.util.ShizukuHelper
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.random.Random

/**
 * 持续向系统 LocationManager 注入虚拟位置（支持 Root、Shizuku 与纯免 Root 开发者选项模式）：
 * - 免 Root / Shizuku 模式下同时向 gps / network / fused / passive 四个 Provider 注入一致的高精度坐标，
 *   并在切换选点后立即同步刷新全部 Provider 的 lastKnownLocation 缓存；
 * - 内置后台轨迹巡航引擎，支持沿提前绘制的轨迹按设定速度与单次/循环/往返模式平滑移动；
 * - 支持与全局悬浮摇杆共享实时速度与航向角，并向 UI 广播实时坐标与进度。
 */
class MockLocationService : Service() {

    interface PositionListener {
        fun onLivePositionUpdated(
            gcjLat: Double,
            gcjLng: Double,
            wgsLat: Double,
            wgsLng: Double,
            address: String,
            bearingDeg: Float,
            speedMps: Float
        )
    }

    interface RouteStateListener {
        fun onRouteStateChanged(
            isPlaying: Boolean,
            traveledMeters: Double,
            totalMeters: Double,
            finished: Boolean
        )
    }

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_MOCK"
        const val ACTION_UPDATE = "com.separ.locationdrifter.action.UPDATE_MOCK"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_MOCK"
        const val ACTION_START_ROUTE = "com.separ.locationdrifter.action.START_ROUTE"
        const val ACTION_PAUSE_ROUTE = "com.separ.locationdrifter.action.PAUSE_ROUTE"
        const val ACTION_STOP_ROUTE = "com.separ.locationdrifter.action.STOP_ROUTE"

        private const val CHANNEL_ID = "location_drifter_channel"
        private const val NOTIFICATION_ID = 10086
        private const val UPDATE_INTERVAL_MS = 200L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isMockingEffective: Boolean = false
            private set

        @Volatile
        var isRoutePlaying: Boolean = false
            private set

        @Volatile
        var routeTraveledMeters: Double = 0.0
            private set

        @Volatile
        var routeTotalMeters: Double = 0.0
            private set

        @Volatile
        private var liveMotionActive: Boolean = false

        @Volatile
        private var liveSpeedMps: Float = 0f

        @Volatile
        private var liveBearingDeg: Float = 0f

        @Volatile
        private var routeReverse: Boolean = false

        @Volatile
        private var activeRouteCache: List<RouteManager.RoutePoint> = emptyList()

        private val mainHandler = Handler(Looper.getMainLooper())
        private val positionListeners = CopyOnWriteArraySet<PositionListener>()
        private val routeStateListeners = CopyOnWriteArraySet<RouteStateListener>()

        fun addPositionListener(listener: PositionListener) {
            positionListeners.add(listener)
        }

        fun removePositionListener(listener: PositionListener) {
            positionListeners.remove(listener)
        }

        fun addRouteStateListener(listener: RouteStateListener) {
            routeStateListeners.add(listener)
        }

        fun removeRouteStateListener(listener: RouteStateListener) {
            routeStateListeners.remove(listener)
        }

        fun setLiveMotion(speedMps: Float, bearingDeg: Float, active: Boolean) {
            liveSpeedMps = speedMps
            liveBearingDeg = bearingDeg
            liveMotionActive = active
        }

        fun pauseRoutePlayback() {
            if (!isRoutePlaying) return
            isRoutePlaying = false
            liveMotionActive = false
            notifyRouteStateListeners(false, routeTraveledMeters, routeTotalMeters, false)
        }

        fun resetRoutePlaybackState() {
            isRoutePlaying = false
            liveMotionActive = false
            routeTraveledMeters = 0.0
            routeReverse = false
            activeRouteCache = emptyList()
            notifyRouteStateListeners(false, 0.0, routeTotalMeters, false)
        }

        fun notifyPositionListeners(
            gcjLat: Double,
            gcjLng: Double,
            wgsLat: Double,
            wgsLng: Double,
            address: String,
            bearingDeg: Float,
            speedMps: Float
        ) {
            if (positionListeners.isEmpty()) return
            mainHandler.post {
                for (l in positionListeners) {
                    try {
                        l.onLivePositionUpdated(gcjLat, gcjLng, wgsLat, wgsLng, address, bearingDeg, speedMps)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        fun notifyRouteStateListeners(
            isPlaying: Boolean,
            traveledMeters: Double,
            totalMeters: Double,
            finished: Boolean
        ) {
            if (routeStateListeners.isEmpty()) return
            mainHandler.post {
                for (l in routeStateListeners) {
                    try {
                        l.onRouteStateChanged(isPlaying, traveledMeters, totalMeters, finished)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private lateinit var locationManager: LocationManager
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val activeProviders = ArrayList<String>()
    private var lastRouteTickMs = 0L
    private var lastNotificationUpdateMs = 0L

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            var cfg = ConfigManager.load(this@MockLocationService)
            if (!cfg.enabled || !cfg.enableSystemMock) {
                stopSelf()
                return
            }

            if (isRoutePlaying) {
                cfg = advanceRoutePlayback(cfg)
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

            val now = SystemClock.elapsedRealtime()
            if (isRoutePlaying && now - lastNotificationUpdateMs >= 2000L) {
                lastNotificationUpdateMs = now
                updateNotification(cfg)
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
                resetRoutePlaybackState()
                ShizukuHelper.runShizukuTask {
                    ShizukuHelper.restoreAllAntiDriftStates(this)
                }
                stopMocking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ROUTE -> {
                pauseRoutePlayback()
                val cfg = ConfigManager.load(this)
                updateNotification(cfg)
            }
            ACTION_STOP_ROUTE -> {
                resetRoutePlaybackState()
                val cfg = ConfigManager.load(this)
                updateNotification(cfg)
            }
            ACTION_START_ROUTE -> {
                val cfg = ConfigManager.load(this)
                startForegroundSafe(buildNotification(cfg))
                if (!isRunning && cfg.enabled && cfg.enableSystemMock) {
                    startMocking(cfg)
                }
                workerHandler?.post {
                    beginRoutePlayback()
                }
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
                            repeat(3) {
                                pushLocationToProviders(cfg)
                            }
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
        resetRoutePlaybackState()
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

    private fun beginRoutePlayback() {
        val points = RouteManager.getActiveRoutePoints(this)
        if (points.size < 2) {
            resetRoutePlaybackState()
            return
        }
        val total = RouteManager.totalDistanceMeters(points)
        if (activeRouteCache != points || routeTraveledMeters >= total) {
            activeRouteCache = points
            routeTraveledMeters = 0.0
            routeReverse = false
        }
        routeTotalMeters = total
        lastRouteTickMs = SystemClock.elapsedRealtime()
        isRoutePlaying = true
        notifyRouteStateListeners(true, routeTraveledMeters, routeTotalMeters, false)

        val firstSample = RouteManager.sampleAlongRoute(points, routeTraveledMeters, routeReverse)
        if (firstSample != null) {
            val speed = RouteManager.getActiveRouteSpeed(this)
            val updated = ConfigManager.updateLiveCoordinates(
                this,
                wgsLat = firstSample.wgsLat,
                wgsLng = firstSample.wgsLng,
                gcjLat = firstSample.gcjLat,
                gcjLng = firstSample.gcjLng,
                address = "轨迹模拟中"
            )
            setLiveMotion(speed, firstSample.bearingDeg, true)
            notifyPositionListeners(
                firstSample.gcjLat,
                firstSample.gcjLng,
                firstSample.wgsLat,
                firstSample.wgsLng,
                "轨迹模拟中",
                firstSample.bearingDeg,
                speed
            )
            updateNotification(updated)
        }
    }

    private fun advanceRoutePlayback(currentCfg: ConfigManager.DriftConfig): ConfigManager.DriftConfig {
        val points = activeRouteCache
        if (points.size < 2) {
            resetRoutePlaybackState()
            return currentCfg
        }
        val now = SystemClock.elapsedRealtime()
        val dtSec = ((now - lastRouteTickMs).coerceIn(50L, 1500L)) / 1000.0
        lastRouteTickMs = now

        val speed = RouteManager.getActiveRouteSpeed(this)
        val loopMode = RouteManager.getActiveLoopMode(this)
        val total = RouteManager.totalDistanceMeters(points).coerceAtLeast(0.01)
        routeTotalMeters = total

        var nextDist = routeTraveledMeters + speed * dtSec
        var finished = false

        if (nextDist >= total) {
            when (loopMode) {
                RouteManager.LOOP_REPEAT -> {
                    nextDist %= total
                    routeReverse = false
                }
                RouteManager.LOOP_PING_PONG -> {
                    nextDist = (nextDist - total).coerceIn(0.0, total)
                    routeReverse = !routeReverse
                }
                else -> {
                    nextDist = total
                    finished = true
                }
            }
        }

        routeTraveledMeters = nextDist
        val sample = RouteManager.sampleAlongRoute(points, nextDist, routeReverse) ?: return currentCfg

        val label = if (finished) "已到达轨迹终点" else "轨迹模拟中"
        val updated = ConfigManager.updateLiveCoordinates(
            this,
            wgsLat = sample.wgsLat,
            wgsLng = sample.wgsLng,
            gcjLat = sample.gcjLat,
            gcjLng = sample.gcjLng,
            address = label
        )

        if (finished) {
            isRoutePlaying = false
            setLiveMotion(0f, sample.bearingDeg, false)
            updateNotification(updated)
        } else {
            setLiveMotion(speed, sample.bearingDeg, true)
        }

        notifyPositionListeners(
            sample.gcjLat,
            sample.gcjLng,
            sample.wgsLat,
            sample.wgsLng,
            label,
            sample.bearingDeg,
            if (finished) 0f else speed
        )
        notifyRouteStateListeners(!finished, routeTraveledMeters, total, finished)
        return updated
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
                repeat(3) {
                    pushLocationToProviders(cfg)
                }
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
            "fused",
            LocationManager.PASSIVE_PROVIDER
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
        val moving = liveMotionActive
        val jitterRange = if (moving) 0.0000008 else 0.000003
        val jitterLat = if (cfg.enableJitter) Random.nextDouble(-jitterRange, jitterRange) else 0.0
        val jitterLng = if (cfg.enableJitter) Random.nextDouble(-jitterRange, jitterRange) else 0.0
        val lat = cfg.wgsLat + jitterLat
        val lng = cfg.wgsLng + jitterLng
        val injectedSpeed = when {
            moving -> liveSpeedMps
            cfg.enableJitter -> Random.nextDouble(0.03, 0.16).toFloat()
            else -> 0.0f
        }
        val injectedBearing = when {
            moving -> liveBearingDeg
            cfg.enableJitter -> Random.nextDouble(0.0, 359.9).toFloat()
            else -> 0.0f
        }

        for (provider in activeProviders) {
            try {
                val loc = Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 36.0
                    accuracy = when (provider) {
                        LocationManager.GPS_PROVIDER -> 2.5f
                        "fused" -> 3.0f
                        else -> 5.0f
                    }
                    speed = injectedSpeed
                    bearing = injectedBearing
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
        isRoutePlaying = false
        liveMotionActive = false
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
        val title = when {
            missingAuth -> "定位漂移助手（等待模拟位置授权）"
            isRoutePlaying -> "定位漂移助手（轨迹模拟中）"
            else -> "定位漂移助手运行中"
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
