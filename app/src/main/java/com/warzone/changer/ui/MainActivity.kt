package com.warzone.changer.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        private const val REQUEST_VPN = 100
        private const val REQUEST_LOCATION = 200
        private const val TAG = "MainActivity"
    }

    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnPickLocation: Button
    private val handler = Handler(Looper.getMainLooper())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val running = intent.getBooleanExtra(VpnProxyService.EXTRA_RUNNING, false)
            val error = intent.getStringExtra(VpnProxyService.EXTRA_ERROR)
            Log.d(TAG, "Status: running=$running, error=$error")
            if (error != null) {
                Toast.makeText(this@MainActivity, "VPN错误: $error", Toast.LENGTH_LONG).show()
            }
            updateUI()
        }
    }

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
        val filter = IntentFilter(VpnProxyService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN -> {
                if (resultCode == RESULT_OK) startVpnService()
                else Toast.makeText(this, "VPN 权限被拒绝", Toast.LENGTH_SHORT).show()
            }
            REQUEST_LOCATION -> updateUI()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQUEST_VPN)
        else startVpnService()
    }

    private fun startVpnService() {
        val location = LocationStore.getSelectedLocation(this)
        if (location == null) {
            Toast.makeText(this, "请先选择战区", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val svcIntent = Intent(this, VpnProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            Log.i(TAG, "Service start requested")
            Toast.makeText(this, "正在启动...", Toast.LENGTH_SHORT).show()
            // 轮询等待启动
            pollRunning(0)
        } catch (e: Exception) {
            Log.e(TAG, "startService failed", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pollRunning(attempts: Int) {
        handler.postDelayed({
            if (VpnProxyService.isRunning) {
                updateUI()
            } else if (attempts < 30) {
                pollRunning(attempts + 1)
            } else {
                Toast.makeText(this, "启动超时，请检查VPN权限和网络", Toast.LENGTH_LONG).show()
                updateUI()
            }
        }, 500)
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(this, VpnProxyService::class.java).apply { action = "STOP" }
            startService(intent)
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "停止失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ updateUI() }, 500)
    }

    private fun updateUI() {
        val location = LocationStore.getSelectedLocation(this)
        if (location != null) {
            tvCurrentLocation.text = "当前战区: ${location.displayName}"
            tvCurrentLocation.setTextColor(0xFF00FF88.toInt())
        } else {
            tvCurrentLocation.text = "未选择战区"
            tvCurrentLocation.setTextColor(0xFFFF6B6B.toInt())
        }

        if (VpnProxyService.isRunning) {
            tvStatus.text = "● 运行中"
            tvStatus.setTextColor(0xFF00FF88.toInt())
            btnToggle.text = "停止"
        } else {
            tvStatus.text = "○ 已停止"
            tvStatus.setTextColor(0xFFFF6B6B.toInt())
            btnToggle.text = "启动"
        }
    }
}
