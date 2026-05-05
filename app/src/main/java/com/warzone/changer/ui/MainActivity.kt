package com.warzone.changer.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
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
    private val refreshRunnable = Runnable { updateUI() }

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
            if (VpnProxyService.isRunning) stopVpnService()
            else requestVpnPermission()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else if (requestCode == REQUEST_LOCATION) {
            updateUI()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            handler.postDelayed({ updateUI() }, 1000)
            Log.i(TAG, "VPN服务启动请求已发送")
        } catch (e: Exception) {
            Log.e(TAG, "启动VPN服务失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(this, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_STOP
            }
            startService(intent)
            handler.postDelayed({ updateUI() }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "停止VPN服务失败", e)
        }
    }

    private fun updateUI() {
        val location = LocationStore.getSelectedLocation(this)
        if (location != null) {
            tvCurrentLocation.text = "当前战区: ${location.displayName}"
        } else {
            tvCurrentLocation.text = "当前战区: 未选择"
        }

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
