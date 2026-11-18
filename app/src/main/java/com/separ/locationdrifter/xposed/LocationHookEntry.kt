package com.separ.locationdrifter.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
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
import kotlin.random.Random

/**
 * LSPosed / Xposed 入口（严格遵守「零三方应用注入」架构）：
 * 作用域仅限系统框架 (android) 与电话服务 (com.android.phone)，绝不向任何第三方业务应用注入 Hook 代码：
 * 1) 在 system_server 挂钩 LocationManagerService / LocationProviderManager / GnssManagerService / LocationResult / Location：
 *    - 主动响应 getLastLocation / getCurrentLocation / registerLocationListener / registerGnssStatusCallback，
 *      即使室内无真实卫星信号也毫秒级下发带 10 颗有效卫星的 WGS-84 坐标，并在 Parcel 层彻底抹除 Mock 标记。
 *    - 采用时间步长一致性微抖动 (Coherent Time-Stepped Jitter)，保证同一时间窗口内多次调用 getLatitude/getLongitude 幂等一致。
 * 2) 在 Binder IPC 源头彻底隔离 Wi-Fi 与蜂窝基站（含 Android 10+ ServiceState -> NetworkRegistrationInfo.mCellIdentity）：
 *    - 使第三方定位 SDK 无法获取任何真实基站 CID/TAC 或周边 Wi-Fi BSSID 指纹，
 *      迫使其采信系统层下发的虚拟 GPS 坐标并通过在线逆地理编码正常解析位置地址。
 */
class LocationHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[LocationDrifter]"
        private const val MODULE_PACKAGE = "com.separ.locationdrifter"
        private const val CACHE_TTL_MS = 600L
        private const val JITTER_STEP_MS = 1000L
        private const val FIRST_APPLICATION_UID = 10000
        private const val HAS_MOCK_PROVIDER_MASK = 1 shl 4

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
        val enableJitter: Boolean = true
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

    private val uidBypassCache = ConcurrentHashMap<Int, Boolean>()
    private val uidModuleSelfCache = ConcurrentHashMap<Int, Boolean>()

    private val locFieldsMaskField: Field? by lazy {
        XposedHelpers.findFieldIfExists(Location::class.java, "mFieldsMask")
    }
    private val locIsFromMockField: Field? by lazy {
        XposedHelpers.findFieldIfExists(Location::class.java, "mIsFromMockProvider")
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
                hookParceledListSlice()
                hookWifiServiceInSystemServer(lpparam.classLoader)
                hookTelephonyRegistryInSystemServer(lpparam.classLoader)
                hookNetworkRegistrationInfo(lpparam.classLoader)
                markSystemServerHookedAsync()
            }
            "com.android.phone" -> {
                hookParceledListSlice()
                hookPhoneInterfaceManager(lpparam.classLoader)
                hookNetworkRegistrationInfo(lpparam.classLoader)
            }
            else -> {
                return
            }
        }
    }

    /**
     * 在 system_server 启动并加载本模块后，将当前 BOOT_COUNT 写入 Settings.Global，
     * 供前端精准区分「刚开启 LSPosed 开关但尚未重启」与「已重启且 system_server Hook 生效」两种状态。
     */
    private fun markSystemServerHookedAsync() {
        Thread {
            for (attempt in 0 until 30) {
                try {
                    val ctx = resolveContext()
                    if (ctx != null) {
                        val cr = ctx.contentResolver
                        val bootCount = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
                        if (bootCount > 0) {
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

    /**
     * 判断当前 Binder 调用方是否需要隔离环境指纹（跳过系统自身、SystemUI、设置页与本模块自身）
     */
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

    /**
     * 判断当前定位请求调用方是否需要注入虚拟位置（跳过模块自身进程，避免模块自检干扰）
     */
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
                enableJitter = obj.optBoolean(ConfigManager.KEY_JITTER, true)
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

            // 1. 首选 Settings.Global（system_server 与 com.android.phone 均可零延迟读取，无 SELinux 限制）
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

            // 2. 次选 LSPosed New XSharedPreferences
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
                        enableJitter = prefs.getBoolean(ConfigManager.KEY_JITTER, true)
                    )
                    return cachedConfig
                } else {
                    xPrefs = null
                }
            } catch (_: Throwable) {
                xPrefs = null
            }

            // 3. 兜底读取 /data/local/tmp/location_drifter.json
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

    /**
     * 按固定时间窗口 (1000ms) 更新一次微抖动偏移，保证同一窗口内多次调用 getLatitude/getLongitude 数值幂等一致
     */
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

    /**
     * 拦截 Android 12+ LocationResult 批量位置容器，确保所有经由 LocationProviderManager 发出的坐标均被覆写并抹除 Mock 标记
     */
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

    /**
     * 在 system_server 内部直接挂钩 LocationManagerService 与 LocationProviderManager：
     * 1) getLastLocation 为 null 时直接合成虚拟 Location 返回
     * 2) getCurrentLocation / registerLocationListener 时立即通过 Binder 回调推送首帧虚拟 Location
     * 3) isProviderEnabledForUser / isLocationEnabledForUser 始终返回 true
     */
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
                            val existing = param.result as? Location
                            if (existing == null) {
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER
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
                            val provider = (param.args.firstOrNull { it is String } as? String)
                                ?: LocationManager.GPS_PROVIDER
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

                XposedBridge.hookAllMethods(
                    cls,
                    "registerLocationListener",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!shouldSpoofLocationCaller()) return
                            val cfg = getConfig()
                            if (!cfg.enabled) return
                            val listener = param.args.firstOrNull { arg ->
                                arg != null && (arg.javaClass.name.contains("ILocationListener") ||
                                    arg.javaClass.interfaces.any { it.name == "android.location.ILocationListener" })
                            } ?: return
                            val provider = (param.args.firstOrNull { it is String } as? String)
                                ?: LocationManager.GPS_PROVIDER
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
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!getConfig().enabled) return
                            val provider = param.args.firstOrNull() as? String ?: return
                            if (provider == LocationManager.GPS_PROVIDER ||
                                provider == LocationManager.NETWORK_PROVIDER ||
                                provider == "fused"
                            ) {
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

    /**
     * 在 system_server 挂钩 GnssManagerService / GnssStatusProvider：
     * 当定位 SDK 注册 IGnssStatusListener 检测卫星状态时，立即下发 onGnssStarted + onFirstFix + 10 颗工作卫星
     */
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

    /**
     * 拦截跨进程 Parcel 中的 ScanResult / CellInfo 列表，
     * 覆盖标准 framework 与 Android 16 独立 Wi-Fi Mainline APEX 的 ParceledListSlice 类加载器
     */
    private fun hookParceledListSlice() {
        val sliceClassNames = listOf(
            "android.content.pm.ParceledListSlice",
            "com.android.wifi.x.com.android.modules.utils.ParceledListSlice",
            "com.android.wifi.x.com.android.modules.utils.BaseParceledListSlice"
        )
        val cl = ClassLoader.getSystemClassLoader()
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

    /**
     * 在 WifiInfo 通过 Binder 写入 Parcel 发往三方应用前临时脱敏 BSSID/MAC/AP MLD MAC，
     * 序列化完成后立即还原，避免影响系统内部 Wi-Fi 状态与状态栏显示
     */
    private fun hookWifiInfoSerialization() {
        try {
            val bssidField: Field? = XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mBSSID")
            val macField: Field? = XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mMacAddress")
            val apMldMacField: Field? = XposedHelpers.findFieldIfExists(WifiInfo::class.java, "mApMldMacAddress")

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
                            if (bssidField != null) {
                                param.setObjectExtra("origBssid", bssidField.get(info))
                                bssidField.set(info, "02:00:00:00:00:00")
                            }
                            if (macField != null) {
                                param.setObjectExtra("origMac", macField.get(info))
                                macField.set(info, "02:00:00:00:00:00")
                            }
                            if (apMldMacField != null) {
                                param.setObjectExtra("origApMldMac", apMldMacField.get(info))
                                apMldMacField.set(info, null)
                            }
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.thisObject ?: return
                        try {
                            val origBssid = param.getObjectExtra("origBssid")
                            if (origBssid != null && bssidField != null) {
                                bssidField.set(info, origBssid)
                            }
                            val origMac = param.getObjectExtra("origMac")
                            if (origMac != null && macField != null) {
                                macField.set(info, origMac)
                            }
                            val origApMldMac = param.getObjectExtra("origApMldMac")
                            if (origApMldMac != null && apMldMacField != null) {
                                apMldMacField.set(info, origApMldMac)
                            }
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
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookWifiServiceInSystemServer error: ${t.message}")
        }
    }

    /**
     * 在 system_server 拦截 TelephonyRegistry 异步推送基站列表与位置变更
     */
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
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookTelephonyRegistryInSystemServer error: ${t.message}")
        }
    }

    /**
     * 关键防漂移通道：拦截 Android 10–16 ServiceState -> NetworkRegistrationInfo 中的 mCellIdentity。
     */
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

    /**
     * 在 com.android.phone 进程隔离蜂窝基站信息返回给三方应用
     */
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
        return Location(provider).apply {
            latitude = jitteredLat(cfg)
            longitude = jitteredLng(cfg)
            altitude = 36.0
            accuracy = 3.5f
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
            loc.latitude = jitteredLat(cfg)
            loc.longitude = jitteredLng(cfg)
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (loc.accuracy <= 0f || loc.accuracy > 20f) {
                loc.accuracy = 3.5f
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
