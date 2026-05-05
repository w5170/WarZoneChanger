package com.warzone.changer.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.warzone.changer.App
import com.warzone.changer.injector.LocationInjector
import com.warzone.changer.ui.MainActivity

class VpnProxyService : VpnService(), NetBareListener {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STATUS = "com.warzone.changer.VPN_STATUS"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        @Volatile var isRunning = false; private set
    }

    private var netBare: NetBare? = null

    override fun onCreate() {
        super.onCreate()
        netBare = NetBare.get()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopVpn(); return START_NOT_STICKY }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            startForeground(NOTIFICATION_ID, createNotification("正在启动..."))
            val config = NetBareConfig.Builder()
                .addInterceptorFactory(HttpInterceptorFactory { LocationInjector(applicationContext) })
                .build()
            netBare?.start(config)
            isRunning = true
            sendStatus(true, null)
            updateNotification("VPN代理运行中 - 战区已修改")
            Log.i(TAG, "VPN启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            sendStatus(false, e.message ?: "启动失败")
            stopSelf()
        }
    }

    private fun stopVpn() {
        try {
            netBare?.stop()
            isRunning = false
            sendStatus(false, null)
            Log.i(TAG, "VPN已停止")
        } catch (e: Exception) { Log.e(TAG, "停止失败", e) }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) stopVpn()
        super.onDestroy()
    }

    // NetBareListener
    override fun onServiceStarted() {
        Log.i(TAG, "NetBare service started")
    }

    override fun onServiceStopped() {
        Log.i(TAG, "NetBare service stopped")
        isRunning = false
        sendStatus(false, null)
    }

    private fun sendStatus(running: Boolean, error: String?) {
        try {
            sendBroadcast(Intent(ACTION_STATUS).apply {
                putExtra(EXTRA_RUNNING, running)
                if (error != null) putExtra(EXTRA_ERROR, error)
                setPackage(packageName)
            })
        } catch (_: Exception) {}
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, App.CHANNEL_ID)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("战区修改器").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pi).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text)) }
        catch (_: Exception) {}
    }
}
