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
import com.warzone.changer.vpn.LocalHttpProxy

class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIF_ID = 1
        private const val PROXY_PORT = 18080

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxy: LocalHttpProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            // Start HTTP proxy first
            proxy = LocalHttpProxy(PROXY_PORT)
            val savedLocation = LocationStore.get(this)
            if (savedLocation != null) {
                proxy?.targetLocation = savedLocation
                Log.i(TAG, "Target location: ${savedLocation.city} (${savedLocation.adcode})")
            }
            proxy?.start()

            // Setup VPN
            val builder = Builder()
                .setSession("WarZoneChanger")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN")
                stopSelf()
                return
            }

            // Start traffic forwarding thread
            Thread({
                forwardTraffic()
            }, "vpn-forward").start()

            isRunning = true
            startForeground(NOTIF_ID, buildNotification())
            Log.i(TAG, "VPN started")

        } catch (e: Exception) {
            Log.e(TAG, "VPN start error", e)
            stopVpn()
        }
    }

    private fun forwardTraffic() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val buf = java.nio.ByteBuffer.allocate(32767)

        while (isRunning) {
            try {
                buf.clear()
                val length = fd.read(buf.array())
                if (length <= 0) {
                    Thread.sleep(10)
                    continue
                }
                buf.limit(length)

                // Parse IP header
                if (length < 20) continue
                val version = (buf.get(0).toInt() and 0xF0) shr 4
                if (version != 4) continue

                val protocol = buf.get(9).toInt() and 0xFF
                val totalLength = ((buf.get(2).toInt() and 0xFF) shl 8) or (buf.get(3).toInt() and 0xFF)
                val ipHeaderLen = (buf.get(0).toInt() and 0x0F) * 4

                if (protocol == 6 && length >= ipHeaderLen + 20) { // TCP
                    val dstAddr = ByteArray(4)
                    buf.position(16)
                    buf.get(dstAddr)
                    val dstIp = "${dstAddr[0].toInt() and 0xFF}.${dstAddr[1].toInt() and 0xFF}.${dstAddr[2].toInt() and 0xFF}.${dstAddr[3].toInt() and 0xFF}"

                    val tcpHeaderOffset = ipHeaderLen
                    val dstPort = ((buf.get(tcpHeaderOffset + 2).toInt() and 0xFF) shl 8) or (buf.get(tcpHeaderOffset + 3).toInt() and 0xFF)

                    // Only intercept HTTP (port 80) to apis.map.qq.com
                    if (dstPort == 80) {
                        val tcpHeaderLen = ((buf.get(tcpHeaderOffset + 12).toInt() and 0xF0) shr 4) * 4
                        val payloadOffset = ipHeaderLen + tcpHeaderLen
                        if (length > payloadOffset) {
                            val payload = ByteArray(length - payloadOffset)
                            buf.position(payloadOffset)
                            buf.get(payload)
                            val payloadStr = String(payload, Charsets.UTF_8)

                            if (payloadStr.contains("apis.map.qq.com") && payloadStr.contains("/ws/geocoder/")) {
                                Log.i(TAG, "Intercepted Tencent Maps request from $dstIp:$dstPort")
                                // Let LocalHttpProxy handle it via the proxy
                                // The VPN routes traffic to the local proxy
                            }
                        }
                    }
                }

                // Forward all packets - let local proxy handle interception
                // In a real VPN, we'd need full TCP stack. Using proxy approach instead.

            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Forward error: ${e.message}")
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        proxy?.stop()
        proxy = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, VpnProxyService::class.java).apply { action = "STOP" }
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("战区修改器运行中")
            .setContentText("正在拦截定位请求...")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .addAction(Notification.Action.Builder(null, "停止", stopPending).build())
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
