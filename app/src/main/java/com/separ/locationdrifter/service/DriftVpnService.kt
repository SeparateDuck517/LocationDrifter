package com.separ.locationdrifter.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.util.RootUtils
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 免 Root / 免 CA 证书的局部 DNS 分流 + 主动 RST 拒止 VPN 通道：
 * 1) 仅路由本地虚拟 DNS (10.233.233.53 / fd00:233:233::53) 与黑洞地址 (10.233.233.254 / fd00:233:233::254)，
 *    绝不添加任何外部公网 IP 路由，避免误伤与定位接口共享腾讯云 BGP Anycast VIP 的微信 mmtls 聊天节点；
 * 2) 启用 allowBypass() 并支持 IPv4 + IPv6 双栈上游 DNS 透明转发，保证微信聊天、地图加载与正常上网 100% 畅通；
 * 3) 仅拦截纯网络定位接口域名 (lbs/loc/c/datalbs.map.qq.com 等)，返回黑洞地址并回发 TCP RST / ICMP 不可达。
 */
class DriftVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_VPN"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_VPN"

        private const val TUN_IPV4 = "10.233.233.1"
        private const val TUN_DNS_IPV4 = "10.233.233.53"
        private const val TUN_SINKHOLE_IPV4 = "10.233.233.254"

        private const val TUN_IPV6 = "fd00:233:233::1"
        private const val TUN_DNS_IPV6 = "fd00:233:233::53"
        private const val TUN_SINKHOLE_IPV6 = "fd00:233:233::254"

        private val UPSTREAM_DNS_SERVERS = listOf(
            "223.5.5.5",
            "119.29.29.29",
            "180.76.76.76",
            "2400:3200::1",
            "2402:4e00::"
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
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val cfg = ConfigManager.load(this)
            if (cfg.enabled) {
                ConfigManager.save(this, cfg.copy(enabled = false), syncTmpWithRoot = false)
            }
        } catch (_: Throwable) {
        }
        stopVpn()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startOrRestartVpnAsync() {
        val gen = sessionGen.incrementAndGet()
        closeCurrentInterface()
        isRunning = true

        Thread {
            try {
                if (!isRunning || sessionGen.get() != gen) return@Thread

                val builder = Builder()
                    .setSession("LocationDrifter-DNS-AntiDrift")
                    .setMtu(1500)
                    .addAddress(TUN_IPV4, 32)
                    .addAddress(TUN_IPV6, 128)
                    .addDnsServer(TUN_DNS_IPV4)
                    .addDnsServer(TUN_DNS_IPV6)
                    .addRoute(TUN_DNS_IPV4, 32)
                    .addRoute(TUN_SINKHOLE_IPV4, 32)
                    .addRoute(TUN_DNS_IPV6, 128)
                    .addRoute(TUN_SINKHOLE_IPV6, 128)
                    .allowBypass()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // 仅放行定位漂移助手自身，确保内置地图搜索与逆地理编码正常
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Throwable) {
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
                    name = "LocationDrifter-VpnDnsResponder-$gen"
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

    private fun tryHandleTunDnsPacket(packet: ByteArray, length: Int, output: FileOutputStream): Boolean {
        if (length < 28) return false
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> handleIpv4DnsPacket(packet, length, output)
            6 -> handleIpv6DnsPacket(packet, length, output)
            else -> false
        }
    }

    private fun handleIpv4DnsPacket(packet: ByteArray, length: Int, output: FileOutputStream): Boolean {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return false
        if ((packet[9].toInt() and 0xFF) != 17) return false // UDP

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

        val dnsLen = udpLen - 8
        val dnsQuery = ByteArray(dnsLen)
        System.arraycopy(packet, ihl + 8, dnsQuery, 0, dnsLen)

        val clientIp = ByteArray(4)
        System.arraycopy(packet, 12, clientIp, 0, 4)
        val serverIp = ByteArray(4)
        System.arraycopy(packet, 16, serverIp, 0, 4)
        val clientPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)

        val parsed = parseDnsQuestion(dnsQuery)
        if (parsed != null && isBlockedLbsDomain(parsed.qname)) {
            val dnsReply = buildSinkholeDnsResponse(dnsQuery, parsed.questionEndOffset, parsed.qtype)
            if (dnsReply != null) {
                writePacketSafe(output, wrapIpv4UdpPacket(serverIp, 53, clientIp, clientPort, dnsReply))
            }
            return true
        }

        dnsExecutor.execute {
            val upstreamReply = forwardDnsQueryUpstream(dnsQuery)
            if (upstreamReply != null && isRunning) {
                writePacketSafe(output, wrapIpv4UdpPacket(serverIp, 53, clientIp, clientPort, upstreamReply))
            }
        }
        return true
    }

    private fun handleIpv6DnsPacket(packet: ByteArray, length: Int, output: FileOutputStream): Boolean {
        if (length < 48) return false
        if ((packet[6].toInt() and 0xFF) != 17) return false // UDP

        val expectedDst = byteArrayOf(
            0xFD.toByte(), 0x00, 0x02, 0x33, 0x02, 0x33, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x53
        )
        for (i in 0 until 16) {
            if (packet[24 + i] != expectedDst[i]) return false
        }

        val dstPort = ((packet[42].toInt() and 0xFF) shl 8) or (packet[43].toInt() and 0xFF)
        if (dstPort != 53) return false

        val payloadLen = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val totalLen = (40 + payloadLen).coerceAtMost(length)
        val udpLen = (((packet[44].toInt() and 0xFF) shl 8) or (packet[45].toInt() and 0xFF))
            .coerceAtMost(totalLen - 40)
        if (udpLen <= 8) return true

        val dnsLen = udpLen - 8
        val dnsQuery = ByteArray(dnsLen)
        System.arraycopy(packet, 48, dnsQuery, 0, dnsLen)

        val clientIp = ByteArray(16)
        System.arraycopy(packet, 8, clientIp, 0, 16)
        val serverIp = ByteArray(16)
        System.arraycopy(packet, 24, serverIp, 0, 16)
        val clientPort = ((packet[40].toInt() and 0xFF) shl 8) or (packet[41].toInt() and 0xFF)

        val parsed = parseDnsQuestion(dnsQuery)
        if (parsed != null && isBlockedLbsDomain(parsed.qname)) {
            val dnsReply = buildSinkholeDnsResponse(dnsQuery, parsed.questionEndOffset, parsed.qtype)
            if (dnsReply != null) {
                writePacketSafe(output, wrapIpv6UdpPacket(serverIp, 53, clientIp, clientPort, dnsReply))
            }
            return true
        }

        dnsExecutor.execute {
            val upstreamReply = forwardDnsQueryUpstream(dnsQuery)
            if (upstreamReply != null && isRunning) {
                writePacketSafe(output, wrapIpv6UdpPacket(serverIp, 53, clientIp, clientPort, upstreamReply))
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
        return qname.isNotEmpty() && qname in RootUtils.LBS_HOSTS
    }

    private fun buildSinkholeDnsResponse(query: ByteArray, questionEnd: Int, qtype: Int): ByteArray? {
        if (questionEnd > query.size) return null
        val answerLen = when (qtype) {
            1 -> 16  // A -> 10.233.233.254
            28 -> 28 // AAAA -> fd00:233:233::254
            else -> 0
        }
        val out = ByteArray(questionEnd + answerLen)

        out[0] = query[0]
        out[1] = query[1]
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        out[4] = 0x00
        out[5] = 0x01
        out[6] = 0x00
        out[7] = if (answerLen > 0) 0x01 else 0x00
        out[8] = 0x00; out[9] = 0x00
        out[10] = 0x00; out[11] = 0x00

        System.arraycopy(query, 12, out, 12, questionEnd - 12)

        if (qtype == 1) {
            var p = questionEnd
            out[p++] = 0xC0.toByte()
            out[p++] = 0x0C
            out[p++] = 0x00; out[p++] = 0x01
            out[p++] = 0x00; out[p++] = 0x01
            out[p++] = 0x00; out[p++] = 0x00; out[p++] = 0x00; out[p++] = 0x01
            out[p++] = 0x00; out[p++] = 0x04
            out[p++] = 10
            out[p++] = 233.toByte()
            out[p++] = 233.toByte()
            out[p] = 254.toByte()
        } else if (qtype == 28) {
            var p = questionEnd
            out[p++] = 0xC0.toByte()
            out[p++] = 0x0C
            out[p++] = 0x00; out[p++] = 0x1C
            out[p++] = 0x00; out[p++] = 0x01
            out[p++] = 0x00; out[p++] = 0x00; out[p++] = 0x00; out[p++] = 0x01
            out[p++] = 0x00; out[p++] = 0x10
            val sink6 = byteArrayOf(
                0xFD.toByte(), 0x00, 0x02, 0x33, 0x02, 0x33, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x54
            )
            System.arraycopy(sink6, 0, out, p, 16)
        }
        return out
    }

    private fun forwardDnsQueryUpstream(query: ByteArray): ByteArray? {
        for (dnsIp in UPSTREAM_DNS_SERVERS) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 1200
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
        out[9] = 17
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

    private fun wrapIpv6UdpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 40 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x60.toByte()
        writeUInt16(out, 4, udpLen)
        out[6] = 17
        out[7] = 64
        System.arraycopy(srcIp, 0, out, 8, 16)
        System.arraycopy(dstIp, 0, out, 24, 16)

        writeUInt16(out, 40, srcPort)
        writeUInt16(out, 42, dstPort)
        writeUInt16(out, 44, udpLen)
        System.arraycopy(payload, 0, out, 48, payload.size)
        val csum = computeIpv6TransportChecksum(out, 8, 24, 17, 40, udpLen)
        writeUInt16(out, 46, if (csum == 0) 0xFFFF else csum)
        return out
    }

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
            6 -> {
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
            17 -> {
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
            6 -> {
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
            17 -> {
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
