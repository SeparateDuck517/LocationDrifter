package com.separ.locationdrifter.xposed

import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellIdentity
import android.telephony.CellInfo
import com.separ.locationdrifter.data.ConfigManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * LSPosed / Xposed 入口（严格遵守「零三方应用注入」架构）：
 * 作用域仅限系统框架 (android) 与电话服务 (com.android.phone)，绝不向任何第三方业务应用注入 Hook 代码。
 * - 定位与 GNSS 卫星注入：拦截 system_server 位置服务并抹除 Mock 标记；
 * - 基站 / Wi-Fi 源头隔离：拦截 WifiServiceImpl、ParceledListSlice、WifiInfo 与 NetworkRegistrationInfo；
 * - 实验性步数模拟（默认关闭）：仅在用户主动开启时，在 system_server 进程内懒加载 Native Hook 拦截 libsensorservice.so，
 *   并内置启动崩溃熔断保护。
 */
class LocationHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[LocationDrifter]"
        private const val MODULE_PACKAGE = "com.separ.locationdrifter"
        private const val PROBE_PROVIDER = "location_drifter_probe"
        private const val CACHE_TTL_MS = 600L
        private const val JITTER_STEP_MS = 1000L
        private const val FEED_INTERVAL_MS = 900L
        private const val FIRST_APPLICATION_UID = 10000
        private const val HAS_MOCK_PROVIDER_MASK = 1 shl 4

        private val KNOWN_PROVIDERS = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused"
        )

        private val SYSTEM_UI_BYPASS_PACKAGES = setOf(
            "com.separ.locationdrifter",
            "com.android.systemui",
            "com.android.settings",
            "com.coloros.wirelesssettings",
            "com.oplus.wirelesssettings"
        )
    }

    private data class RuntimeConfig(
        val enabled: Boolean = false,
        val wgsLat: Double = 39.907418,
        val wgsLng: Double = 116.391227,
        val blockNetworkDrift: Boolean = true,
        val enableJitter: Boolean = true,
        val enableStepSim: Boolean = false,
        val simStepCount: Long = 0L
    )

    private data class TrackedListener(
        val listener: Any,
        val provider: String
    )

    private data class TrackedPendingIntent(
        val pendingIntent: PendingIntent,
        val provider: String
    )

    @Volatile
    private var cachedConfig = RuntimeConfig()

    @Volatile
    private var lastLoadTimeMs = 0L

    @Volatile
    private var lastJitterTimeMs = 0L

    @Volatile
    private var cachedLatJitter = 0.0

    @Volatile
    private var cachedLngJitter = 0.0

    @Volatile
    private var xPrefs: XSharedPreferences? = null

    @Volatile
    private var cachedContext: Context? = null

    private val wifiApexHooked = AtomicBoolean(false)
    private val feederStarted = AtomicBoolean(false)
    private val nativeStepInitAttempted = AtomicBoolean(false)
    private val activeListeners = ConcurrentHashMap<IBinder, TrackedListener>()
    private val activePendingIntents = ConcurrentHashMap<PendingIntent, TrackedPendingIntent>()
    private val uidBypassCache = ConcurrentHashMap<Int, Boolean>()
    private val uidModuleSelfCache = ConcurrentHashMap<Int, Boolean>()

    private val locFieldsMaskField: Field? by lazy {
        XposedHelpers.findFieldIfExists(Location::class.java, "mFieldsMask")
    }
    private val locIsFromMockField: Field? by lazy {
        XposedHelpers.findFieldIfExists(Location::class.java, "mIsFromMockProvider")
    }
    private val wifiBssidField: Field? by lazy {
        XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mBSSID")
    }
    private val wifiMacField: Field? by lazy {
        XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mMacAddress")
    }
    private val wifiApMldMacField: Field? by lazy {
        XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mApMldMacAddress")
    }
    private val wifiSsidField: Field? by lazy {
        XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mWifiSsid")
    }
    private val wifiNetworkIdField: Field? by lazy {
        XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mNetworkId")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.separ.locationdrifter.xposed.ModuleStatus",
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG self-hook failed: ${t.message}")
            }
            return
        }

        when (lpparam.packageName) {
            "android" -> {
                hookLocationClass()
                hookLocationResult(lpparam.classLoader)
                hookSystemLocationServices(lpparam.classLoader)
                hookGnssManagerService(lpparam.classLoader)
                hookWifiInfoSerialization()
                hookParceledListSlice(ClassLoader.getSystemClassLoader())
                hookWifiServiceInSystemServer(lpparam.classLoader)
                hookWifiMainlineApexLoader(lpparam.classLoader)
                hookTelephonyRegistryInSystemServer(lpparam.classLoader)
                hookNetworkRegistrationInfo(lpparam.classLoader)
                startSystemGpsFeederLoop()
                markSystemServerHookedAsync()
            }
            "com.android.phone" -> {
                hookParceledListSlice(ClassLoader.getSystemClassLoader())
                hookPhoneInterfaceManager(lpparam.classLoader)
                hookNetworkRegistrationInfo(lpparam.classLoader)
            }
            else -> {
                return
            }
        }
    }

    private fun resolveProviderName(param: XC_MethodHook.MethodHookParam): String {
        try {
            val target = param.thisObject
            if (target != null && target.javaClass.name.contains("LocationProviderManager")) {
                val mName = XposedHelpers.getObjectField(target, "mName") as? String
                if (!mName.isNullOrBlank() && mName in KNOWN_PROVIDERS) {
                    return mName
                }
            }
        } catch (_: Throwable) {
        }

        for (arg in param.args) {
            if (arg != null && arg.javaClass.name == "android.location.LocationRequest") {
                try {
                    val reqProv = XposedHelpers.callMethod(arg, "getProvider") as? String
                    if (!reqProv.isNullOrBlank() && reqProv in KNOWN_PROVIDERS) {
                        return reqProv
                    }
                } catch (_: Throwable) {
                }
            }
        }

        for (arg in param.args) {
            if (arg is String && arg in KNOWN_PROVIDERS) {
                return arg
            }
        }

        return LocationManager.GPS_PROVIDER
    }

    private fun hookWifiMainlineApexLoader(systemClassLoader: ClassLoader) {
        try {
            val ssmCls = XposedHelpers.findClassIfExists(
                "com.android.server.SystemServiceManager",
                systemClassLoader
            )
            if (ssmCls != null) {
                XposedBridge.hookAllMethods(
                    ssmCls,
                    "startService",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val service = param.result ?: return
                            val name = service.javaClass.name
                            if (name.contains("wifi", ignoreCase = true)) {
                                service.javaClass.classLoader?.let { cl ->
                                    installWifiApexHooksOnce(cl)
                                }
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookSystemServiceManager error: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (wifiApexHooked.get()) return
                        val name = param.args.firstOrNull() as? String ?: return
                        if (name == "com.android.server.wifi.WifiServiceImpl") {
                            val cls = param.result as? Class<*> ?: return
                            cls.classLoader?.let { cl ->
                                installWifiApexHooksOnce(cl)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookClassLoader(WifiServiceImpl) error: ${t.message}")
        }
    }

    private fun installWifiApexHooksOnce(wifiClassLoader: ClassLoader) {
        if (!wifiApexHooked.compareAndSet(false, true)) return
        XposedBridge.log("$TAG hooked Wi-Fi APEX ClassLoader: $wifiClassLoader")
        hookParceledListSlice(wifiClassLoader)
        hookWifiServiceInSystemServer(wifiClassLoader)
    }

    private fun ensureNativeStepHookOnce(ctx: Context) {
        if (!nativeStepInitAttempted.compareAndSet(false, true)) return
        Thread {
            val cr = ctx.contentResolver
            val currentBoot = try {
                Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0)
            } catch (_: Throwable) {
                0
            }
            val prevGuard = try {
                Settings.Global.getString(cr, ConfigManager.SETTINGS_GLOBAL_STEP_GUARD_KEY)
            } catch (_: Throwable) {
                null
            }
            if (prevGuard != null && prevGuard.startsWith("loading:") && prevGuard != "loading:$currentBoot") {
                XposedBridge.log("$TAG step native hook circuit-breaker tripped (prevGuard=$prevGuard)")
                return@Thread
            }
            try {
                Settings.Global.putString(cr, ConfigManager.SETTINGS_GLOBAL_STEP_GUARD_KEY, "loading:$currentBoot")
            } catch (_: Throwable) {
            }

            try {
                val appInfo = ctx.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
                val nativeLibDir = appInfo.nativeLibraryDir
                val ret = NativeStepBridge.ensureLoadedAndHooked(nativeLibDir)
                val status = NativeStepBridge.getStatusSafe()
                val mark = if (ret == 0) "ready:$currentBoot:$status" else "failed:$ret:$status"
                try {
                    Settings.Global.putString(cr, ConfigManager.SETTINGS_GLOBAL_STEP_GUARD_KEY, mark)
                } catch (_: Throwable) {
                }
                XposedBridge.log("$TAG native step hook result=$ret status=$status")
            } catch (t: Throwable) {
                try {
                    Settings.Global.putString(
                        cr,
                        ConfigManager.SETTINGS_GLOBAL_STEP_GUARD_KEY,
                        "error:${t.javaClass.simpleName}"
                    )
                } catch (_: Throwable) {
                }
                XposedBridge.log("$TAG native step hook exception: ${t.message}")
            }
        }.apply {
            name = "LocationDrifter-StepNativeLoader"
            isDaemon = true
            start()
        }
    }

    private fun startSystemGpsFeederLoop() {
        if (!feederStarted.compareAndSet(false, true)) return
        Thread {
            while (true) {
                try {
                    Thread.sleep(FEED_INTERVAL_MS)
                    val cfg = getConfig()

                    if (cfg.enabled && cfg.enableStepSim) {
                        resolveContext()?.let { ensureNativeStepHookOnce(it) }
                        NativeStepBridge.updateStateSafe(true, cfg.simStepCount)
                    } else {
                        NativeStepBridge.updateStateSafe(false, 0L)
                    }

                    if (!cfg.enabled) continue

                    if (activeListeners.isNotEmpty()) {
                        val iterator = activeListeners.entries.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            val binder = entry.key
                            if (!binder.isBinderAlive) {
                                iterator.remove()
                                continue
                            }
                            val tracked = entry.value
                            val fakeLoc = buildFakeLocation(tracked.provider, cfg)
                            val token = Binder.clearCallingIdentity()
                            try {
                                try {
                                    XposedHelpers.callMethod(
                                        tracked.listener,
                                        "onLocationChanged",
                                        Collections.singletonList(fakeLoc),
                                        null
                                    )
                                } catch (_: Throwable) {
                                    XposedHelpers.callMethod(tracked.listener, "onLocationChanged", fakeLoc)
                                }
                            } catch (_: Throwable) {
                                iterator.remove()
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        }
                    }

                    if (activePendingIntents.isNotEmpty()) {
                        val ctx = resolveContext()
                        if (ctx != null) {
                            val piIter = activePendingIntents.entries.iterator()
                            while (piIter.hasNext()) {
                                val entry = piIter.next()
                                val trackedPi = entry.value
                                val fakeLoc = buildFakeLocation(trackedPi.provider, cfg)
                                val intent = Intent().apply {
                                    putExtra(LocationManager.KEY_LOCATION_CHANGED, fakeLoc)
                                }
                                val token = Binder.clearCallingIdentity()
                                try {
                                    trackedPi.pendingIntent.send(ctx, 0, intent)
                                } catch (_: PendingIntent.CanceledException) {
                                    piIter.remove()
                                } catch (_: Throwable) {
                                } finally {
                                    Binder.restoreCallingIdentity(token)
                                }
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                }
            }
        }.apply {
            name = "LocationDrifter-SystemGpsFeeder"
            isDaemon = true
            start()
        }
    }

    private fun extractListenerBinder(listener: Any): IBinder? {
        return try {
            if (listener is IInterface) {
                listener.asBinder()
            } else {
                XposedHelpers.callMethod(listener, "asBinder") as? IBinder
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun markSystemServerHookedAsync() {
        Thread {
            for (attempt in 0 until 45) {
                try {
                    val ctx = resolveContext()
                    if (ctx != null) {
                        val cr = ctx.contentResolver
                        val bootCount = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0)
                        if (bootCount >= 0) {
                            Settings.Global.putInt(cr, ConfigManager.SETTINGS_GLOBAL_HOOKED_BOOT_KEY, bootCount)
                            XposedBridge.log("$TAG system_server hook ready for boot_count=$bootCount")
                            return@Thread
                        }
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(2000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            name = "LocationDrifter-BootMarker"
            isDaemon = true
            start()
        }
    }

    private fun resolveContext(): Context? {
        cachedContext?.let { return it }
        val app = try {
            AndroidAppHelper.currentApplication()
        } catch (_: Throwable) {
            null
        }
        if (app != null) {
            cachedContext = app
            return app
        }
        val sysCtx = try {
            val atCls = XposedHelpers.findClass("android.app.ActivityThread", null)
            val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
            if (at != null) {
                XposedHelpers.callMethod(at, "getSystemContext") as? Context
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        if (sysCtx != null) {
            cachedContext = sysCtx
        }
        return sysCtx
    }

    private fun shouldIsolateCaller(allowSameProcessBroadcast: Boolean = false): Boolean {
        val callingPid = Binder.getCallingPid()
        if (callingPid == Process.myPid()) {
            return allowSameProcessBroadcast
        }
        val uid = Binder.getCallingUid()
        if (uid < FIRST_APPLICATION_UID) return false

        uidBypassCache[uid]?.let { bypass -> return !bypass }
        val ctx = resolveContext() ?: return true
        val bypass = try {
            val pkgs = ctx.packageManager?.getPackagesForUid(uid)
            pkgs != null && pkgs.any { it in SYSTEM_UI_BYPASS_PACKAGES }
        } catch (_: Throwable) {
            false
        }
        uidBypassCache[uid] = bypass
        return !bypass
    }

    private fun shouldSpoofLocationCaller(): Boolean {
        val callingPid = Binder.getCallingPid()
        if (callingPid == Process.myPid()) return true
        val uid = Binder.getCallingUid()
        if (uid < FIRST_APPLICATION_UID) return true

        uidModuleSelfCache[uid]?.let { isSelf -> return !isSelf }
        val ctx = resolveContext() ?: return true
        val isSelf = try {
            val pkgs = ctx.packageManager?.getPackagesForUid(uid)
            pkgs != null && pkgs.any { it == MODULE_PACKAGE }
        } catch (_: Throwable) {
            false
        }
        uidModuleSelfCache[uid] = isSelf
        return !isSelf
    }

    private fun parseJsonConfig(jsonText: String): RuntimeConfig? {
        return try {
            val obj = JSONObject(jsonText)
            if (!obj.has(ConfigManager.KEY_ENABLED)) return null
            RuntimeConfig(
                enabled = obj.optBoolean(ConfigManager.KEY_ENABLED, false),
                wgsLat = obj.optDouble(ConfigManager.KEY_WGS_LAT, cachedConfig.wgsLat),
                wgsLng = obj.optDouble(ConfigManager.KEY_WGS_LNG, cachedConfig.wgsLng),
                blockNetworkDrift = obj.optBoolean(ConfigManager.KEY_BLOCK_NETWORK_DRIFT, true),
                enableJitter = obj.optBoolean(ConfigManager.KEY_JITTER, true),
                enableStepSim = obj.optBoolean(ConfigManager.KEY_ENABLE_STEP_SIM, false),
                simStepCount = obj.optLong(ConfigManager.KEY_SIM_STEP_COUNT, 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun getConfig(): RuntimeConfig {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoadTimeMs < CACHE_TTL_MS) {
            return cachedConfig
        }

        synchronized(this) {
            val nowInside = SystemClock.elapsedRealtime()
            if (nowInside - lastLoadTimeMs < CACHE_TTL_MS) {
                return cachedConfig
            }
            lastLoadTimeMs = nowInside

            try {
                val ctx = resolveContext()
                if (ctx != null) {
                    val raw = Settings.Global.getString(
                        ctx.contentResolver,
                        ConfigManager.SETTINGS_GLOBAL_KEY
                    )
                    if (!raw.isNullOrBlank()) {
                        parseJsonConfig(raw)?.let { parsed ->
                            cachedConfig = parsed
                            return cachedConfig
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            try {
                if (xPrefs == null) {
                    xPrefs = XSharedPreferences(MODULE_PACKAGE, ConfigManager.PREFS_NAME)
                } else {
                    xPrefs?.reload()
                }
                val prefs = xPrefs
                if (prefs != null && prefs.contains(ConfigManager.KEY_ENABLED)) {
                    cachedConfig = RuntimeConfig(
                        enabled = prefs.getBoolean(ConfigManager.KEY_ENABLED, false),
                        wgsLat = prefs.getString(ConfigManager.KEY_WGS_LAT, null)?.toDoubleOrNull()
                            ?: cachedConfig.wgsLat,
                        wgsLng = prefs.getString(ConfigManager.KEY_WGS_LNG, null)?.toDoubleOrNull()
                            ?: cachedConfig.wgsLng,
                        blockNetworkDrift = prefs.getBoolean(ConfigManager.KEY_BLOCK_NETWORK_DRIFT, true),
                        enableJitter = prefs.getBoolean(ConfigManager.KEY_JITTER, true),
                        enableStepSim = prefs.getBoolean(ConfigManager.KEY_ENABLE_STEP_SIM, false),
                        simStepCount = prefs.getLong(ConfigManager.KEY_SIM_STEP_COUNT, 0L)
                    )
                    return cachedConfig
                } else {
                    xPrefs = null
                }
            } catch (_: Throwable) {
                xPrefs = null
            }

            try {
                val file = File(ConfigManager.TMP_CONFIG_PATH)
                if (file.exists() && file.canRead()) {
                    parseJsonConfig(file.readText())?.let { parsed ->
                        cachedConfig = parsed
                        return cachedConfig
                    }
                }
            } catch (_: Throwable) {
            }

            return cachedConfig
        }
    }

    private fun refreshCoherentJitterIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastJitterTimeMs >= JITTER_STEP_MS) {
            lastJitterTimeMs = now
            cachedLatJitter = Random.nextDouble(-0.0000022, 0.0000022)
            cachedLngJitter = Random.nextDouble(-0.0000022, 0.0000022)
        }
    }

    private fun jitteredLat(cfg: RuntimeConfig): Double {
        if (!cfg.enableJitter) return cfg.wgsLat
        refreshCoherentJitterIfNeeded()
        return cfg.wgsLat + cachedLatJitter
    }

    private fun jitteredLng(cfg: RuntimeConfig): Double {
        if (!cfg.enableJitter) return cfg.wgsLng
        refreshCoherentJitterIfNeeded()
        return cfg.wgsLng + cachedLngJitter
    }

    private fun hookLocationClass() {
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (cfg.enabled) {
                            param.result = jitteredLat(cfg)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getLongitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (cfg.enabled) {
                            param.result = jitteredLng(cfg)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "writeToParcel",
                Parcel::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        val loc = param.thisObject as? Location ?: return
                        patchLocationInPlace(loc, cfg)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "set",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        val loc = param.thisObject as? Location ?: return
                        patchLocationInPlace(loc, cfg)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location.CREATOR.javaClass,
                "createFromParcel",
                Parcel::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        val loc = param.result as? Location ?: return
                        patchLocationInPlace(loc, cfg)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                Location::class.java,
                "setIsFromMockProvider",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (getConfig().enabled && param.args.isNotEmpty()) {
                            param.args[0] = false
                        }
                    }
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                XposedBridge.hookAllMethods(
                    Location::class.java,
                    "setMock",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (getConfig().enabled && param.args.isNotEmpty()) {
                                param.args[0] = false
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "isMock",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (getConfig().enabled) {
                                param.result = false
                            }
                        }
                    }
                )
            }

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isFromMockProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (getConfig().enabled) {
                            param.result = false
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getExtras",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!getConfig().enabled) return
                        val bundle = (param.result as? Bundle)?.let { Bundle(it) } ?: Bundle()
                        bundle.remove("mockLocation")
                        bundle.remove("mock")
                        bundle.remove("isMock")
                        if (!bundle.containsKey("satellites")) {
                            bundle.putInt("satellites", 12)
                        }
                        param.result = bundle
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookLocationClass error: ${t.message}")
        }
    }

    private fun hookLocationResult(classLoader: ClassLoader) {
        try {
            val lrCls = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                ?: XposedHelpers.findClassIfExists("android.location.LocationResult", ClassLoader.getSystemClassLoader())
                ?: return

            val patchResultObj = { resultObj: Any?, cfg: RuntimeConfig ->
                if (resultObj != null) {
                    try {
                        val list = XposedHelpers.callMethod(resultObj, "asList") as? List<*>
                        list?.forEach { item ->
                            (item as? Location)?.let { patchLocationInPlace(it, cfg) }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            XposedBridge.hookAllMethods(
                lrCls,
                "create",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        patchResultObj(param.result, cfg)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                lrCls,
                "wrap",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        patchResultObj(param.result, cfg)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                lrCls,
                "writeToParcel",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        patchResultObj(param.thisObject, cfg)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                lrCls,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        (param.result as? Location)?.let { patchLocationInPlace(it, cfg) }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                lrCls,
                "get",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        (param.result as? Location)?.let { patchLocationInPlace(it, cfg) }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookLocationResult error: ${t.message}")
        }
    }

    private fun hookSystemLocationServices(classLoader: ClassLoader) {
        val serviceClassNames = listOf(
            "com.android.server.location.LocationManagerService",
            "com.android.server.location.provider.LocationProviderManager"
        )
        for (clsName in serviceClassNames) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue

                XposedBridge.hookAllMethods(
                    cls,
                    "getLastLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!shouldSpoofLocationCaller()) return
                            val cfg = getConfig()
                            if (!cfg.enabled) return
                            val provider = resolveProviderName(param)
                            val existing = param.result as? Location
                            if (existing == null) {
                                param.result = buildFakeLocation(provider, cfg)
                            } else {
                                patchLocationInPlace(existing, cfg)
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "getCurrentLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldSpoofLocationCaller()) return
                            val cfg = getConfig()
                            if (!cfg.enabled) return
                            val callback = param.args.firstOrNull { arg ->
                                arg != null && (arg.javaClass.name.contains("ILocationCallback") ||
                                    arg.javaClass.interfaces.any { it.name == "android.location.ILocationCallback" })
                            } ?: return
                            val provider = resolveProviderName(param)
                            val token = Binder.clearCallingIdentity()
                            try {
                                val fakeLoc = buildFakeLocation(provider, cfg)
                                XposedHelpers.callMethod(callback, "onLocation", fakeLoc)
                                param.result = null
                            } catch (_: Throwable) {
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        }
                    }
                )

                val registerListenerHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldSpoofLocationCaller()) return
                        val listener = param.args.firstOrNull { arg ->
                            arg != null && (arg.javaClass.name.contains("ILocationListener") ||
                                arg.javaClass.interfaces.any { it.name == "android.location.ILocationListener" })
                        } ?: return
                        val provider = resolveProviderName(param)

                        extractListenerBinder(listener)?.let { binder ->
                            activeListeners[binder] = TrackedListener(listener, provider)
                        }

                        val cfg = getConfig()
                        if (!cfg.enabled) return
                        val fakeLoc = buildFakeLocation(provider, cfg)
                        val token = Binder.clearCallingIdentity()
                        try {
                            XposedHelpers.callMethod(
                                listener,
                                "onLocationChanged",
                                Collections.singletonList(fakeLoc),
                                null
                            )
                        } catch (_: Throwable) {
                            try {
                                XposedHelpers.callMethod(listener, "onLocationChanged", fakeLoc)
                            } catch (_: Throwable) {
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                }
                XposedBridge.hookAllMethods(cls, "registerLocationListener", registerListenerHook)
                XposedBridge.hookAllMethods(cls, "registerLocationRequest", registerListenerHook)

                XposedBridge.hookAllMethods(
                    cls,
                    "unregisterLocationListener",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val listener = param.args.firstOrNull { arg ->
                                arg != null && (arg.javaClass.name.contains("ILocationListener") ||
                                    arg.javaClass.interfaces.any { it.name == "android.location.ILocationListener" })
                            } ?: return
                            extractListenerBinder(listener)?.let { binder ->
                                activeListeners.remove(binder)
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "registerLocationPendingIntent",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!shouldSpoofLocationCaller()) return
                            val pi = param.args.firstOrNull { it is PendingIntent } as? PendingIntent ?: return
                            val provider = resolveProviderName(param)
                            activePendingIntents[pi] = TrackedPendingIntent(pi, provider)

                            val cfg = getConfig()
                            if (!cfg.enabled) return
                            val ctx = resolveContext() ?: return
                            val fakeLoc = buildFakeLocation(provider, cfg)
                            val intent = Intent().apply {
                                putExtra(LocationManager.KEY_LOCATION_CHANGED, fakeLoc)
                            }
                            val token = Binder.clearCallingIdentity()
                            try {
                                pi.send(ctx, 0, intent)
                            } catch (_: Throwable) {
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "unregisterLocationPendingIntent",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pi = param.args.firstOrNull { it is PendingIntent } as? PendingIntent ?: return
                            activePendingIntents.remove(pi)
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "onReportLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cfg = getConfig()
                            if (!cfg.enabled) return
                            for (arg in param.args) {
                                when {
                                    arg is Location -> patchLocationInPlace(arg, cfg)
                                    arg != null && arg.javaClass.name == "android.location.LocationResult" -> {
                                        try {
                                            val list = XposedHelpers.callMethod(arg, "asList") as? List<*>
                                            list?.forEach { (it as? Location)?.let { loc -> patchLocationInPlace(loc, cfg) } }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "isProviderEnabledForUser",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val provider = param.args.firstOrNull() as? String ?: return
                            if (provider == PROBE_PROVIDER) {
                                param.result = true
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val provider = param.args.firstOrNull() as? String ?: return
                            if (provider == PROBE_PROVIDER) {
                                param.result = true
                                return
                            }
                            if (!getConfig().enabled) return
                            if (provider in KNOWN_PROVIDERS) {
                                param.result = true
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "sendExtraCommand",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val provider = param.args.firstOrNull() as? String ?: return
                            val command = param.args.getOrNull(1) as? String ?: return
                            if (provider == PROBE_PROVIDER && command == "ping") {
                                param.result = true
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    cls,
                    "isLocationEnabledForUser",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (getConfig().enabled) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hookSystemLocationServices($clsName) error: ${t.message}")
            }
        }
    }

    private fun hookGnssManagerService(classLoader: ClassLoader) {
        val gnssClasses = listOf(
            "com.android.server.location.gnss.GnssManagerService",
            "com.android.server.location.LocationManagerService"
        )
        for (clsName in gnssClasses) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
                XposedBridge.hookAllMethods(
                    cls,
                    "registerGnssStatusCallback",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!shouldSpoofLocationCaller()) return
                            val cfg = getConfig()
                            if (!cfg.enabled) return
                            val listener = param.args.firstOrNull { arg ->
                                arg != null && (arg.javaClass.name.contains("IGnssStatusListener") ||
                                    arg.javaClass.interfaces.any { it.name == "android.location.IGnssStatusListener" })
                            } ?: return
                            val token = Binder.clearCallingIdentity()
                            try {
                                dispatchFakeGnssStatus(listener)
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hookGnssManagerService($clsName) error: ${t.message}")
            }
        }

        try {
            val statusProviderCls = XposedHelpers.findClassIfExists(
                "com.android.server.location.gnss.GnssStatusProvider",
                classLoader
            ) ?: return
            XposedBridge.hookAllMethods(
                statusProviderCls,
                "onReportSvStatus",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!getConfig().enabled) return
                        val fakeStatus = buildFakeGnssStatus() ?: return
                        for (i in param.args.indices) {
                            if (param.args[i] is GnssStatus) {
                                param.args[i] = fakeStatus
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun buildFakeGnssStatus(): GnssStatus? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val builder = GnssStatus.Builder()
            val svids = intArrayOf(2, 5, 9, 12, 17, 23, 201, 204, 208, 212)
            for (i in svids.indices) {
                val constellation = if (svids[i] > 200) {
                    GnssStatus.CONSTELLATION_BEIDOU
                } else {
                    GnssStatus.CONSTELLATION_GPS
                }
                val svid = if (svids[i] > 200) svids[i] - 200 else svids[i]
                builder.addSatellite(
                    constellation,
                    svid,
                    39.0f + (i % 4),
                    45.0f,
                    (i * 36).toFloat(),
                    true,
                    true,
                    true,
                    true,
                    1575420000.0f,
                    true,
                    37.0f
                )
            }
            builder.build()
        } catch (_: Throwable) {
            null
        }
    }

    private fun dispatchFakeGnssStatus(listener: Any) {
        try {
            XposedHelpers.callMethod(listener, "onGnssStarted")
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(listener, "onFirstFix", 120)
        } catch (_: Throwable) {
        }
        val status = buildFakeGnssStatus() ?: return
        try {
            XposedHelpers.callMethod(listener, "onSvStatusChanged", status)
        } catch (_: Throwable) {
        }
    }

    private fun hookParceledListSlice(cl: ClassLoader) {
        val sliceClassNames = listOf(
            "android.content.pm.ParceledListSlice",
            "com.android.wifi.x.com.android.modules.utils.ParceledListSlice",
            "com.android.wifi.x.com.android.modules.utils.BaseParceledListSlice"
        )
        for (clsName in sliceClassNames) {
            try {
                val plsCls = XposedHelpers.findClassIfExists(clsName, cl) ?: continue

                XposedBridge.hookAllConstructors(
                    plsCls,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val list = param.args.firstOrNull() as? List<*> ?: return
                            if (list.isEmpty()) return
                            val first = list[0] ?: return
                            if (first !is ScanResult && first !is CellInfo) return
                            if (!shouldIsolateCaller()) return

                            val cfg = getConfig()
                            if (cfg.enabled && cfg.blockNetworkDrift) {
                                param.args[0] = ArrayList<Any>()
                            }
                        }
                    }
                )

                XposedBridge.hookAllMethods(
                    plsCls,
                    "getList",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val list = param.result as? List<*> ?: return
                            if (list.isEmpty()) return
                            val first = list[0] ?: return
                            if (first !is ScanResult && first !is CellInfo) return
                            if (!shouldIsolateCaller()) return

                            val cfg = getConfig()
                            if (cfg.enabled && cfg.blockNetworkDrift) {
                                param.result = ArrayList<Any>()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hookParceledListSlice($clsName) error: ${t.message}")
            }
        }
    }

    private fun sanitizeWifiInfoInPlace(info: Any) {
        try {
            wifiBssidField?.set(info, "02:00:00:00:00:00")
            wifiMacField?.set(info, "02:00:00:00:00:00")
            wifiApMldMacField?.set(info, null)
            wifiSsidField?.set(info, null)
            wifiNetworkIdField?.setInt(info, -1)
        } catch (_: Throwable) {
        }
    }

    private fun hookWifiInfoSerialization() {
        try {
            XposedHelpers.findAndHookMethod(
                WifiInfo::class.java,
                "writeToParcel",
                Parcel::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return

                        val info = param.thisObject ?: return
                        try {
                            wifiBssidField?.let { f ->
                                param.setObjectExtra("origBssid", f.get(info))
                                f.set(info, "02:00:00:00:00:00")
                            }
                            wifiMacField?.let { f ->
                                param.setObjectExtra("origMac", f.get(info))
                                f.set(info, "02:00:00:00:00:00")
                            }
                            wifiApMldMacField?.let { f ->
                                param.setObjectExtra("origApMldMac", f.get(info))
                                f.set(info, null)
                            }
                            wifiSsidField?.let { f ->
                                param.setObjectExtra("origWifiSsid", f.get(info))
                                f.set(info, null)
                            }
                            wifiNetworkIdField?.let { f ->
                                param.setObjectExtra("origNetId", f.getInt(info))
                                f.setInt(info, -1)
                            }
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.thisObject ?: return
                        try {
                            val origBssid = param.getObjectExtra("origBssid")
                            if (origBssid != null) wifiBssidField?.set(info, origBssid)
                            val origMac = param.getObjectExtra("origMac")
                            if (origMac != null) wifiMacField?.set(info, origMac)
                            val origApMldMac = param.getObjectExtra("origApMldMac")
                            if (origApMldMac != null) wifiApMldMacField?.set(info, origApMldMac)
                            val origWifiSsid = param.getObjectExtra("origWifiSsid")
                            if (origWifiSsid != null) wifiSsidField?.set(info, origWifiSsid)
                            val origNetId = param.getObjectExtra("origNetId") as? Int
                            if (origNetId != null) wifiNetworkIdField?.setInt(info, origNetId)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookWifiInfoSerialization error: ${t.message}")
        }
    }

    private fun hookWifiServiceInSystemServer(classLoader: ClassLoader) {
        try {
            val wifiServiceCls = XposedHelpers.findClassIfExists(
                "com.android.server.wifi.WifiServiceImpl",
                classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                wifiServiceCls,
                "getScanResults",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return

                        val res = param.result ?: return
                        if (res is MutableList<*>) {
                            res.clear()
                        } else if (res.javaClass.name.endsWith("ParceledListSlice")) {
                            try {
                                val list = XposedHelpers.callMethod(res, "getList") as? MutableList<*>
                                list?.clear()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                wifiServiceCls,
                "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return
                        val info = param.result ?: return
                        sanitizeWifiInfoInPlace(info)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookWifiServiceInSystemServer error: ${t.message}")
        }
    }

    private fun hookTelephonyRegistryInSystemServer(classLoader: ClassLoader) {
        try {
            val regCls = XposedHelpers.findClassIfExists(
                "com.android.server.TelephonyRegistry",
                classLoader
            ) ?: return

            val cellInfoHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cfg = getConfig()
                    if (!cfg.enabled || !cfg.blockNetworkDrift) return
                    for (i in param.args.indices) {
                        val arg = param.args[i]
                        if (arg is List<*>) {
                            param.args[i] = ArrayList<CellInfo>()
                        }
                    }
                }
            }
            XposedBridge.hookAllMethods(regCls, "notifyCellInfo", cellInfoHook)
            XposedBridge.hookAllMethods(regCls, "notifyCellInfoForSubscriber", cellInfoHook)

            val cellLocHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cfg = getConfig()
                    if (!cfg.enabled || !cfg.blockNetworkDrift) return
                    for (i in param.args.indices) {
                        if (param.args[i] is CellIdentity || param.args[i] is Bundle) {
                            param.result = null
                            return
                        }
                    }
                }
            }
            XposedBridge.hookAllMethods(regCls, "notifyCellLocation", cellLocHook)
            XposedBridge.hookAllMethods(regCls, "notifyCellLocationForSubscriber", cellLocHook)

            XposedBridge.hookAllMethods(
                regCls,
                "getAllCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (cfg.enabled && cfg.blockNetworkDrift) {
                            param.result = ArrayList<CellInfo>()
                        }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                regCls,
                "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (cfg.enabled && cfg.blockNetworkDrift) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookTelephonyRegistryInSystemServer error: ${t.message}")
        }
    }

    private fun hookNetworkRegistrationInfo(classLoader: ClassLoader) {
        try {
            val nriCls = XposedHelpers.findClassIfExists(
                "android.telephony.NetworkRegistrationInfo",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "android.telephony.NetworkRegistrationInfo",
                ClassLoader.getSystemClassLoader()
            ) ?: return

            val cellIdentityField: Field? = XposedHelpers.findFieldIfExists(nriCls, "mCellIdentity")

            XposedBridge.hookAllMethods(
                nriCls,
                "getCellIdentity",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (cfg.enabled && cfg.blockNetworkDrift) {
                            param.result = null
                        }
                    }
                }
            )

            if (cellIdentityField != null) {
                XposedBridge.hookAllMethods(
                    nriCls,
                    "sanitizeLocationInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!shouldIsolateCaller()) return
                            val cfg = getConfig()
                            if (!cfg.enabled || !cfg.blockNetworkDrift) return
                            val sanitized = param.result ?: return
                            try {
                                cellIdentityField.set(sanitized, null)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    nriCls,
                    "writeToParcel",
                    Parcel::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldIsolateCaller()) return
                            val cfg = getConfig()
                            if (!cfg.enabled || !cfg.blockNetworkDrift) return
                            val obj = param.thisObject ?: return
                            try {
                                val orig = cellIdentityField.get(obj)
                                if (orig != null) {
                                    param.setObjectExtra("origCellIdentity", orig)
                                    cellIdentityField.set(obj, null)
                                }
                            } catch (_: Throwable) {
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val obj = param.thisObject ?: return
                            try {
                                val orig = param.getObjectExtra("origCellIdentity")
                                if (orig != null) {
                                    cellIdentityField.set(obj, orig)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookNetworkRegistrationInfo error: ${t.message}")
        }
    }

    private fun hookPhoneInterfaceManager(classLoader: ClassLoader) {
        try {
            val phoneMgrCls = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.PhoneInterfaceManager",
                classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                phoneMgrCls,
                "getAllCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return
                        param.result = ArrayList<CellInfo>()
                    }
                }
            )

            XposedBridge.hookAllMethods(
                phoneMgrCls,
                "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return
                        param.result = null
                    }
                }
            )

            XposedBridge.hookAllMethods(
                phoneMgrCls,
                "getNeighboringCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return
                        param.result = ArrayList<Any>()
                    }
                }
            )

            XposedBridge.hookAllMethods(
                phoneMgrCls,
                "requestCellInfoUpdate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldIsolateCaller()) return
                        val cfg = getConfig()
                        if (!cfg.enabled || !cfg.blockNetworkDrift) return
                        val cb = param.args.firstOrNull {
                            it != null && it.javaClass.name.contains("ICellInfoCallback")
                        } ?: return
                        val token = Binder.clearCallingIdentity()
                        try {
                            XposedHelpers.callMethod(cb, "onCellInfo", ArrayList<CellInfo>())
                            param.result = null
                        } catch (_: Throwable) {
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookPhoneInterfaceManager error: ${t.message}")
        }
    }

    private fun buildFakeLocation(provider: String, cfg: RuntimeConfig): Location {
        val validProvider = if (provider in KNOWN_PROVIDERS) provider else LocationManager.GPS_PROVIDER
        return Location(validProvider).apply {
            latitude = jitteredLat(cfg)
            longitude = jitteredLng(cfg)
            altitude = 36.0
            accuracy = 3.2f
            speed = 0.08f
            bearing = 15.0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 0.5f
                speedAccuracyMetersPerSecond = 0.2f
                verticalAccuracyMeters = 2.0f
            }
            extras = Bundle().apply {
                putInt("satellites", 12)
                putInt("maxCn0", 42)
            }
            stripMockMarkers(this)
        }
    }

    private fun patchLocationInPlace(loc: Location, cfg: RuntimeConfig) {
        try {
            if (loc.provider !in KNOWN_PROVIDERS) {
                loc.provider = LocationManager.GPS_PROVIDER
            }
            loc.latitude = jitteredLat(cfg)
            loc.longitude = jitteredLng(cfg)
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (loc.accuracy <= 0f || loc.accuracy > 20f) {
                loc.accuracy = 3.2f
            }
            if (!loc.hasAltitude()) {
                loc.altitude = 36.0
            }
            stripMockMarkers(loc)
        } catch (_: Throwable) {
        }
    }

    private fun stripMockMarkers(loc: Location) {
        try {
            XposedHelpers.callMethod(loc, "setIsFromMockProvider", false)
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                XposedHelpers.callMethod(loc, "setMock", false)
            } catch (_: Throwable) {
            }
        }
        try {
            locIsFromMockField?.let { field ->
                field.isAccessible = true
                field.setBoolean(loc, false)
            }
        } catch (_: Throwable) {
        }
        try {
            locFieldsMaskField?.let { field ->
                field.isAccessible = true
                val mask = field.getInt(loc)
                if ((mask and HAS_MOCK_PROVIDER_MASK) != 0) {
                    field.setInt(loc, mask and HAS_MOCK_PROVIDER_MASK.inv())
                }
            }
        } catch (_: Throwable) {
        }
        try {
            val extras = loc.extras
            val clean = if (extras != null) Bundle(extras) else Bundle()
            clean.remove("mockLocation")
            clean.remove("mock")
            clean.remove("isMock")
            if (!clean.containsKey("satellites")) {
                clean.putInt("satellites", 12)
            }
            loc.extras = clean
        } catch (_: Throwable) {
        }
    }
}
