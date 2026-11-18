package com.separ.locationdrifter.service

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.separ.locationdrifter.util.RootUtils
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 免 Root / 免 CA 证书的局部主动拒止 VPN 防漂移通道（IPv4 + IPv6 双栈）：
 * 1) 仅对目标网络定位域名 IP 添加精准主机路由 (/32 与 /128)，绝不接管 0.0.0.0/0 或 ::/0 正常上网流量；
 * 2) 自动剔除与微信等高频通讯服务共享的腾讯 Anycast/CDN 边缘 IP，并将微信等通讯应用加入 VPN 绕行白名单，
 *    避免三层 IP 级 TCP RST 误伤微信 mmtls 连接导致「网络不可用」；
 * 3) 收到发往定位服务器的 TCP/UDP 数据包时，在用户态直接向 TUN 接口回发 TCP RST / ICMP Port Unreachable，
 *    还原内核 iptables -j REJECT 的毫秒级拒止体验。
 */
class DriftVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_VPN"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_VPN"

        // 避免局部 RST-VPN 误伤即时通讯与系统基础服务连接
        private val BYPASS_VPN_PACKAGES = listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.wework",
            "com.android.vending",
            "com.google.android.gms"
        )

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            startVpnAsync()
        }
        return START_STICKY
    }

    private fun startVpnAsync() {
        isRunning = true
        Thread {
            try {
                // 先解析微信核心通讯域名 IP，防止与腾讯地图 LBS 域名共享同一腾讯 BGP/CDN 节点时被误杀
                val protectedIps = HashSet<String>()
                for (host in RootUtils.PROTECTED_TENCENT_HOSTS) {
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
                    for (host in RootUtils.LBS_HOSTS) {
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

                if (!isRunning) return@Thread

                // 若当前运营商 DNS 下所有 LBS IP 均与微信核心 VIP 重叠，则跳过建立空路由 TUN，避免影响正常网络
                if (ipv4s.isEmpty() && ipv6s.isEmpty()) {
                    isRunning = false
                    stopSelf()
                    return@Thread
                }

                val builder = Builder()
                    .setSession("LocationDrifter-RST-AntiDrift")
                    .setMtu(1500)
                    .addAddress("10.233.233.1", 32)
                    .addAddress("fd00:233:233::1", 128)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                for (pkg in BYPASS_VPN_PACKAGES) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: Throwable) {
                    }
                }

                for (ip in ipv4s) {
                    try {
                        builder.addRoute(ip, 32)
                    } catch (_: Throwable) {
                    }
                }
                for (ip in ipv6s) {
                    try {
                        builder.addRoute(ip, 128)
                    } catch (_: Throwable) {
                    }
                }

                val fd = builder.establish()
                if (fd == null) {
                    isRunning = false
                    stopSelf()
                    return@Thread
                }
                vpnInterface = fd

                drainThread = Thread {
                    val buf = ByteArray(32767)
                    try {
                        val rawFd = fd.fileDescriptor
                        FileInputStream(rawFd).use { input ->
                            FileOutputStream(rawFd).use { output ->
                                while (isRunning && !Thread.currentThread().isInterrupted) {
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
                    name = "LocationDrifter-VpnRstResponder"
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
                isRunning = false
                stopSelf()
            }
        }.start()
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
                if ((tcpFlags and 0x04) != 0) return null // 忽略已带 RST 标记的包，防止环路

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
                    replyFlags = 0x04 // RST
                } else {
                    var segLen = (totalLen - ihl - tcpDataOffset).toLong()
                    if ((tcpFlags and 0x02) != 0) segLen += 1L // SYN
                    if ((tcpFlags and 0x01) != 0) segLen += 1L // FIN
                    replySeq = 0L
                    replyAck = (seqNum + segLen) and 0xFFFFFFFFL
                    replyFlags = 0x14 // RST | ACK
                }

                val out = ByteArray(40)
                out[0] = 0x45.toByte()
                writeUInt16(out, 2, 40)
                writeUInt16(out, 6, 0x4000) // DF
                out[8] = 64 // TTL
                out[9] = 6  // TCP
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
                out[9] = 1 // ICMP
                System.arraycopy(packet, 16, out, 12, 4)
                System.arraycopy(packet, 12, out, 16, 4)
                writeUInt16(out, 10, computeChecksum(out, 0, 20))

                out[20] = 3 // Destination Unreachable
                out[21] = 3 // Port Unreachable
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
                out[6] = 6  // TCP
                out[7] = 64 // Hop Limit
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
                out[6] = 58 // ICMPv6
                out[7] = 64
                System.arraycopy(packet, 24, out, 8, 16)
                System.arraycopy(packet, 8, out, 24, 16)

                out[40] = 1 // Destination Unreachable
                out[41] = 4 // Port Unreachable
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

    private fun stopVpn() {
        isRunning = false
        drainThread?.interrupt()
        drainThread = null
        try {
            vpnInterface?.close()
        } catch (_: Throwable) {
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
