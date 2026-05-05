package com.warzone.changer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.github.megatronking.netbare.NetBare

class App : Application() {
    companion object {
        const val CHANNEL_ID = "warzone_vpn_channel"
    }

    override fun onCreate() {
        super.onCreate()
        NetBare.get().attachApplication(this, BuildConfig.DEBUG)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "战区修改器", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
