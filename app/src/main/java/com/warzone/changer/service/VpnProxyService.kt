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
 * VPN 代理服务（只路由目标 IP）
 *
 * 核心改进：不再路由所有流量！
 * 只把 apis.map.qq.com 的 IP 通过 VPN，其余流量走真实网络。
 * 这样 DNS/其他服务器连接完全不受影响。
 */
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "warzone_vpn"
        var isRunning = false; private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetHandler: PacketHandler? = null

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopVpn(); return START_NOT_STICKY }
        if (isRunning) return START_STICKY
        startForeground(NOTIFICATION_ID, createNotification("正在启动..."))
        Thread {
            try { startVpn() } catch (e: Exception) {
                Log.e(TAG, "VPN start failed", e); stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun startVpn() {
        val location = LocationStore.getSelectedLocation(this)
        val adcode = location?.adcode ?: "110101"
        Log.i(TAG, "Starting VPN, adcode=$adcode")

        // 解析目标域名
        val targetIps = mutableSetOf<String>()
        try {
            for (addr in InetAddress.getAllByName("apis.map.qq.com")) {
                targetIps.add(addr.hostAddress!!)
                Log.d(TAG, "Target IP: ${addr.hostAddress}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS resolve failed", e)
            targetIps.add("101.89.46.62"); targetIps.add("101.89.46.61")
        }

        val fakeResponse = buildFakeHttpResponse(adcode)

        // === 关键：只路由目标 IP，不路由所有流量 ===
        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .setSession("WarZoneChanger")
            .setMtu(1500)
            .setBlocking(true)

        // 只添加目标 IP 的路由
        for (ip in targetIps) {
            builder.addRoute(ip, 32)
            Log.d(TAG, "Route added: $ip/32")
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) { Log.e(TAG, "VPN null!"); stopSelf(); return }

        val fd = vpnInterface!!
        packetHandler = PacketHandler(
            tunInput = FileInputStream(fd.fileDescriptor),
            tunOutput = FileOutputStream(fd.fileDescriptor),
            targetIps = targetIps,
            fakeHttpResponse = fakeResponse
        )

        isRunning = true
        packetHandler?.start()
        Log.i(TAG, "VPN started, only routing: $targetIps")
        updateNotification("运行中 adcode=$adcode")
    }

    private fun stopVpn() {
        isRunning = false; packetHandler?.stop(); packetHandler = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null; stopForeground(true); stopSelf()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun buildFakeHttpResponse(adcode: String): ByteArray {
        val json = """
            {"status":0,"message":"query ok","request_id":"fake001",
             "result":{"ad_info":{"adcode":"$adcode","nation":"中国","province":"","city":"","district":""},
             "location":{"lat":39.9042,"lng":116.4074},
             "formatted_addresses":{"recommend":"","rough":""}}}
        """.trimIndent()
        val body = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nServer: TencentMapHTTPServer\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + body
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "战区切换", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("WarZoneChanger").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
    }
}
