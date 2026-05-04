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

class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "warzone_vpn"
        const val ACTION_STATUS = "com.warzone.changer.VPN_STATUS"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        @Volatile var isRunning = false; private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetHandler: PacketHandler? = null

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopVpn(); return START_NOT_STICKY }
        if (isRunning) return START_STICKY
        Log.i(TAG, "onStartCommand: starting foreground")
        startForeground(NOTIFICATION_ID, createNotification("正在启动..."))
        Thread {
            try {
                startVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed", e)
                sendStatus(false, e.message ?: "未知错误")
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun startVpn() {
        val location = LocationStore.getSelectedLocation(this)
        val adcode = location?.adcode ?: "110101"
        Log.i(TAG, "Starting VPN, adcode=$adcode")

        val targetIps = mutableSetOf<String>()
        try {
            for (addr in InetAddress.getAllByName("apis.map.qq.com")) {
                targetIps.add(addr.hostAddress!!)
                Log.i(TAG, "Resolved: apis.map.qq.com -> ${addr.hostAddress}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS failed, using fallback", e)
            targetIps.add("101.89.46.62"); targetIps.add("101.89.46.61")
        }

        val fakeResponse = buildFakeHttpResponse(adcode)

        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .setSession("WarZoneChanger")
            .setMtu(1500)
            .setBlocking(true)

        for (ip in targetIps) {
            builder.addRoute(ip, 32)
            Log.i(TAG, "Route: $ip/32")
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            val err = "VPN establish() returned null"
            Log.e(TAG, err)
            sendStatus(false, err)
            stopSelf()
            return
        }

        Log.i(TAG, "VPN interface established, fd=${vpnInterface!!.fd}")

        packetHandler = PacketHandler(
            tunInput = FileInputStream(vpnInterface!!.fileDescriptor),
            tunOutput = FileOutputStream(vpnInterface!!.fileDescriptor),
            targetIps = targetIps,
            fakeHttpResponse = fakeResponse
        )

        isRunning = true
        packetHandler?.start()
        sendStatus(true, null)
        updateNotification("运行中 adcode=$adcode")
        Log.i(TAG, "VPN running! Target IPs: $targetIps")
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        isRunning = false
        packetHandler?.stop()
        packetHandler = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        sendStatus(false, null)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun sendStatus(running: Boolean, error: String?) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_RUNNING, running)
            if (error != null) putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Status broadcast: running=$running, error=$error")
    }

    private fun buildFakeHttpResponse(adcode: String): ByteArray {
        val json = """{"status":0,"message":"query ok","request_id":"fake001","result":{"ad_info":{"adcode":"$adcode","nation":"中国","province":"","city":"","district":""},"location":{"lat":39.9042,"lng":116.4074},"formatted_addresses":{"recommend":"","rough":""}}}"""
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
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text)) }
        catch (_: Exception) {}
    }
}
