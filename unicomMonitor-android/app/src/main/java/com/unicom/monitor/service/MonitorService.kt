package com.unicom.monitor.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.unicom.monitor.MainActivity
import com.unicom.monitor.R
import com.unicom.monitor.model.Device
import com.unicom.monitor.network.ApiClient
import com.unicom.monitor.recorder.RecordingTask
import kotlinx.coroutines.*

class MonitorService : Service() {
    companion object {
        const val TAG = "MonitorService"
        const val CHANNEL_ID = "unicom_monitor_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.unicom.monitor.action.START"
        const val ACTION_STOP = "com.unicom.monitor.action.STOP"
        const val EXTRA_TOKEN_ONLINE = "token_online"
        const val EXTRA_MOBILE = "mobile"
        const val EXTRA_DEVICE_INDEX = "device_index"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_TOKEN = "device_token"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var apiClient: ApiClient? = null
    private var recordingTask: RecordingTask? = null
    private var statusCallback: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        apiClient = ApiClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tokenOnline = intent.getStringExtra(EXTRA_TOKEN_ONLINE) ?: ""
                val mobile = intent.getStringExtra(EXTRA_MOBILE) ?: ""
                val deviceIndex = intent.getIntExtra(EXTRA_DEVICE_INDEX, 0)
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                val deviceToken = intent.getStringExtra(EXTRA_DEVICE_TOKEN) ?: ""
                startRecording(tokenOnline, mobile, deviceIndex, deviceId, deviceToken)
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        recordingTask?.stop()
    }

    private fun startRecording(
        tokenOnline: String,
        mobile: String,
        deviceIndex: Int,
        deviceId: String,
        deviceToken: String
    ) {
        scope.launch {
            try {
                updateNotification("正在登录...")
                val cloudToken: String = if (deviceToken.isNotEmpty()) {
                    deviceToken
                } else {
                    val (privateToken, _) = apiClient!!.refreshToken(tokenOnline, mobile)
                    val ticket = apiClient!!.getTicketNative(privateToken)
                    val accessToken = apiClient!!.getAutoLoginToken(ticket)
                    apiClient!!.cloudLogin(mobile, accessToken)
                }

                val devices = apiClient!!.getDeviceList(cloudToken)
                val device: Device? = when {
                    deviceId.isNotEmpty() && devices.any { it.deviceId == deviceId } -> {
                        devices.first { it.deviceId == deviceId }
                    }
                    deviceIndex < devices.size -> devices[deviceIndex]
                    else -> null
                }

                if (device == null) {
                    updateNotification("设备未找到")
                    return@launch
                }

                statusCallback = { status ->
                    updateNotification(status)
                }

                recordingTask = RecordingTask(
                    context = this@MonitorService,
                    apiClient = apiClient!!,
                    device = device,
                    cloudToken = cloudToken,
                    onStatusChanged = { status ->
                        updateNotification(status)
                    }
                )
                recordingTask?.start()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording error", e)
                updateNotification("启动失败: ${e.message}")
            }
        }
    }

    private fun stopRecording() {
        recordingTask?.stop()
        recordingTask = null
    }

    private fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("联通监控录制")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)

        // 广播状态给 Activity
        val intent = Intent(MainActivity.ACTION_STATUS).apply {
            putExtra(MainActivity.EXTRA_STATUS, content)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录制服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
