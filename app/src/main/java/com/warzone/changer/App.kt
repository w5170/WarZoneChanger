package com.warzone.changer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.ssl.JKS

class App : Application() {

    companion object {
        const val CHANNEL_ID = "warzone_vpn_channel"
        const val JSK_ALIAS = "WarZoneChanger"

        private lateinit var instance: App

        fun getInstance(): App = instance
    }

    private lateinit var mJKS: JKS

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 创建 JKS（不需要安装证书，只用于 HTTP 拦截）
        mJKS = JKS(this, JSK_ALIAS, JSK_ALIAS.toCharArray(),
            JSK_ALIAS, JSK_ALIAS, JSK_ALIAS, JSK_ALIAS, JSK_ALIAS)

        // 初始化 NetBare
        NetBare.get().attachApplication(this, false)

        createNotificationChannel()
    }

    fun getJSK(): JKS = mJKS

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "战区修改器", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
