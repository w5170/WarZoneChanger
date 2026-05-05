package com.warzone.changer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.NetBareService
import com.github.megatronking.netbare.http.HttpInjectInterceptor
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.warzone.changer.App
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import com.warzone.changer.injector.LocationInjector

class MainActivity : Activity(), NetBareListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PREPARE = 1
        private const val REQUEST_LOCATION = 200
    }

    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnPickLocation: Button

    private val mNetBare = NetBare.get()

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
            if (mNetBare.isActive) {
                stopVpn()
            } else {
                prepareNetBare()
            }
        }

        mNetBare.registerNetBareListener(this)
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        mNetBare.unregisterNetBareListener(this)
    }

    override fun onServiceStarted() {
        Log.i(TAG, "NetBare服务已启动")
        runOnUiThread { updateUI() }
    }

    override fun onServiceStopped() {
        Log.i(TAG, "NetBare服务已停止")
        runOnUiThread { updateUI() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_PREPARE -> {
                if (resultCode == Activity.RESULT_OK) {
                    prepareNetBare()
                }
            }
            REQUEST_LOCATION -> updateUI()
        }
    }

    private fun prepareNetBare() {
        Log.i(TAG, "prepareNetBare 开始")

        // 请求 VPN 权限
        val intent = mNetBare.prepare()
        if (intent != null) {
            Log.i(TAG, "请求VPN权限")
            startActivityForResult(intent, REQUEST_CODE_PREPARE)
            return
        }
        Log.i(TAG, "VPN权限已有，启动服务")

        try {
            // 构建配置
            val config = NetBareConfig.defaultHttpConfig(
                App.getInstance().getJSK(),
                interceptorFactories()
            )

            // 存储配置到 NetBare（不发 intent）
            mNetBare.prepareConfig(config)

            // 手动启动服务
            val serviceIntent = Intent(NetBareService.ACTION_START)
            serviceIntent.setPackage(packageName)
            startForegroundService(serviceIntent)

            Log.i(TAG, "服务启动命令已发送")
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVpn() {
        try {
            val intent = Intent(NetBareService.ACTION_STOP)
            intent.setPackage(packageName)
            startService(intent)
            Log.i(TAG, "停止命令已发送")
        } catch (e: Exception) {
            Log.e(TAG, "停止失败", e)
        }
    }

    private fun interceptorFactories(): List<HttpInterceptorFactory> {
        return listOf(
            HttpInjectInterceptor.createFactory(LocationInjector(applicationContext))
        )
    }

    private fun updateUI() {
        val location = LocationStore.getSelectedLocation(this)
        tvCurrentLocation.text = if (location != null) "当前战区: ${location.displayName}" else "当前战区: 未选择"

        if (mNetBare.isActive) {
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
