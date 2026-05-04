package com.warzone.changer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.warzone.changer.R
import com.warzone.changer.data.LocationStore
import com.warzone.changer.service.VpnProxyService

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var btnPickLocation: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLocation: TextView

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startVpn()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { updateUI() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        btnPickLocation = findViewById(R.id.btnPickLocation)
        tvStatus = findViewById(R.id.tvStatus)
        tvLocation = findViewById(R.id.tvLocation)

        btnToggle.setOnClickListener {
            if (VpnProxyService.isRunning) {
                stopVpn()
            } else {
                startVpn()
            }
        }

        btnPickLocation.setOnClickListener {
            startActivity(Intent(this, LocationPickerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        ContextCompat.registerReceiver(
            this, stateReceiver,
            IntentFilter("com.warzone.changer.VPN_STATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    private fun startVpn() {
        if (VpnProxyService.isRunning) return
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        startService(Intent(this, VpnProxyService::class.java))
        updateUI()
    }

    private fun stopVpn() {
        val stopIntent = Intent(this, VpnProxyService::class.java).apply { action = "STOP" }
        startService(stopIntent)
        updateUI()
    }

    private fun updateUI() {
        val running = VpnProxyService.isRunning
        tvStatus.text = if (running) "🟢 运行中" else "🔴 已停止"
        btnToggle.text = if (running) "停止" else "启动"

        val loc = LocationStore.get(this)
        tvLocation.text = if (loc != null && loc.isValid()) {
            "当前战区：${loc.province} ${loc.city} ${loc.district}"
        } else {
            "请先选择战区"
        }
    }
}
