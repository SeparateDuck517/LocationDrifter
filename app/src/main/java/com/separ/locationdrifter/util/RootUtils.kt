package com.separ.locationdrifter.util

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.xposed.ModuleStatus
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root (su) 命令执行与环境权限检测工具：
 * 1) 合并批量命令单次 su 执行，消除重复进程拉起开销；
 * 2) 并行收割网络定位域名边缘 IP 并本地缓存，大幅缩短开启/切换延迟。
 */
object RootUtils {

    private const val OPSTR_MOCK_LOCATION = "android:mock_location"
    private const val PROBE_PROVIDER = "location_drifter_probe"
    private const val DNS_CACHE_TTL_MS = 180_000L

    private val rootTaskExecutor = Executors.newSingleThreadExecutor()
    private val dnsResolvePool = Executors.newCachedThreadPool()

    @Volatile
    private var cachedLbsIps: Pair<Set<String>, Set<String>>? = null

    @Volatile
    private var lastDnsResolveTimeMs = 0L

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

    fun isSystemServerHookActive(context: Context): Boolean {
        if (!ModuleStatus.isModuleActive()) return false
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                if (lm.isProviderEnabled(PROBE_PROVIDER)) return true
                if (lm.sendExtraCommand(PROBE_PROVIDER, "ping", Bundle())) return true
            }
        } catch (_: Throwable) {
        }
        return try {
            val currentBoot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
            val hookedBoot = Settings.Global.getInt(
                context.contentResolver,
                ConfigManager.SETTINGS_GLOBAL_HOOKED_BOOT_KEY,
                -2
            )
            currentBoot >= 0 && hookedBoot == currentBoot
        } catch (_: Throwable) {
            false
        }
    }

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

    private fun buildWriteTmpConfigScript(jsonText: String): String {
        val escaped = jsonText.replace("'", "'\\''")
        return """
            settings put global location_drifter_cfg '$escaped' 2>/dev/null || true
            printf '%s' '$escaped' > /data/local/tmp/location_drifter.json
            chmod 644 /data/local/tmp/location_drifter.json
            chcon u:object_r:system_file:s0 /data/local/tmp/location_drifter.json 2>/dev/null || true
        """.trimIndent()
    }

    fun writeTmpConfigJson(jsonText: String): Boolean {
        return execSu(buildWriteTmpConfigScript(jsonText)).success
    }

    private const val IPTABLES_CHAIN = "LOC_DRIFT_BLOCK"

    val LBS_HOSTS = listOf(
        "lbs.map.qq.com",
        "loc.map.qq.com",
        "c.map.qq.com",
        "datalbs.map.qq.com",
        "apilocate.amap.com",
        "cgicol.amap.com",
        "loc.map.baidu.com",
        "ofloc.map.baidu.com"
    )

    val PROTECTED_IM_HOSTS = listOf(
        "szshort.weixin.qq.com",
        "szlong.weixin.qq.com",
        "szextshort.weixin.qq.com",
        "szminorshort.weixin.qq.com",
        "extshort.weixin.qq.com",
        "minorshort.weixin.qq.com",
        "short.weixin.qq.com",
        "long.weixin.qq.com",
        "dns.weixin.qq.com"
    )

    private fun queryHttpDns(host: String): List<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://119.29.29.29/d?dn=$host").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 500
                readTimeout = 500
            }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText().trim()
                }
                body.split(';', ',')
                    .map { it.trim() }
                    .filter { it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) }
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private fun queryUdpDnsA(host: String, dnsServer: String): List<String> {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = 450
            val labels = host.trim('.').split('.')
            val query = ArrayList<Byte>(64)
            query.add(0x13); query.add(0x37)
            query.add(0x01); query.add(0x00)
            query.add(0x00); query.add(0x01)
            query.add(0x00); query.add(0x00)
            query.add(0x00); query.add(0x00)
            query.add(0x00); query.add(0x00)
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) return emptyList()
                query.add(bytes.size.toByte())
                for (b in bytes) query.add(b)
            }
            query.add(0x00)
            query.add(0x00); query.add(0x01)
            query.add(0x00); query.add(0x01)

            val qBytes = query.toByteArray()
            socket.send(DatagramPacket(qBytes, qBytes.size, InetAddress.getByName(dnsServer), 53))
            val resp = ByteArray(1024)
            val pkt = DatagramPacket(resp, resp.size)
            socket.receive(pkt)
            val len = pkt.length
            if (len < 12) return emptyList()

            val qdCount = ((resp[4].toInt() and 0xFF) shl 8) or (resp[5].toInt() and 0xFF)
            val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
            var offset = 12
            fun skipName(start: Int): Int {
                var idx = start
                var steps = 0
                while (idx < len && steps < 128) {
                    val b = resp[idx].toInt() and 0xFF
                    if (b == 0) return idx + 1
                    if ((b and 0xC0) == 0xC0) return idx + 2
                    idx += 1 + b
                    steps++
                }
                return -1
            }
            repeat(qdCount) {
                offset = skipName(offset)
                if (offset < 0 || offset + 4 > len) return emptyList()
                offset += 4
            }
            val out = ArrayList<String>()
            repeat(anCount) {
                offset = skipName(offset)
                if (offset < 0 || offset + 10 > len) return out
                val type = ((resp[offset].toInt() and 0xFF) shl 8) or (resp[offset + 1].toInt() and 0xFF)
                val rdLen = ((resp[offset + 8].toInt() and 0xFF) shl 8) or (resp[offset + 9].toInt() and 0xFF)
                offset += 10
                if (offset + rdLen > len) return out
                if (type == 1 && rdLen == 4) {
                    val raw = ByteArray(4)
                    System.arraycopy(resp, offset, raw, 0, 4)
                    InetAddress.getByAddress(raw).hostAddress?.let { out.add(it) }
                }
                offset += rdLen
            }
            out
        } catch (_: Throwable) {
            emptyList()
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 并行解析全部网络定位服务器边缘 IP（严格限制总耗时 <= 650ms 并缓存 3 分钟）
     */
    private fun resolveBlockedIpsParallel(): Pair<Set<String>, Set<String>> {
        val now = System.currentTimeMillis()
        cachedLbsIps?.let { cached ->
            if (now - lastDnsResolveTimeMs < DNS_CACHE_TTL_MS && (cached.first.isNotEmpty() || cached.second.isNotEmpty())) {
                return cached
            }
        }

        val protectedFutures = PROTECTED_IM_HOSTS.map { host ->
            dnsResolvePool.submit(Callable {
                runCatching {
                    InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }
                }.getOrDefault(emptyList())
            })
        }

        val lbsFutures = LBS_HOSTS.map { host ->
            dnsResolvePool.submit(Callable {
                val ips = ArrayList<String>()
                runCatching {
                    InetAddress.getAllByName(host).forEach { addr ->
                        addr.hostAddress?.substringBefore('%')?.let { ips.add(it) }
                    }
                }
                ips.addAll(queryUdpDnsA(host, "223.5.5.5"))
                ips.addAll(queryHttpDns(host))
                ips
            })
        }

        val deadline = System.currentTimeMillis() + 650L
        val protectedIps = HashSet<String>()
        for (f in protectedFutures) {
            val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(30L)
            runCatching { f.get(remain, TimeUnit.MILLISECONDS) }.getOrNull()?.let {
                protectedIps.addAll(it)
            }
        }

        val ipv4s = LinkedHashSet<String>()
        val ipv6s = LinkedHashSet<String>()
        for (f in lbsFutures) {
            val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(30L)
            val list = runCatching { f.get(remain, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
            for (rawIp in list) {
                if (rawIp in protectedIps) continue
                if (rawIp.contains(':')) {
                    ipv6s.add(rawIp)
                } else {
                    ipv4s.add(rawIp)
                }
            }
        }

        val result = ipv4s to ipv6s
        if (ipv4s.isNotEmpty() || ipv6s.isNotEmpty()) {
            cachedLbsIps = result
            lastDnsResolveTimeMs = System.currentTimeMillis()
        }
        return result
    }

    /**
     * 单次 su 批量同步配置并更新 iptables 阻断规则，避免多次拉起 su 进程造成启动卡顿
     */
    fun applyRootConfigAndIptables(jsonText: String, enableBlock: Boolean): Boolean {
        val sb = StringBuilder()
        sb.append(buildWriteTmpConfigScript(jsonText)).append('\n')
        appendCleanup(sb)
        if (enableBlock) {
            val (ipv4s, ipv6s) = resolveBlockedIpsParallel()
            appendFilterDropRules(sb, "iptables -w", ipv4s)
            appendFilterDropRules(sb, "ip6tables -w", ipv6s)
        }
        return execSu(sb.toString()).success
    }

    fun enableIptablesBlock(): Boolean {
        val (ipv4s, ipv6s) = resolveBlockedIpsParallel()
        val sb = StringBuilder()
        appendCleanup(sb)
        appendFilterDropRules(sb, "iptables -w", ipv4s)
        appendFilterDropRules(sb, "ip6tables -w", ipv6s)
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

    private fun appendFilterDropRules(
        sb: StringBuilder,
        bin: String,
        ips: Set<String>
    ) {
        if (ips.isEmpty()) return
        sb.append("$bin -t filter -N $IPTABLES_CHAIN 2>/dev/null || true\n")
        sb.append("$bin -t filter -F $IPTABLES_CHAIN 2>/dev/null || true\n")
        for (port in listOf(53, 853, 1053)) {
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -p udp --dport $port -j RETURN\n")
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -p tcp --dport $port -j RETURN\n")
        }
        for (ip in ips) {
            sb.append("$bin -t filter -A $IPTABLES_CHAIN -d $ip -j DROP\n")
        }
        sb.append("$bin -t filter -I OUTPUT 1 -j $IPTABLES_CHAIN\n")
    }

    fun disableIptablesBlock(): Boolean {
        val sb = StringBuilder()
        appendCleanup(sb)
        return execSu(sb.toString()).success
    }
}
