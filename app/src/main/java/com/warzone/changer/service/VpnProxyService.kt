package com.warzone.changer.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.NetBareService
import com.github.megatronking.netbare.http.HttpInjectInterceptor
import com.github.megatronking.netbare.http.HttpVirtualGatewayFactory
import com.github.megatronking.netbare.ip.IpAddress
import com.github.megatronking.netbare.ssl.JKS
import com.warzone.changer.App
import com.warzone.changer.injector.LocationInjector
import com.warzone.changer.ui.MainActivity

/**
 * VPN代理服务 - 继承 NetBareService
 * 
 * 启动流程:
 * 1. MainActivity 调用 NetBare.get().start(config)
 * 2. NetBare 存储 config 并发送 ACTION_START intent
 * 3. 本服务收到 intent，调用 startNetBare() 建立 VPN
 */
class VpnProxyService : NetBareService(), NetBareListener {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set
    }

    override fun notificationId(): Int = NOTIFICATION_ID

    override fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("战区修改器")
            .setContentText("VPN运行中 - 战区已修改")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 让 NetBareService 处理 ACTION_START / ACTION_STOP
        val result = super.onStartCommand(intent, flags, startId)
        if (intent != null && ACTION_START == intent.action) {
            isRunning = true
            Log.i(TAG, "VPN已启动")
        } else if (intent != null && ACTION_STOP == intent.action) {
            isRunning = false
            Log.i(TAG, "VPN已停止")
        }
        return result
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onServiceStarted() {
        Log.i(TAG, "NetBare服务已启动")
        isRunning = true
    }

    override fun onServiceStopped() {
        Log.i(TAG, "NetBare服务已停止")
        isRunning = false
    }
}
