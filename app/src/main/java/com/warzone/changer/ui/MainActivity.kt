package com.warzone.changer.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.http.HttpInjectInterceptor
import com.github.megatronking.netbare.http.HttpVirtualGatewayFactory
import com.github.megatronking.netbare.ip.IpAddress
import com.github.megatronking.netbare.ssl.JKS
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import com.warzone.changer.injector.LocationInjector
import com.warzone.changer.service.VpnProxyService

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_VPN = 100
        private const val REQUEST_LOCATION = 200
    }

    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnPickLocation: Button
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentLocation = findViewById(R.id.tv_current_location)
        tvStatus = findViewById(R.id.tv_status)
        btnToggle = findViewById(R.id.btn_toggle)
        btnPickLocation = findViewById(R.id.btn_pick_location)

        btnPickLocation.setOnClickListener {
            startActivityForResult(Intent(this, LocationPickerActivity::class.java), REQUEST_LOCATION)
        }

        btnToggle.setOnClickListener {
            if (VpnProxyService.isRunning) stopVpn() else requestVpnPermission()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN -> {
                if (resultCode == Activity.RESULT_OK) startVpn()
            }
            REQUEST_LOCATION -> updateUI()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            Log.i(TAG, "请求VPN权限")
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        try {
            // 创建 JKS（NetBare 要求）
            val jks = JKS(
                this, "warzone", "warzone123".toCharArray(),
                "WarZone", "WarZone", "WarZone", "WarZone", "WarZone"
            )

            // 创建拦截器工厂
            val factory = HttpInjectInterceptor.createFactory(LocationInjector(applicationContext))
            val gatewayFactory = HttpVirtualGatewayFactory(jks, listOf(factory))

            // 构建配置
            val config = NetBareConfig.defaultConfig().newBuilder()
                .setVirtualGatewayFactory(gatewayFactory)
                .build()

            // ★ 关键: 通过 NetBare 启动，它会发送 intent 给 VpnProxyService
            NetBare.get().start(config)

            Log.i(TAG, "NetBare.start() 已调用")
            handler.postDelayed({ updateUI() }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVpn() {
        try {
            NetBare.get().stop()
            Log.i(TAG, "NetBare.stop() 已调用")
            handler.postDelayed({ updateUI() }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "停止失败", e)
        }
    }

    private fun updateUI() {
        val location = LocationStore.getSelectedLocation(this)
        tvCurrentLocation.text = if (location != null) "当前战区: ${location.displayName}" else "当前战区: 未选择"

        if (VpnProxyService.isRunning) {
            tvStatus.text = "状态: VPN运行中"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
            btnToggle.text = "停止VPN"
        } else {
            tvStatus.text = "状态: 未运行"
            tvStatus.setTextColor(0xFF757575.toInt())
            btnToggle.text = "启动VPN"
        }
    }
}
