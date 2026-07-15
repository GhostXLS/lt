package com.unicom.monitor

import android.content.*
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.unicom.monitor.model.Config
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "MainActivity"
        const val ACTION_STATUS = "com.unicom.monitor.action.STATUS"
        const val EXTRA_STATUS = "status"
    }

    private lateinit var etTokenOnline: EditText
    private lateinit var etMobile: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private var config: Config? = null
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(EXTRA_STATUS) ?: return
            tvStatus.text = status
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTokenOnline = findViewById(R.id.etTokenOnline)
        etMobile = findViewById(R.id.etMobile)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        loadConfig()

        btnStart.setOnClickListener {
            val tokenOnline = etTokenOnline.text.toString().trim()
            val mobile = etMobile.text.toString().trim()
            if (tokenOnline.isEmpty() || mobile.isEmpty()) {
                tvStatus.text = "请输入 token_online 和手机号"
                return@setOnClickListener
            }
            startRecording(tokenOnline, mobile, 0)
        }

        btnStop.setOnClickListener {
            stopRecording()
        }

        // 注册状态广播接收器
        val filter = IntentFilter(ACTION_STATUS)
        registerReceiver(statusReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }

    private fun loadConfig() {
        try {
            assets.open("config.json").use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    val json = reader.readText()
                    config = Gson().fromJson(json, Config::class.java)
                    if (config != null) {
                        etTokenOnline.setText(config!!.token)
                        etMobile.setText(config!!.mobile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadConfig error", e)
        }
    }

    private fun startRecording(tokenOnline: String, mobile: String, deviceIndex: Int) {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra(MonitorService.EXTRA_TOKEN_ONLINE, tokenOnline)
            putExtra(MonitorService.EXTRA_MOBILE, mobile)
            putExtra(MonitorService.EXTRA_DEVICE_INDEX, deviceIndex)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvStatus.text = "正在启动录制..."
    }

    private fun stopRecording() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP
        }
        startService(intent)
        tvStatus.text = "已停止"
    }
}
