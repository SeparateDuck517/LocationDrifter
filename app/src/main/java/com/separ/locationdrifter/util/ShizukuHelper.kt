package com.separ.locationdrifter.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * 免 Root Shizuku (UID 2000 ADB Shell) 特权通道辅助工具：
 * 1) 通过 ShizukuBinderWrapper 直连系统 IAppOpsService 授予 OP_MOCK_LOCATION，并辅以多格式 appops 命令兜底；
 * 2) 合并执行系统后台 Wi-Fi / 蓝牙扫描关闭与指定应用包名的扫描权限隔离，减少重复 Shell 进程创建开销。
 */
object ShizukuHelper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val SCAN_BACKUP_PREFS = "shizuku_scan_backup"
    private const val KEY_HAS_BACKUP = "has_backup"
    private const val KEY_ORIG_WIFI_SCAN = "orig_wifi_scan"
    private const val KEY_ORIG_BLE_SCAN = "orig_ble_scan"

    private const val TARGET_APPS_PREFS = "shizuku_target_apps_prefs"
    private const val KEY_TARGET_PACKAGES = "target_packages_json"
    private const val KEY_APPOPS_BACKUP_MAP = "appops_backup_map_json"

    private const val OP_MOCK_LOCATION_CODE = 58

    private val TARGET_ISOLATED_OPS = listOf(
        "android:wifi_scan",
        "android:bluetooth_scan"
    )

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

    fun grantMockAndLocationPermissions(context: Context): Boolean {
        if (!hasPermission()) return RootUtils.isMockLocationAllowed(context)
        if (RootUtils.isMockLocationAllowed(context)) return true

        val packageName = context.packageName
        val uid = android.os.Process.myUid()

        if (grantMockLocationViaBinder(context)) {
            return true
        }

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

    fun getTargetApps(context: Context): List<String> {
        val prefs = context.getSharedPreferences(TARGET_APPS_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TARGET_PACKAGES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i).trim()
                if (pkg.isNotEmpty() && pkg !in list) {
                    list.add(pkg)
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setTargetApps(context: Context, packages: List<String>) {
        val arr = JSONArray()
        packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach {
            arr.put(it)
        }
        context.getSharedPreferences(TARGET_APPS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_PACKAGES, arr.toString())
            .apply()
    }

    fun getTargetAppsSummary(context: Context): String {
        val list = getTargetApps(context)
        return if (list.isEmpty()) "未配置" else "已选 ${list.size} 项"
    }

    private fun buildScanSuppressionScript(context: Context): String {
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
        return """
            settings put global wifi_scan_always_enabled 0 2>/dev/null || cmd settings put global wifi_scan_always_enabled 0 2>/dev/null || true
            settings put global ble_scan_always_enabled 0 2>/dev/null || cmd settings put global ble_scan_always_enabled 0 2>/dev/null || true
            cmd wifi set-scan-always-available disabled 2>/dev/null || true
        """.trimIndent()
    }

    private fun buildTargetIsolationScript(
        context: Context,
        packages: List<String>
    ): Pair<String, Int> {
        if (packages.isEmpty()) return "" to 0
        val prefs = context.getSharedPreferences(TARGET_APPS_PREFS, Context.MODE_PRIVATE)
        val backupJson = try {
            JSONObject(prefs.getString(KEY_APPOPS_BACKUP_MAP, "{}") ?: "{}")
        } catch (_: Throwable) {
            JSONObject()
        }

        val pm = context.packageManager
        val cmdBuilder = StringBuilder()
        var isolatedCount = 0

        for (rawPkg in packages) {
            val pkg = rawPkg.trim()
            if (pkg.isEmpty() || pkg == context.packageName) continue
            val installed = try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
            if (!installed) continue

            for (op in TARGET_ISOLATED_OPS) {
                val key = "$pkg|$op"
                if (!backupJson.has(key)) {
                    backupJson.put(key, "default")
                }
                cmdBuilder.append("cmd appops set --user 0 $pkg $op ignore 2>/dev/null || appops set $pkg $op ignore 2>/dev/null || true\n")
            }
            isolatedCount++
        }

        prefs.edit().putString(KEY_APPOPS_BACKUP_MAP, backupJson.toString()).apply()
        return cmdBuilder.toString() to isolatedCount
    }

    /**
     * 单次 Shizuku Shell 批量执行扫描压制与目标应用隔离，消除重复进程拉起延迟
     */
    fun applyBatchAntiDrift(
        context: Context,
        suppressScan: Boolean,
        isolateTargets: Boolean
    ): Int {
        if (!hasPermission()) return 0
        val sb = StringBuilder()
        if (suppressScan) {
            sb.append(buildScanSuppressionScript(context)).append('\n')
        }
        var count = 0
        if (isolateTargets) {
            val (script, isolatedCount) = buildTargetIsolationScript(context, getTargetApps(context))
            count = isolatedCount
            if (script.isNotEmpty()) {
                sb.append(script).append('\n')
            }
        }
        if (sb.isNotEmpty()) {
            execShizuku(sb.toString())
        }
        return count
    }

    fun applyTargetAppLocationIsolation(
        context: Context,
        packages: List<String> = getTargetApps(context)
    ): Int {
        if (!hasPermission() || packages.isEmpty()) return 0
        val (script, count) = buildTargetIsolationScript(context, packages)
        if (script.isNotEmpty()) {
            execShizuku(script)
        }
        return count
    }

    fun restoreTargetAppLocationIsolation(context: Context): Boolean {
        val prefs = context.getSharedPreferences(TARGET_APPS_PREFS, Context.MODE_PRIVATE)
        val rawMap = prefs.getString(KEY_APPOPS_BACKUP_MAP, null)
        if (rawMap.isNullOrBlank() || rawMap == "{}") return true
        if (!hasPermission()) return false

        val backupJson = try {
            JSONObject(rawMap)
        } catch (_: Throwable) {
            prefs.edit().remove(KEY_APPOPS_BACKUP_MAP).apply()
            return true
        }

        val cmdBuilder = StringBuilder()
        val keys = backupJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parts = key.split("|")
            if (parts.size != 2) continue
            val pkg = parts[0]
            val op = parts[1]
            val origMode = backupJson.optString(key, "default").ifBlank { "default" }
            cmdBuilder.append("cmd appops set --user 0 $pkg $op $origMode 2>/dev/null || appops set $pkg $op $origMode 2>/dev/null || true\n")
        }

        if (cmdBuilder.isNotEmpty()) {
            execShizuku(cmdBuilder.toString())
        }
        prefs.edit().remove(KEY_APPOPS_BACKUP_MAP).apply()
        return true
    }

    fun applyAntiDriftScanSuppression(context: Context): Boolean {
        if (!hasPermission()) return false
        return execShizuku(buildScanSuppressionScript(context)).success
    }

    fun restoreAntiDriftScanSuppression(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SCAN_BACKUP_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_BACKUP, false)) return true
        val origWifi = prefs.getInt(KEY_ORIG_WIFI_SCAN, 1)
        val origBle = prefs.getInt(KEY_ORIG_BLE_SCAN, 1)
        prefs.edit().putBoolean(KEY_HAS_BACKUP, false).apply()

        if (!hasPermission()) return false
        val wifiStateWord = if (origWifi == 1) "enabled" else "disabled"
        val cmd = """
            settings put global wifi_scan_always_enabled $origWifi 2>/dev/null || cmd settings put global wifi_scan_always_enabled $origWifi 2>/dev/null || true
            settings put global ble_scan_always_enabled $origBle 2>/dev/null || cmd settings put global ble_scan_always_enabled $origBle 2>/dev/null || true
            cmd wifi set-scan-always-available $wifiStateWord 2>/dev/null || true
        """.trimIndent()
        return execShizuku(cmd).success
    }

    fun restoreAllAntiDriftStates(context: Context) {
        val scanPrefs = context.getSharedPreferences(SCAN_BACKUP_PREFS, Context.MODE_PRIVATE)
        val targetPrefs = context.getSharedPreferences(TARGET_APPS_PREFS, Context.MODE_PRIVATE)
        val hasScanBackup = scanPrefs.getBoolean(KEY_HAS_BACKUP, false)
        val rawMap = targetPrefs.getString(KEY_APPOPS_BACKUP_MAP, null)
        val hasTargetBackup = !rawMap.isNullOrBlank() && rawMap != "{}"
        if (!hasScanBackup && !hasTargetBackup) return

        val sb = StringBuilder()
        if (hasScanBackup) {
            val origWifi = scanPrefs.getInt(KEY_ORIG_WIFI_SCAN, 1)
            val origBle = scanPrefs.getInt(KEY_ORIG_BLE_SCAN, 1)
            scanPrefs.edit().putBoolean(KEY_HAS_BACKUP, false).apply()
            val wifiStateWord = if (origWifi == 1) "enabled" else "disabled"
            sb.append(
                """
                settings put global wifi_scan_always_enabled $origWifi 2>/dev/null || cmd settings put global wifi_scan_always_enabled $origWifi 2>/dev/null || true
                settings put global ble_scan_always_enabled $origBle 2>/dev/null || cmd settings put global ble_scan_always_enabled $origBle 2>/dev/null || true
                cmd wifi set-scan-always-available $wifiStateWord 2>/dev/null || true
                """.trimIndent()
            ).append('\n')
        }
        if (hasTargetBackup) {
            targetPrefs.edit().remove(KEY_APPOPS_BACKUP_MAP).apply()
            runCatching {
                val backupJson = JSONObject(rawMap!!)
                val keys = backupJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val parts = key.split("|")
                    if (parts.size == 2) {
                        val origMode = backupJson.optString(key, "default").ifBlank { "default" }
                        sb.append("cmd appops set --user 0 ${parts[0]} ${parts[1]} $origMode 2>/dev/null || appops set ${parts[0]} ${parts[1]} $origMode 2>/dev/null || true\n")
                    }
                }
            }
        }
        if (hasPermission() && sb.isNotEmpty()) {
            execShizuku(sb.toString())
        }
    }
}
