package com.separ.locationdrifter.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.separ.locationdrifter.util.RootUtils
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 免 CA 证书的局部 VPN 防漂移通道（IPv4 + IPv6 双栈备用方案）：
 * 仅对目标网络定位域名 IP 进行路由黑洞，绝不接管 0.0.0.0/0 或 ::/0 正常上网流量。
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                val ipv4s = LinkedHashSet<String>()
                val ipv6s = LinkedHashSet<String>()
                for (host in RootUtils.LBS_HOSTS) {
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

                val builder = Builder()
                    .setSession("LocationDrifter-AntiDrift")
                    .setMtu(1500)
                    .addAddress("10.233.233.1", 32)
                    .addAddress("fd00:233:233::1", 128)

                if (ipv4s.isEmpty() && ipv6s.isEmpty()) {
                    // 仅添加占位路由，绝不劫持默认路由造成断网
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

                // 放行自身（避免本地地图搜索/DNS受影响），对其余全部应用生效
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Throwable) {
                }

                vpnInterface = builder.establish()
                val fd = vpnInterface?.fileDescriptor ?: run {
                    isRunning = false
                    return@Thread
                }

                drainThread = Thread {
                    val buf = ByteArray(32767)
                    try {
                        FileInputStream(fd).use { input ->
                            while (isRunning && !Thread.currentThread().isInterrupted) {
                                val read = input.read(buf)
                                if (read < 0) break
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }.apply { start() }
            } catch (_: Throwable) {
                isRunning = false
                stopSelf()
            }
        }.start()
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
