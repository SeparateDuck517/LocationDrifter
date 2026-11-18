package com.separ.locationdrifter.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Root (su) 命令执行与环境权限检测工具
 * 所有异步 Root 任务统一通过单线程队列串行调度，消除并发 su 进程竞争。
 */
object RootUtils {

    private const val OPSTR_MOCK_LOCATION = "android:mock_location"
    private val rootTaskExecutor = Executors.newSingleThreadExecutor()

    fun runRootTask(task: () -> Unit) {
        rootTaskExecutor.execute(task)
    }

    data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String
    )

    fun isRootAvailable(): Boolean {
        return execSu("id").let { it.success && it.stdout.contains("uid=0") }
    }

    /**
     * 免 Root 环境下检测当前应用是否已在「开发者选项 -> 选择模拟位置信息应用」中被授权
     */
    fun isMockLocationAllowed(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun execSu(command: String): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su").start()
            process.outputStream.use { os ->
                os.write(command.toByteArray(Charsets.UTF_8))
                os.write("\nexit\n".toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { it.readText() }
            val code = process.waitFor()
            CommandResult(code == 0, stdout.trim(), stderr.trim())
        } catch (t: Throwable) {
            CommandResult(false, "", t.message ?: "su failed")
        } finally {
            process?.destroy()
        }
    }

    /**
     * 免手动去开发者选项设置模拟位置应用，并授予后台定位与安全设置权限
     */
    fun grantMockAndLocationPermissions(packageName: String): Boolean {
        val cmd = """
            appops set $packageName android:mock_location allow
            pm grant $packageName android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
            pm grant $packageName android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
            pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null || true
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
        """.trimIndent()
        return execSu(cmd).success
    }

    /**
     * 将 JSON 同步写入 Settings.Global 与 /data/local/tmp/location_drifter.json 作为跨进程兜底配置
     */
    fun writeTmpConfigJson(jsonText: String): Boolean {
        val escaped = jsonText.replace("'", "'\\''")
        val cmd = """
            settings put global location_drifter_cfg '$escaped' 2>/dev/null || true
            printf '%s' '$escaped' > /data/local/tmp/location_drifter.json
            chmod 644 /data/local/tmp/location_drifter.json
            chcon u:object_r:system_file:s0 /data/local/tmp/location_drifter.json 2>/dev/null || true
        """.trimIndent()
        return execSu(cmd).success
    }

    private const val IPTABLES_CHAIN = "LOC_DRIFT_BLOCK"

    /**
     * 仅针对高频网络定位域名下发内核 REJECT 规则：
     * 1) 当网络定位请求在内核层收到 REJECT 响应后会立即回落到系统 LocationManager。
     * 2) 不在内核层一刀切阻断在线逆地理编码接口 (如 isNeedAddress=true 场景)，
     *    避免强依赖服务端逆地理编码的定位 SDK 因连接被拒直接返回网络异常错误码；
     *    其余网络定位 SDK 的防漂移由系统层 Wi-Fi/基站/ServiceState 源头隔离保障。
     */
    val LBS_HOSTS = listOf(
        "lbs.map.qq.com",
        "c.map.qq.com",
        "loc.map.qq.com",
        "datalbs.map.qq.com"
    )

    fun enableIptablesBlock(): Boolean {
        val ipv4s = LinkedHashSet<String>()
        val ipv6s = LinkedHashSet<String>()
        repeat(2) {
            for (host in LBS_HOSTS) {
                try {
                    InetAddress.getAllByName(host).forEach { addr ->
                        when (addr) {
                            is Inet4Address -> addr.hostAddress?.let { ipv4s.add(it) }
                            is Inet6Address -> addr.hostAddress?.substringBefore('%')?.let { ipv6s.add(it) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        val sb = StringBuilder()
        appendCleanup(sb)
        appendFilterRejectRules(sb, "iptables -w", "icmp-port-unreachable", ipv4s)
        appendFilterRejectRules(sb, "ip6tables -w", "icmp6-port-unreachable", ipv6s)
        return execSu(sb.toString()).success
    }

    private fun appendCleanup(sb: StringBuilder) {
        for (bin in listOf("iptables -w", "ip6tables -w")) {
            for (table in listOf("mangle", "filter")) {
                sb.append("while $bin -t $table -D OUTPUT -j $IPTABLES_CHAIN 2>/dev/null; do :; done\n")
                sb.append("$bin -t $table -F $IPTABLES_CHAIN 2>/dev/null || true\n")
                sb.append("$bin -t $table -X $IPTABLES_CHAIN 2>/dev/null || true\n")
            }
        }
    }

    private fun appendFilterRejectRules(
        sb: StringBuilder,
        bin: String,
        icmpReject: String,
        ips: Set<String>
    ) {
        sb.append("$bin -t filter -N $IPTABLES_CHAIN 2>/dev/null || true\n")
        sb.append("$bin -t filter -F $IPTABLES_CHAIN 2>/dev/null || true\n")
        // 放行标准 DNS / 私人 DNS (DoT 853) / 本地代理 DNS，避免域名解析本身被误伤
        for (port in listOf(53, 853, 1053)) {
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -p udp --dport $port -j RETURN 2>/dev/null || true\n")
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -p tcp --dport $port -j RETURN 2>/dev/null || true\n")
        }

        for (host in LBS_HOSTS) {
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -p tcp -m string --algo bm --string \"$host\" -j REJECT --reject-with tcp-reset 2>/dev/null || true\n")
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -p udp -m string --algo bm --string \"$host\" -j REJECT --reject-with $icmpReject 2>/dev/null || true\n")
        }
        for (ip in ips) {
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -d $ip -p tcp -j REJECT --reject-with tcp-reset 2>/dev/null || true\n")
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -d $ip -j REJECT --reject-with $icmpReject 2>/dev/null || true\n")
        }
        sb.append("$bin -t filter -I OUTPUT 1 -j $IPTABLES_CHAIN 2>/dev/null || true\n")
    }

    fun disableIptablesBlock(): Boolean {
        val sb = StringBuilder()
        appendCleanup(sb)
        return execSu(sb.toString()).success
    }
}
