package com.unicom.monitor.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.unicom.monitor.R
import com.unicom.monitor.model.Device
import com.unicom.monitor.network.ApiClient
import kotlinx.coroutines.*

class DeviceListActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: Button
    private lateinit var adapter: DeviceAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var apiClient: ApiClient? = null
    private var cloudToken: String = ""
    private var tokenOnline: String = ""
    private var mobile: String = ""

    companion object {
        const val EXTRA_TOKEN_ONLINE = "token_online"
        const val EXTRA_MOBILE = "mobile"
        const val EXTRA_CLOUD_TOKEN = "cloud_token"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        recyclerView = findViewById(R.id.recyclerView)
        tvStatus = findViewById(R.id.tvStatus)
        btnBack = findViewById(R.id.btnBack)

        adapter = DeviceAdapter { device ->
            // 点击设备，启动录制
            val intent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START
                putExtra(MonitorService.EXTRA_TOKEN_ONLINE, tokenOnline)
                putExtra(MonitorService.EXTRA_MOBILE, mobile)
                putExtra(MonitorService.EXTRA_DEVICE_ID, device.deviceId)
                putExtra(MonitorService.EXTRA_DEVICE_TOKEN, cloudToken)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "正在启动: ${device.name}"
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnBack.setOnClickListener {
            finish()
        }

        tokenOnline = intent.getStringExtra(EXTRA_TOKEN_ONLINE) ?: ""
        mobile = intent.getStringExtra(EXTRA_MOBILE) ?: ""
        cloudToken = intent.getStringExtra(EXTRA_CLOUD_TOKEN) ?: ""

        apiClient = ApiClient(this)

        if (tokenOnline.isNotEmpty() && mobile.isNotEmpty()) {
            loadDevices()
        } else {
            tvStatus.text = "缺少登录信息"
        }
    }

    private fun loadDevices() {
        scope.launch {
            try {
                tvStatus.text = "正在登录..."
                val (privateToken, _) = apiClient!!.refreshToken(tokenOnline, mobile)
                val ticket = apiClient!!.getTicketNative(privateToken)
                val accessToken = apiClient!!.getAutoLoginToken(ticket)
                cloudToken = apiClient!!.cloudLogin(mobile, accessToken)

                tvStatus.text = "正在获取设备列表..."
                val devices = withContext(Dispatchers.IO) {
                    apiClient!!.getDeviceList(cloudToken)
                }
                if (devices.isEmpty()) {
                    tvStatus.text = "未找到设备"
                } else {
                    adapter.submitList(devices)
                    tvStatus.text = "共 ${devices.size} 个设备"
                }
            } catch (e: Exception) {
                tvStatus.text = "获取设备失败: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
