package com.separ.locationdrifter.xposed

import android.annotation.SuppressLint
import java.io.File

/**
 * 运行于 system_server 进程内的实验性步数传感器 Native Hook 桥接层：
 * - 严格遵守「零三方应用注入」原则，仅在 system_server 进程内从本模块 APK 原生库目录加载 .so；
 * - 仅在用户主动开启「步数模拟(实验性)」时按需懒加载，默认关闭状态下完全不触碰 libsensorservice.so。
 */
object NativeStepBridge {

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var hooked: Boolean = false

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun ensureLoadedAndHooked(nativeLibDir: String): Int {
        if (hooked) return 0
        if (!loaded) {
            val dir = File(nativeLibDir)
            if (!dir.isDirectory) return -10
            val shadowSo = File(dir, "libshadowhook.so")
            val sensorSo = File(dir, "liblocationdrifter_sensor.so")
            if (!shadowSo.exists() || !sensorSo.exists()) return -11
            System.load(shadowSo.absolutePath)
            System.load(sensorSo.absolutePath)
            loaded = true
        }
        val ret = nativeInstallStepHook()
        if (ret == 0) {
            hooked = true
        }
        return ret
    }

    fun updateStateSafe(enabled: Boolean, simStepDelta: Long) {
        if (!loaded) return
        try {
            nativeUpdateStepState(enabled, simStepDelta)
        } catch (_: Throwable) {
        }
    }

    fun getStatusSafe(): String {
        if (!loaded) return "not_loaded"
        return try {
            nativeGetHookStatus()
        } catch (_: Throwable) {
            "error"
        }
    }

    @JvmStatic
    private external fun nativeInstallStepHook(): Int

    @JvmStatic
    private external fun nativeUpdateStepState(enabled: Boolean, simStepDelta: Long)

    @JvmStatic
    private external fun nativeGetHookStatus(): String
}
