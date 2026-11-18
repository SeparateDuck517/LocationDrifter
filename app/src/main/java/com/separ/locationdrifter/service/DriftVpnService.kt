package com.separ.locationdrifter.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.separ.locationdrifter.util.RootUtils
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 免 Root / 免 CA 证书的局部主动拒止 VPN 防漂移通道（DNS 精准分流 + IPv4/IPv6 双栈 RST 拒止）：
 * 1) 内置本地 TUN DNS 拦截器 (10.233.233.53)：
 *    - 针对微信内置腾讯定位 SDK (*.map.qq.com 定位接口) 与高德/百度网络定位域名，直接解析至黑洞地址
 *      10.233.233.254，在用户态毫秒级回发 TCP RST / ICMP Port Unreachable，消除 GeoDNS/CDN 节点漂移漏网；
 *    - 其余全部正常域名（含微信 mmtls 聊天、朋友圈、小程序等）通过 protect() 套接字透明转发至公共 DNS；
 * 2) 同步预解析多组公共 DNS 的 IPv4 (/32) 与 IPv6 (/128) 主机路由，拦截微信启动前已缓存的定位服务器 IP；
 * 3) 剔除微信核心 mmtls IM 长短连接节点 IP 并配置 allowBypass()，确保微信消息收发 100% 畅通。
 */
class DriftVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_VPN"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_VPN"

        private const val TUN_IPV4 = "10.233.233.1"
        private const val TUN_DNS_IPV4 = "10.233.233.53"
        private const val TUN_SINKHOLE_IPV4 = "10.233.233.254"
        private const val TUN_IPV6 = "fd00:233:233::1"

        private val UPSTREAM_DNS_SERVERS = listOf(
            "223.5.5.5",
            "119.29.29.29",
            "180.76.76.76",
            "114.114.114.114"
        )

        private val dnsExecutor = Executors.newCachedThreadPool()

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private val sessionGen = AtomicInteger(0)
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startOrRestartVpnAsync()
        return START_STICKY
    }

    private fun startOrRestartVpnAsync() {
        val gen = sessionGen.incrementAndGet()
        closeCurrentInterface()
        isRunning = true

        Thread {
            try {
                // 仅排除微信核心 mmtls IM 节点 IP，避免共享 VIP 误伤微信聊天连接
                val protectedIps = HashSet<String>()
                for (host in RootUtils.PROTECTED_TENCENT_HOSTS) {
                    resolveHostIps(host).forEach { ip ->
                        protectedIps.add(ip)
                    }
                }

                val ipv4s = LinkedHashSet<String>()
                val ipv6s = LinkedHashSet<String>()
                for (host in RootUtils.LBS_HOSTS) {
                    resolveHostIps(host).forEach { rawIp ->
                        if (rawIp in protectedIps) return@forEach
                        if (rawIp.contains(':')) {
                            ipv6s.add(rawIp)
                        } else {
                            ipv4s.add(rawIp)
                        }
                    }
                }

                if (!isRunning || sessionGen.get() != gen) return@Thread

                val builder = Builder()
                    .setSession("LocationDrifter-RST-AntiDrift")
                    .setMtu(1500)
                    .addAddress(TUN_IPV4, 32)
                    .addAddress(TUN_IPV6, 128)
                    .addDnsServer(TUN_DNS_IPV4)
                    .addRoute(TUN_DNS_IPV4, 32)
                    .addRoute(TUN_SINKHOLE_IPV4, 32)
                    .allowBypass()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // 仅放行定位漂移助手自身，对其余全部应用（包括微信 com.tencent.mm）生效
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Throwable) {
                }

                for (ip in ipv4s) {
                    try {
                        builder.addRoute(ip, 32)
                    } catch (_: Throwable) {
                    }
                }
                for (ip6 in ipv6s) {
                    try {
                        builder.addRoute(ip6, 128)
                    } catch (_: Throwable) {
                    }
                }

                val fd = builder.establish()
                if (fd == null || !isRunning || sessionGen.get() != gen) {
                    try {
                        fd?.close()
                    } catch (_: Throwable) {
                    }
                    if (sessionGen.get() == gen) {
                        isRunning = false
                        stopSelf()
                    }
                    return@Thread
                }
                vpnInterface = fd

                drainThread = Thread {
                    val buf = ByteArray(32767)
                    try {
                        val rawFd = fd.fileDescriptor
                        FileInputStream(rawFd).use { input ->
                            FileOutputStream(rawFd).use { output ->
                                while (isRunning && sessionGen.get() == gen && !Thread.currentThread().isInterrupted) {
                                    val read = input.read(buf)
                                    if (read < 0) break
                                    if (read == 0) continue

                                    if (tryHandleTunDnsPacket(buf, read, output)) {
                                        continue
                                    }

                                    val reply = buildRejectReply(buf, read)
                                    if (reply != null) {
                                        writePacketSafe(output, reply)
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }.apply {
                    name = "LocationDrifter-VpnRstResponder-$gen"
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
                if (sessionGen.get() == gen) {
                    isRunning = false
                    stopSelf()
                }
            }
        }.apply {
            name = "LocationDrifter-VpnStarter-$gen"
            isDaemon = true
            start()
        }
    }

    private fun writePacketSafe(output: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(packet)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 拦截发往 10.233.233.53:53 的 UDP DNS 查询：
     * - 匹配到网络定位域名时直接合成指向 10.233.233.254 的解析记录，后续 TCP/UDP 建连立即触发 RST 拒止；
     * - 其余域名通过 protect() 套接字透明转发至上游公共 DNS。
     */
    private fun tryHandleTunDnsPacket(packet: ByteArray, length: Int, output: FileOutputStream): Boolean {
        if (length < 28) return false
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return false

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return false
        val proto = packet[9].toInt() and 0xFF
        if (proto != 17) return false // UDP

        // 检查目标 IP 是否为 10.233.233.53
        if ((packet[16].toInt() and 0xFF) != 10 ||
            (packet[17].toInt() and 0xFF) != 233 ||
            (packet[18].toInt() and 0xFF) != 233 ||
            (packet[19].toInt() and 0xFF) != 53
        ) {
            return false
        }

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return false

        val totalLen = (((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF))
            .coerceAtMost(length)
        val udpLen = (((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF))
            .coerceAtMost(totalLen - ihl)
        if (udpLen <= 8) return true

        val dnsOffset = ihl + 8
        val dnsLen = udpLen - 8
        val dnsQuery = ByteArray(dnsLen)
        System.arraycopy(packet, dnsOffset, dnsQuery, 0, dnsLen)

        val clientIp = ByteArray(4)
        System.arraycopy(packet, 12, clientIp, 0, 4)
        val serverIp = ByteArray(4)
        System.arraycopy(packet, 16, serverIp, 0, 4)
        val clientPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)

        val parsed = parseDnsQuestion(dnsQuery)
        if (parsed != null && isBlockedLbsDomain(parsed.qname)) {
            val dnsReply = buildSinkholeDnsResponse(dnsQuery, parsed.questionEndOffset, parsed.qtype)
            if (dnsReply != null) {
                val udpIpPacket = wrapIpv4UdpPacket(serverIp, 53, clientIp, clientPort, dnsReply)
                writePacketSafe(output, udpIpPacket)
            }
            return true
        }

        dnsExecutor.execute {
            val upstreamReply = forwardDnsQueryUpstream(dnsQuery)
            if (upstreamReply != null && isRunning) {
                val udpIpPacket = wrapIpv4UdpPacket(serverIp, 53, clientIp, clientPort, upstreamReply)
                writePacketSafe(output, udpIpPacket)
            }
        }
        return true
    }

    private data class DnsQuestion(
        val qname: String,
        val qtype: Int,
        val questionEndOffset: Int
    )

    private fun parseDnsQuestion(query: ByteArray): DnsQuestion? {
        if (query.size < 17) return null
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val sb = StringBuilder()
        var idx = 12
        var steps = 0
        while (idx < query.size && steps < 64) {
            val labelLen = query[idx].toInt() and 0xFF
            if (labelLen == 0) {
                idx++
                break
            }
            if ((labelLen and 0xC0) != 0) return null
            idx++
            if (idx + labelLen > query.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(query, idx, labelLen, Charsets.US_ASCII))
            idx += labelLen
            steps++
        }
        if (idx + 4 > query.size) return null
        val qtype = ((query[idx].toInt() and 0xFF) shl 8) or (query[idx + 1].toInt() and 0xFF)
        return DnsQuestion(
            qname = sb.toString().lowercase(Locale.US).trimEnd('.'),
            qtype = qtype,
            questionEndOffset = idx + 4
        )
    }

    private fun isBlockedLbsDomain(qname: String): Boolean {
        if (qname.isEmpty()) return false
        if (qname in RootUtils.LBS_HOSTS) return true
        // 拦截腾讯地图定位相关全部子域名，但放行 vectorsdk.map.qq.com（矢量底图样式）
        if (qname.endsWith(".map.qq.com") && qname != "vectorsdk.map.qq.com") {
            return true
        }
        return false
    }

    private fun buildSinkholeDnsResponse(query: ByteArray, questionEnd: Int, qtype: Int): ByteArray? {
        if (questionEnd > query.size) return null
        val includeARecord = (qtype == 1)
        val answerLen = if (includeARecord) 16 else 0
        val out = ByteArray(questionEnd + answerLen)

        // Transaction ID
        out[0] = query[0]
        out[1] = query[1]
        // Standard response, recursion available, NOERROR
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        // QDCOUNT = 1
        out[4] = 0x00
        out[5] = 0x01
        // ANCOUNT = 1 (for A) or 0 (for AAAA/HTTPS so client immediately falls back to IPv4)
        out[6] = 0x00
        out[7] = if (includeARecord) 0x01 else 0x00
        // NSCOUNT = 0, ARCOUNT = 0
        out[8] = 0x00; out[9] = 0x00
        out[10] = 0x00; out[11] = 0x00

        System.arraycopy(query, 12, out, 12, questionEnd - 12)

        if (includeARecord) {
            var p = questionEnd
            out[p++] = 0xC0.toByte()
            out[p++] = 0x0C // Pointer to QNAME at offset 12
            out[p++] = 0x00
            out[p++] = 0x01 // TYPE A
            out[p++] = 0x00
            out[p++] = 0x01 // CLASS IN
            out[p++] = 0x00
            out[p++] = 0x00
            out[p++] = 0x00
            out[p++] = 0x01 // TTL = 1s
            out[p++] = 0x00
            out[p++] = 0x04 // RDLENGTH = 4
            // 10.233.233.254
            out[p++] = 10
            out[p++] = 233.toByte()
            out[p++] = 233.toByte()
            out[p] = 254.toByte()
        }
        return out
    }

    private fun forwardDnsQueryUpstream(query: ByteArray): ByteArray? {
        for (dnsIp in listOf("223.5.5.5", "119.29.29.29")) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 1500
                val addr = InetAddress.getByName(dnsIp)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                if (pkt.length >= 12) {
                    val result = ByteArray(pkt.length)
                    System.arraycopy(buf, 0, result, 0, pkt.length)
                    return result
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun wrapIpv4UdpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        writeUInt16(out, 2, totalLen)
        writeUInt16(out, 6, 0x4000)
        out[8] = 64
        out[9] = 17 // UDP
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        writeUInt16(out, 10, computeChecksum(out, 0, 20))

        writeUInt16(out, 20, srcPort)
        writeUInt16(out, 22, dstPort)
        writeUInt16(out, 24, udpLen)
        System.arraycopy(payload, 0, out, 28, payload.size)
        writeUInt16(out, 26, computeIpv4TransportChecksum(out, 12, 16, 17, 20, udpLen))
        return out
    }

    /**
     * 结合系统解析器与多组直连公共 DNS 同步提取域名的 IPv4 (A) 与 IPv6 (AAAA) 地址
     */
    private fun resolveHostIps(host: String): Set<String> {
        val results = LinkedHashSet<String>()
        repeat(2) {
            try {
                InetAddress.getAllByName(host).forEach { addr ->
                    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress) {
                        return@forEach
                    }
                    val raw = addr.hostAddress?.substringBefore('%') ?: return@forEach
                    if (addr is Inet4Address || addr is Inet6Address) {
                        results.add(raw)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        for (dnsIp in UPSTREAM_DNS_SERVERS) {
            results.addAll(queryDnsRecords(host, dnsIp, 1))  // A
            results.addAll(queryDnsRecords(host, dnsIp, 28)) // AAAA
        }
        return results
    }

    private fun queryDnsRecords(host: String, dnsServer: String, qtype: Int): List<String> {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 1000

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
            query.add(((qtype ushr 8) and 0xFF).toByte())
            query.add((qtype and 0xFF).toByte())
            query.add(0x00); query.add(0x01)

            val qBytes = query.toByteArray()
            val serverAddr = InetAddress.getByName(dnsServer)
            socket.send(DatagramPacket(qBytes, qBytes.size, serverAddr, 53))

            val respBuf = ByteArray(1024)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            parseDnsResponse(respBuf, respPacket.length, qtype)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun parseDnsResponse(buf: ByteArray, len: Int, expectedType: Int): List<String> {
        if (len < 12) return emptyList()
        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        var offset = 12

        fun skipName(start: Int): Int {
            var idx = start
            var steps = 0
            while (idx < len && steps < 128) {
                val b = buf[idx].toInt() and 0xFF
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
            val type = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            val rdLen = ((buf[offset + 8].toInt() and 0xFF) shl 8) or (buf[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLen > len) return out

            if (type == expectedType) {
                if (expectedType == 1 && rdLen == 4) {
                    val raw = ByteArray(4)
                    System.arraycopy(buf, offset, raw, 0, 4)
                    val addr = InetAddress.getByAddress(raw)
                    if (!addr.isLoopbackAddress && !addr.isAnyLocalAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                } else if (expectedType == 28 && rdLen == 16) {
                    val raw = ByteArray(16)
                    System.arraycopy(buf, offset, raw, 0, 16)
                    val addr = InetAddress.getByAddress(raw)
                    if (!addr.isLoopbackAddress && !addr.isAnyLocalAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.substringBefore('%')?.let { out.add(it) }
                    }
                }
            }
            offset += rdLen
        }
        return out
    }

    /**
     * 解析发往定位域名黑洞路由的 IPv4 / IPv6 数据包，构造符合 RFC 793 / 792 / 4443 的
     * TCP RST(/ACK) 或 ICMP(v6) Port Unreachable 响应包，实现免 Root 秒级连接拒止。
     */
    private fun buildRejectReply(packet: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> buildIpv4Reject(packet, length)
            6 -> buildIpv6Reject(packet, length)
            else -> null
        }
    }

    private fun buildIpv4Reject(packet: ByteArray, length: Int): ByteArray? {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        val totalLen = (((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF))
            .coerceAtMost(length)
        val fragOffset = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragOffset != 0) return null

        val proto = packet[9].toInt() and 0xFF
        return when (proto) {
            6 -> { // TCP -> RST / RST+ACK
                if (totalLen < ihl + 20) return null
                val tcpFlags = packet[ihl + 13].toInt() and 0xFF
                if ((tcpFlags and 0x04) != 0) return null

                val tcpDataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
                if (tcpDataOffset < 20 || totalLen < ihl + tcpDataOffset) return null

                val seqNum = readUInt32(packet, ihl + 4)
                val ackNum = readUInt32(packet, ihl + 8)
                val replySeq: Long
                val replyAck: Long
                val replyFlags: Int

                if ((tcpFlags and 0x10) != 0) {
                    replySeq = ackNum
                    replyAck = 0L
                    replyFlags = 0x04
                } else {
                    var segLen = (totalLen - ihl - tcpDataOffset).toLong()
                    if ((tcpFlags and 0x02) != 0) segLen += 1L
                    if ((tcpFlags and 0x01) != 0) segLen += 1L
                    replySeq = 0L
                    replyAck = (seqNum + segLen) and 0xFFFFFFFFL
                    replyFlags = 0x14
                }

                val out = ByteArray(40)
                out[0] = 0x45.toByte()
                writeUInt16(out, 2, 40)
                writeUInt16(out, 6, 0x4000)
                out[8] = 64
                out[9] = 6
                System.arraycopy(packet, 16, out, 12, 4)
                System.arraycopy(packet, 12, out, 16, 4)
                writeUInt16(out, 10, computeChecksum(out, 0, 20))

                System.arraycopy(packet, ihl + 2, out, 20, 2)
                System.arraycopy(packet, ihl, out, 22, 2)
                writeUInt32(out, 24, replySeq)
                writeUInt32(out, 28, replyAck)
                out[32] = 0x50.toByte()
                out[33] = replyFlags.toByte()
                writeUInt16(out, 36, computeIpv4TransportChecksum(out, 12, 16, 6, 20, 20))
                out
            }
            17 -> { // UDP -> ICMP Type 3 Code 3 (Port Unreachable)
                if (totalLen < ihl + 8) return null
                val quotedLen = (ihl + 8).coerceAtMost(totalLen)
                val replyTotalLen = 20 + 8 + quotedLen
                val out = ByteArray(replyTotalLen)
                out[0] = 0x45.toByte()
                writeUInt16(out, 2, replyTotalLen)
                out[8] = 64
                out[9] = 1
                System.arraycopy(packet, 16, out, 12, 4)
                System.arraycopy(packet, 12, out, 16, 4)
                writeUInt16(out, 10, computeChecksum(out, 0, 20))

                out[20] = 3
                out[21] = 3
                System.arraycopy(packet, 0, out, 28, quotedLen)
                writeUInt16(out, 22, computeChecksum(out, 20, 8 + quotedLen))
                out
            }
            else -> null
        }
    }

    private fun buildIpv6Reject(packet: ByteArray, length: Int): ByteArray? {
        if (length < 40) return null
        val payloadLen = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val totalLen = (40 + payloadLen).coerceAtMost(length)
        val nextHeader = packet[6].toInt() and 0xFF

        return when (nextHeader) {
            6 -> { // TCP -> RST / RST+ACK
                if (totalLen < 60) return null
                val tcpFlags = packet[53].toInt() and 0xFF
                if ((tcpFlags and 0x04) != 0) return null

                val tcpDataOffset = ((packet[52].toInt() ushr 4) and 0x0F) * 4
                if (tcpDataOffset < 20 || totalLen < 40 + tcpDataOffset) return null

                val seqNum = readUInt32(packet, 44)
                val ackNum = readUInt32(packet, 48)
                val replySeq: Long
                val replyAck: Long
                val replyFlags: Int

                if ((tcpFlags and 0x10) != 0) {
                    replySeq = ackNum
                    replyAck = 0L
                    replyFlags = 0x04
                } else {
                    var segLen = (totalLen - 40 - tcpDataOffset).toLong()
                    if ((tcpFlags and 0x02) != 0) segLen += 1L
                    if ((tcpFlags and 0x01) != 0) segLen += 1L
                    replySeq = 0L
                    replyAck = (seqNum + segLen) and 0xFFFFFFFFL
                    replyFlags = 0x14
                }

                val out = ByteArray(60)
                out[0] = 0x60.toByte()
                writeUInt16(out, 4, 20)
                out[6] = 6
                out[7] = 64
                System.arraycopy(packet, 24, out, 8, 16)
                System.arraycopy(packet, 8, out, 24, 16)

                System.arraycopy(packet, 42, out, 40, 2)
                System.arraycopy(packet, 40, out, 42, 2)
                writeUInt32(out, 44, replySeq)
                writeUInt32(out, 48, replyAck)
                out[52] = 0x50.toByte()
                out[53] = replyFlags.toByte()
                writeUInt16(out, 56, computeIpv6TransportChecksum(out, 8, 24, 6, 40, 20))
                out
            }
            17 -> { // UDP -> ICMPv6 Type 1 Code 4 (Port Unreachable)
                if (totalLen < 48) return null
                val quotedLen = totalLen.coerceAtMost(1232)
                val icmpLen = 8 + quotedLen
                val out = ByteArray(40 + icmpLen)
                out[0] = 0x60.toByte()
                writeUInt16(out, 4, icmpLen)
                out[6] = 58
                out[7] = 64
                System.arraycopy(packet, 24, out, 8, 16)
                System.arraycopy(packet, 8, out, 24, 16)

                out[40] = 1
                out[41] = 4
                System.arraycopy(packet, 0, out, 48, quotedLen)
                writeUInt16(out, 42, computeIpv6TransportChecksum(out, 8, 24, 58, 40, icmpLen))
                out
            }
            else -> null
        }
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL)
    }

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFFL).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        buf[offset + 3] = (value and 0xFFL).toByte()
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = sumWords(buf, offset, length)
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeIpv4TransportChecksum(
        buf: ByteArray,
        srcOffset: Int,
        dstOffset: Int,
        proto: Int,
        payloadOffset: Int,
        payloadLen: Int
    ): Int {
        var sum = 0
        sum += sumWords(buf, srcOffset, 4)
        sum += sumWords(buf, dstOffset, 4)
        sum += proto and 0xFF
        sum += payloadLen and 0xFFFF
        sum += sumWords(buf, payloadOffset, payloadLen)
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeIpv6TransportChecksum(
        buf: ByteArray,
        srcOffset: Int,
        dstOffset: Int,
        nextHeader: Int,
        payloadOffset: Int,
        payloadLen: Int
    ): Int {
        var sum = 0
        sum += sumWords(buf, srcOffset, 16)
        sum += sumWords(buf, dstOffset, 16)
        sum += (payloadLen ushr 16) and 0xFFFF
        sum += payloadLen and 0xFFFF
        sum += nextHeader and 0xFF
        sum += sumWords(buf, payloadOffset, payloadLen)
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun sumWords(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun closeCurrentInterface() {
        drainThread?.interrupt()
        drainThread = null
        try {
            vpnInterface?.close()
        } catch (_: Throwable) {
        }
        vpnInterface = null
    }

    private fun stopVpn() {
        sessionGen.incrementAndGet()
        isRunning = false
        closeCurrentInterface()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
