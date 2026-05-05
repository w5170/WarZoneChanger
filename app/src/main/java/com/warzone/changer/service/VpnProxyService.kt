package com.warzone.changer.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.github.megatronking.netbare.NetBareService
import com.warzone.changer.App
import com.warzone.changer.ui.MainActivity

/**
 * VPN服务 - 继承 NetBareService
 * 
 * NetBareService 内部处理 onStartCommand:
 * - ACTION_START → startNetBare() → 建立 VPN
 * - ACTION_STOP → stopNetBare() → 断开 VPN
 * 
 * 无需手动处理启动逻辑。
 */
class VpnProxyService : NetBareService() {

    companion object {
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
}
