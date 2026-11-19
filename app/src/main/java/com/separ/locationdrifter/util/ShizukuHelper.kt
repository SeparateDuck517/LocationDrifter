package com.separ.locationdrifter.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * 免 Root Shizuku (UID 2000 ADB Shell) 特权通道辅助工具：
 * 1) 优先通过 ShizukuBinderWrapper 直连系统 IAppOpsService 授予 OP_MOCK_LOCATION，并辅以多格式 appops 命令兜底，
 *    自动放行模拟位置与定位/通知权限，免去手动配置开发者选项；
 * 2) 在开启定位漂移期间自动临时关闭系统后台 Wi-Fi / 蓝牙扫描减少坐标回弹，停止漂移后自动还原。
 */
object ShizukuHelper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SCAN_BACKUP_PREFS = "shizuku_scan_backup"
    private const val KEY_HAS_BACKUP = "has_backup"
    private const val KEY_ORIG_WIFI_SCAN = "orig_wifi_scan"
    private const val KEY_ORIG_BLE_SCAN = "orig_ble_scan"
    private const val OP_MOCK_LOCATION_CODE = 58

    private val shizukuExecutor = Executors.newSingleThreadExecutor()

    fun runShizukuTask(task: () -> Unit) {
        shizukuExecutor.execute(task)
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldShowRationale(): Boolean {
        return try {
            isBinderAlive() && Shizuku.shouldShowRequestPermissionRationale()
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (isBinderAlive()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Throwable) {
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                true
            } else {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 通过 Shizuku 特权进程执行 Shell 命令（兼容 Shizuku API 13.x）
     */
    fun execShizuku(command: String): RootUtils.CommandResult {
        if (!hasPermission()) {
            return RootUtils.CommandResult(false, "", "Shizuku not authorized")
        }
        var process: Process? = null
        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }
            process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            val stderr = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use {
                it.readText()
            }
            val code = process.waitFor()
            RootUtils.CommandResult(code == 0, stdout.trim(), stderr.trim())
        } catch (t: Throwable) {
            RootUtils.CommandResult(false, "", t.message ?: "Shizuku exec failed")
        } finally {
            process?.destroy()
        }
    }

    /**
     * 通过 ShizukuBinderWrapper 直连系统 IAppOpsService 授予 OP_MOCK_LOCATION（与系统开发者选项同源）
     */
    private fun grantMockLocationViaBinder(context: Context): Boolean {
        if (!hasPermission()) return false
        return try {
            val rawBinder: IBinder = try {
                val sshCls = Class.forName("rikka.shizuku.SystemServiceHelper")
                val getSvc = sshCls.getMethod("getSystemService", String::class.java)
                getSvc.invoke(null, Context.APP_OPS_SERVICE) as? IBinder
            } catch (_: Throwable) {
                null
            } ?: run {
                val smCls = Class.forName("android.os.ServiceManager")
                val getSvc = smCls.getMethod("getService", String::class.java)
                getSvc.invoke(null, Context.APP_OPS_SERVICE) as? IBinder
            } ?: return false

            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubCls = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            val asInterface = stubCls.getMethod("asInterface", IBinder::class.java)
            val appOpsService = asInterface.invoke(null, wrappedBinder) ?: return false

            val uid = android.os.Process.myUid()
            val pkg = context.packageName
            val opCode = try {
                val field = AppOpsManager::class.java.getDeclaredField("OP_MOCK_LOCATION")
                field.isAccessible = true
                field.getInt(null)
            } catch (_: Throwable) {
                OP_MOCK_LOCATION_CODE
            }

            try {
                val setMode = appOpsService.javaClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                setMode.invoke(appOpsService, opCode, uid, pkg, AppOpsManager.MODE_ALLOWED)
            } catch (_: Throwable) {
            }

            try {
                val setUidMode = appOpsService.javaClass.getMethod(
                    "setUidMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                setUidMode.invoke(appOpsService, opCode, uid, AppOpsManager.MODE_ALLOWED)
            } catch (_: Throwable) {
            }

            RootUtils.isMockLocationAllowed(context)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 免 Root 自动授权模拟位置与定位/通知权限，并以实际 AppOpsManager 检测结果作为最终返回值
     */
    fun grantMockAndLocationPermissions(context: Context): Boolean {
        if (!hasPermission()) return RootUtils.isMockLocationAllowed(context)
        val packageName = context.packageName
        val uid = android.os.Process.myUid()

        // 1. 优先尝试 Binder 直连 IAppOpsService 授予模拟位置权限
        if (!RootUtils.isMockLocationAllowed(context)) {
            grantMockLocationViaBinder(context)
        }

        // 2. 补充多格式 shell appops 与运行时定位/通知权限授权
        val cmd = """
            cmd appops set --user 0 $packageName android:mock_location allow 2>/dev/null || true
            appops set --user 0 $packageName android:mock_location allow 2>/dev/null || true
            cmd appops set --uid $uid android:mock_location allow 2>/dev/null || true
            appops set $packageName android:mock_location allow 2>/dev/null || true
            appops set $packageName $OP_MOCK_LOCATION_CODE allow 2>/dev/null || true
            pm grant --user 0 $packageName android.permission.ACCESS_FINE_LOCATION 2>/dev/null || pm grant $packageName android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
            pm grant --user 0 $packageName android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || pm grant $packageName android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
            pm grant --user 0 $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null || pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null || true
        """.trimIndent()
        execShizuku(cmd)

        return RootUtils.isMockLocationAllowed(context)
    }

    /**
     * 漂移期间自动临时关闭系统 Wi-Fi 扫描与蓝牙扫描，显著减少免 Root 下的室内坐标回弹
     */
    fun applyAntiDriftScanSuppression(context: Context): Boolean {
        if (!hasPermission()) return false
        val prefs = context.getSharedPreferences(SCAN_BACKUP_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_BACKUP, false)) {
            val origWifi = try {
                Settings.Global.getInt(context.contentResolver, "wifi_scan_always_enabled", 1)
            } catch (_: Throwable) {
                1
            }
            val origBle = try {
                Settings.Global.getInt(context.contentResolver, "ble_scan_always_enabled", 1)
            } catch (_: Throwable) {
                1
            }
            prefs.edit()
                .putBoolean(KEY_HAS_BACKUP, true)
                .putInt(KEY_ORIG_WIFI_SCAN, origWifi)
                .putInt(KEY_ORIG_BLE_SCAN, origBle)
                .apply()
        }
        val cmd = """
            settings put global wifi_scan_always_enabled 0 2>/dev/null || cmd settings put global wifi_scan_always_enabled 0 2>/dev/null || true
            settings put global ble_scan_always_enabled 0 2>/dev/null || cmd settings put global ble_scan_always_enabled 0 2>/dev/null || true
        """.trimIndent()
        return execShizuku(cmd).success
    }

    /**
     * 停止漂移时按备份恢复系统 Wi-Fi 扫描与蓝牙扫描开关状态
     */
    fun restoreAntiDriftScanSuppression(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SCAN_BACKUP_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_BACKUP, false)) return true
        val origWifi = prefs.getInt(KEY_ORIG_WIFI_SCAN, 1)
        val origBle = prefs.getInt(KEY_ORIG_BLE_SCAN, 1)
        prefs.edit().putBoolean(KEY_HAS_BACKUP, false).apply()

        if (!hasPermission()) return false
        val cmd = """
            settings put global wifi_scan_always_enabled $origWifi 2>/dev/null || cmd settings put global wifi_scan_always_enabled $origWifi 2>/dev/null || true
            settings put global ble_scan_always_enabled $origBle 2>/dev/null || cmd settings put global ble_scan_always_enabled $origBle 2>/dev/null || true
        """.trimIndent()
        return execShizuku(cmd).success
    }
}
