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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 免 Root / 免 CA 证书的局部主动拒止 VPN 防漂移通道（IPv4 + IPv6 双栈）：
 * 1) 覆盖包括微信 (com.tencent.mm) 在内的三方应用定位 SDK 流量（仅放行定位漂移助手自身），
 *    同时剔除微信聊天长短连接域名 (PROTECTED_TENCENT_HOSTS) 的 IP 并配置 allowBypass() + 公共 DNS，
 *    确保微信正常消息收发不受任何影响；
 * 2) 仅对目标网络定位域名 IPv4 (/32) 与 IPv6 (/128) 地址添加精准主机路由，绝不劫持 0.0.0.0/0 或 ::/0；
 * 3) 收到发往定位服务器的 TCP/UDP 数据包时，在用户态直接回发 TCP RST(/ACK) 与 ICMP(v6) Port Unreachable，
 *    实现毫秒级拒止并迫使定位 SDK 立即采信系统模拟 GPS。
 */
class DriftVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_VPN"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_VPN"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private val sessionGen = AtomicInteger(0)

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
                    .addAddress("10.233.233.1", 32)
                    .addAddress("fd00:233:233::1", 128)
                    .addDnsServer("223.5.5.5")
                    .addDnsServer("119.29.29.29")
                    .addDnsServer("2400:3200::1")
                    .allowBypass()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // 仅放行定位漂移助手自身（保障内置地图选点与地理编码正常），对其余所有应用（含微信）生效
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Throwable) {
                }

                if (ipv4s.isEmpty() && ipv6s.isEmpty()) {
                    // 兜底占位路由，避免建立无路由 TUN
                    builder.addRoute("10.233.233.2", 32)
                } else {
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
                                    val reply = buildRejectReply(buf, read)
                                    if (reply != null) {
                                        try {
                                            output.write(reply)
                                        } catch (_: Throwable) {
                                        }
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

    /**
     * 结合系统解析器与直连公共 DNS (223.5.5.5 / 119.29.29.29) 同步提取域名的 IPv4 (A) 与 IPv6 (AAAA) 地址，
     * 避免运营商 4G/5G 双栈环境下定位 SDK 走 IPv6 绕过纯 IPv4 路由。
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
        // 补充直连 UDP DNS 查询，捕获更多 CDN/BGP 节点 IP
        for (dnsIp in listOf("223.5.5.5", "119.29.29.29")) {
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
            socket.soTimeout = 1200

            val labels = host.trim('.').split('.')
            val query = ArrayList<Byte>(64)
            // Transaction ID = 0x1337, Flags = 0x0100 (standard recursion)
            query.add(0x13); query.add(0x37)
            query.add(0x01); query.add(0x00)
            query.add(0x00); query.add(0x01) // QDCOUNT = 1
            query.add(0x00); query.add(0x00) // ANCOUNT = 0
            query.add(0x00); query.add(0x00) // NSCOUNT = 0
            query.add(0x00); query.add(0x00) // ARCOUNT = 0
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) return emptyList()
                query.add(bytes.size.toByte())
                for (b in bytes) query.add(b)
            }
            query.add(0x00)
            query.add(((qtype ushr 8) and 0xFF).toByte())
            query.add((qtype and 0xFF).toByte())
            query.add(0x00); query.add(0x01) // QCLASS = IN

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
