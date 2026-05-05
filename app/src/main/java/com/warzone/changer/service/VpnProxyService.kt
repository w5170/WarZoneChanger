package com.warzone.changer.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.github.megatronking.netbare.NetBareService
import com.warzone.changer.App
import com.warzone.changer.ui.MainActivity

class VpnProxyService : NetBareService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1001
    }

    override fun notificationId(): Int = NOTIFICATION_ID

    override fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("战区修改器")
            .setContentText("VPN运行中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 重写 onStartCommand：
     * 先确保 startForeground 被调用，再处理 VPN 逻辑
     * 防止 startNetBare() 异常导致 ANR/闪退
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        // ★ 第一时间调用 startForeground，Android 要求 5 秒内必须调用
        try {
            startForeground(notificationId(), createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
        }

        // 再交给父类处理 VPN 启动/停止
        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            Log.e(TAG, "super.onStartCommand 失败", e)
            stopSelf()
            START_NOT_STICKY
        }
    }
}
