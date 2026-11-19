package com.separ.locationdrifter.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
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

    fun rebootDevice(): Boolean {
        return execSu("svc power reboot || reboot").success
    }

    /**
     * 免 Root 环境下检测当前应用是否已被授予模拟位置权限
     */
    fun isMockLocationAllowed(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val uid = android.os.Process.myUid()
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    OPSTR_MOCK_LOCATION,
                    uid,
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    OPSTR_MOCK_LOCATION,
                    uid,
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
            appops set --user 0 $packageName android:mock_location allow 2>/dev/null || appops set $packageName android:mock_location allow
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
     * 高频网络定位 SDK 域名列表（覆盖微信内置腾讯定位 SDK、高德/百度网络定位接口）：
     * 当网络定位请求收到 TCP RST / ICMP Port Unreachable 后会立即回落到系统 LocationManager 虚拟 GPS。
     */
    val LBS_HOSTS = listOf(
        "lbs.map.qq.com",
        "c.map.qq.com",
        "loc.map.qq.com",
        "datalbs.map.qq.com",
        "apis.map.qq.com",
        "rtt.map.qq.com",
        "ping.map.qq.com",
        "pr.map.qq.com",
        "apilocate.amap.com",
        "cgicol.amap.com",
        "loc.map.baidu.com",
        "ofloc.map.baidu.com"
    )

    // 微信核心长/短连接及基础业务域名：避免三层 IP 拦截与微信 mmtls 节点冲突导致微信离线
    val PROTECTED_TENCENT_HOSTS = listOf(
        "szshort.weixin.qq.com",
        "szlong.weixin.qq.com",
        "szextshort.weixin.qq.com",
        "szminorshort.weixin.qq.com",
        "short.weixin.qq.com",
        "long.weixin.qq.com",
        "dns.weixin.qq.com",
        "mp.weixin.qq.com",
        "wx.qq.com",
        "res.wx.qq.com"
    )

    fun enableIptablesBlock(): Boolean {
        val protectedIps = HashSet<String>()
        for (host in PROTECTED_TENCENT_HOSTS) {
            try {
                InetAddress.getAllByName(host).forEach { addr ->
                    addr.hostAddress?.substringBefore('%')?.let { protectedIps.add(it) }
                }
            } catch (_: Throwable) {
            }
        }

        val ipv4s = LinkedHashSet<String>()
        val ipv6s = LinkedHashSet<String>()
        repeat(2) {
            for (host in LBS_HOSTS) {
                try {
                    InetAddress.getAllByName(host).forEach { addr ->
                        val rawIp = addr.hostAddress?.substringBefore('%') ?: return@forEach
                        if (rawIp in protectedIps) return@forEach
                        when (addr) {
                            is Inet4Address -> ipv4s.add(rawIp)
                            is Inet6Address -> ipv6s.add(rawIp)
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
