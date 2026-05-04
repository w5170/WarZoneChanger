package com.warzone.changer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.warzone.changer.data.LocationStore
import com.warzone.changer.packet.PacketHandler
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

/**
 * VPN 代理服务 — 只路由目标 IP
 *
 * 关键设计：不走 addRoute("0.0.0.0", 0) 全量路由
 * 而是只 addRoute 目标 IP（apis.map.qq.com 的解析结果）
 * → 非目标流量根本不经过 VPN → 游戏不会卡
 */
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "warzone_vpn"
        private const val TARGET_DOMAIN = "apis.map.qq.com"

        var isRunning = false
            private set
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var packetHandler: PacketHandler? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("正在启动..."))
        Thread { startVpn() }.start()
        return START_STICKY
    }

    private fun startVpn() {
        // 1. 读取选中战区
        val location = LocationStore.getSelectedLocation(this)
        val adcode = location?.adcode ?: run {
            Log.e(TAG, "No location selected")
            stopSelf()
            return
        }
        Log.i(TAG, "Adcode: $adcode (${location?.displayName})")

        // 2. 解析目标域名 → IP 列表
        val targetIps = resolveTargetIps()
        if (targetIps.isEmpty()) {
            Log.e(TAG, "Cannot resolve $TARGET_DOMAIN")
            stopSelf()
            return
        }
        Log.i(TAG, "Target IPs: $targetIps")

        // 3. 构建假响应
        val fakeResp = buildFakeHttpResponse(adcode)

        // 4. 建立 VPN — 只路由目标 IP！
        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .setSession("WarZoneChanger")
            .setMtu(1500)
            .setBlocking(true)

        for (ip in targetIps) {
            builder.addRoute(ip, 32)
            Log.d(TAG, "Route added: $ip/32")
        }

        vpnFd = builder.establish()
        if (vpnFd == null) {
            Log.e(TAG, "VPN establish failed")
            stopSelf()
            return
        }

        // 5. 启动包处理器
        val tunIn = FileInputStream(vpnFd!!.fileDescriptor)
        val tunOut = FileOutputStream(vpnFd!!.fileDescriptor)

        packetHandler = PacketHandler(
            tunInput = tunIn,
            tunOutput = tunOut,
            targetIps = targetIps,
            fakeHttpResponse = fakeResp
        )

        isRunning = true
        packetHandler?.start()

        updateNotification("战区: ${location?.displayName} ($adcode)")
        Log.i(TAG, "VPN started OK")
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        isRunning = false
        packetHandler?.stop()
        packetHandler = null
        try { vpnFd?.close() } catch (_: Exception) {}
        vpnFd = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    /**
     * 解析目标域名，返回 IPv4 地址列表
     */
    private fun resolveTargetIps(): Set<String> {
        val ips = mutableSetOf<String>()
        try {
            val addrs = InetAddress.getAllByName(TARGET_DOMAIN)
            for (addr in addrs) {
                val ip = addr.hostAddress ?: continue
                // 只要 IPv4
                if (ip.contains(":")) continue
                ips.add(ip)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS resolve failed", e)
        }

        // 后备：已知 IP
        if (ips.isEmpty()) {
            val known = listOf(
                "101.89.46.62", "101.89.46.61",
                "101.89.46.70", "101.89.46.71",
                "101.89.46.78", "101.89.46.79"
            )
            ips.addAll(known)
            Log.w(TAG, "Using known backup IPs: $ips")
        }

        return ips
    }

    /**
     * 构建假 HTTP 响应 — 修改 result.ad_info.adcode
     */
    private fun buildFakeHttpResponse(adcode: String): ByteArray {
        val json = """{"status":0,"message":"query ok","request_id":"fakereq001","result":{"ad_info":{"adcode":"$adcode","nation":"中国","province":"","city":"","district":""},"location":{"lat":39.9042,"lng":116.4074},"formatted_addresses":{"recommend":"","rough":""}}}"""
        val body = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nServer: TencentMapHTTPServer\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + body
    }

    // ===== 通知 =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "战区切换", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return b.setContentTitle("WarZoneChanger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
