package com.warzone.changer.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import com.warzone.changer.service.VpnProxyService

/**
 * 主界面
 * 简洁设计：显示当前战区 + 启动/停止按钮
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_VPN = 100
        private const val REQUEST_LOCATION = 200
    }

    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnPickLocation: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentLocation = findViewById(R.id.tv_current_location)
        tvStatus = findViewById(R.id.tv_status)
        btnToggle = findViewById(R.id.btn_toggle)
        btnPickLocation = findViewById(R.id.btn_pick_location)

        btnPickLocation.setOnClickListener {
            startActivityForResult(
                Intent(this, LocationPickerActivity::class.java),
                REQUEST_LOCATION
            )
        }

        btnToggle.setOnClickListener {
            if (VpnProxyService.isRunning) {
                stopVpnService()
            } else {
                requestVpnPermission()
            }
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
                if (resultCode == RESULT_OK) {
                    startVpnService()
                } else {
                    Toast.makeText(this, "VPN 权限被拒绝", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_LOCATION -> updateUI()
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
        val location = LocationStore.getSelectedLocation(this)
        if (location == null) {
            Toast.makeText(this, "请先选择战区", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, VpnProxyService::class.java))
        Toast.makeText(this, "战区切换已启动", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopVpnService() {
        val intent = Intent(this, VpnProxyService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun updateUI() {
        val location = LocationStore.getSelectedLocation(this)
        if (location != null) {
            tvCurrentLocation.text = location.displayName
        } else {
            tvCurrentLocation.text = "未选择战区"
        }

        if (VpnProxyService.isRunning) {
            tvStatus.text = "● 运行中"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
            btnToggle.text = "停止"
        } else {
            tvStatus.text = "○ 已停止"
            tvStatus.setTextColor(0xFF9E9E9E.toInt())
            btnToggle.text = "启动"
        }
    }
}
