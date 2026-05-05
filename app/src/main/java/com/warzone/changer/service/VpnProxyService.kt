package com.warzone.changer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.github.megatronking.netbare.http.HttpVirtualGatewayFactory
import com.github.megatronking.netbare.ip.IpAddress
import com.github.megatronking.netbare.ssl.JKS
import com.warzone.changer.injector.LocationInjector
import com.warzone.changer.ui.MainActivity

/**
 * VPN代理服务
 * 使用NetBare库创建本地VPN，拦截并修改王者荣耀的地图API请求
 */
class VpnProxyService : VpnService(), NetBareListener {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.warzone.action.START"
        const val ACTION_STOP = "com.warzone.action.STOP"
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        NetBare.get().attachApplication(application, true)
        NetBare.get().registerListener(this)
    }

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

            // 创建JKS用于SSL证书管理（即使我们只拦截HTTP也需要）
            val jks = JKS(
                this,
                "warzone",
                "warzone123".toCharArray(),
                "WarZone Changer",
                "WarZone",
                "WarZone",
                "WarZone",
                "WarZone"
            )

            // 创建拦截器工厂
            val interceptorFactories = listOf<HttpInterceptorFactory>(
                HttpInterceptorFactory { LocationInjector(applicationContext) }
            )

            // 使用默认HTTP配置
            val config = NetBareConfig.defaultHttpConfig(jks, interceptorFactories)

            NetBare.get().start(config)
            isRunning = true
            Log.i(TAG, "VPN代理已启动 - 战区修改生效中")
        } catch (e: Exception) {
            Log.e(TAG, "启动VPN失败", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        try {
            NetBare.get().stop()
            isRunning = false
            Log.i(TAG, "VPN代理已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止VPN失败", e)
        }
        stopForeground(true)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val channelId = "warzone_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "战区修改器",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("战区修改器")
            .setContentText("VPN代理运行中 - 战区已修改")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        NetBare.get().unregisterListener(this)
        super.onDestroy()
    }

    // NetBareListener callbacks
    override fun onServiceStarted() {
        Log.i(TAG, "NetBare服务已启动")
    }

    override fun onServiceStopped() {
        Log.i(TAG, "NetBare服务已停止")
        isRunning = false
    }
}
