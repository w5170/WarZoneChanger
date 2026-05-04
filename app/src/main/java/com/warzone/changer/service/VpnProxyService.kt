package com.warzone.changer.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.warzone.changer.App
import com.warzone.changer.R
import com.warzone.changer.ui.MainActivity

/**
 * VPN代理服务（替代已删除的 NetBare 库）
 *
 * 原理：
 * 1. VpnService 创建 TUN 虚拟网卡，拦截设备所有网络流量
 * 2. PacketHandler 直接解析 IP/TCP 数据包
 * 3. 识别腾讯地图 API (HTTP) 请求并返回假的 adcode 响应
 * 4. 游戏读到假的 adcode → 设置战区
 */
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.warzone.action.START"
        const val ACTION_STOP = "com.warzone.action.STOP"
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetHandler: PacketHandler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            startForeground(NOTIFICATION_ID, createNotification())

            // 创建 VPN 接口
            vpnInterface = createVpnInterface()

            // 启动包处理器
            packetHandler = PacketHandler(this)
            packetHandler?.start(vpnInterface!!)

            isRunning = true
            Log.i(TAG, "VPN代理已启动 - 战区修改生效中")
        } catch (e: Exception) {
            Log.e(TAG, "启动VPN失败", e)
            stopSelf()
        }
    }

    /**
     * 创建 VPN TUN 接口
     *
     * 将所有 IPv4 流量路由到 VPN 接口，
     * PacketHandler 从 TUN 设备读取并处理数据包。
     */
    private fun createVpnInterface(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("WarZoneVPN")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)  // 拦截所有 IPv4 流量
            .setMtu(1500)

        // 排除本应用自身的流量（避免死循环）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "排除自身流量失败: ${e.message}")
            }
        }

        return builder.establish()
            ?: throw IllegalStateException("无法创建 VPN 接口")
    }

    private fun stopVpn() {
        isRunning = false
        try {
            packetHandler?.stop()
            packetHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "停止包处理器失败", e)
        }
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭VPN接口失败", e)
        }
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "VPN代理已停止")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("战区修改器")
            .setContentText("VPN代理运行中 - 战区已修改")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
