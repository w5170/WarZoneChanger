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
        startForeground(NOTIFICATION_ID, createNotification("正在启动..."))
        Thread {
            try { startVpn() }
            catch (e: Exception) { Log.e(TAG, "VPN failed", e); sendStatus(false, e.message ?: "未知"); stopSelf() }
        }.start()
        return START_STICKY
    }

    private fun startVpn() {
        val location = LocationStore.getSelectedLocation(this)
        val adcode = location?.adcode ?: "110101"
        Log.i(TAG, "adcode=$adcode")

        val targetIps = mutableSetOf<String>()
        try {
            for (addr in InetAddress.getAllByName("apis.map.qq.com")) {
                targetIps.add(addr.hostAddress!!)
                Log.i(TAG, "Target: ${addr.hostAddress}")
            }
        } catch (e: Exception) { targetIps.add("101.89.46.62"); targetIps.add("101.89.46.61") }

        val fakeResp = buildFakeHttpResponse(adcode)

        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("114.114.114.114")
            .setMtu(1500)
            .setSession("WarZoneChanger")
            .setBlocking(true)

        vpnInterface = builder.establish()
        if (vpnInterface == null) { sendStatus(false, "VPN建立失败"); stopSelf(); return }
        Log.i(TAG, "VPN established")

        val fd = vpnInterface!!
        packetHandler = PacketHandler(
            tunInput = FileInputStream(fd.fileDescriptor),
            tunOutput = FileOutputStream(fd.fileDescriptor),
            targetIps = targetIps,
            fakeHttpResponse = fakeResp,
            protectFn = { s -> protect(s) },
            protectDg = { s -> protect(s) }
        )

        isRunning = true
        packetHandler?.start()
        sendStatus(true, null)
        updateNotification("运行中 adcode=$adcode")
        Log.i(TAG, "Running! Targets: $targetIps")
    }

    private fun stopVpn() {
        isRunning = false; packetHandler?.stop(); packetHandler = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null; sendStatus(false, null); stopForeground(true); stopSelf()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun sendStatus(running: Boolean, error: String?) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_RUNNING, running)
            if (error != null) putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        })
    }

    private fun buildFakeHttpResponse(adcode: String): ByteArray {
        val json = """{"status":0,"message":"query ok","request_id":"f","result":{"ad_info":{"adcode":"$adcode","nation":"中国","province":"","city":"","district":""},"location":{"lat":39.9,"lng":116.4},"formatted_addresses":{"recommend":"","rough":""}}}"""
        val body = json.toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        return hdr.toByteArray(Charsets.US_ASCII) + body
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "战区切换", NotificationManager.IMPORTANCE_LOW))
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
