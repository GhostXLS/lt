package com.unicom.monitor.recorder

import android.content.Context
import android.os.Environment
import android.util.Log
import com.unicom.monitor.model.Device
import com.unicom.monitor.network.ApiClient
import com.unicom.monitor.network.WsClient
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingTask(
    private val context: Context,
    private val apiClient: ApiClient,
    private val device: Device,
    private val cloudToken: String,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        const val TAG = "RecordingTask"
    }

    private var wsClient: WsClient? = null
    private var isRecording = false

    suspend fun start() {
        if (isRecording) return
        isRecording = true
        onStatusChanged("正在启动...")

        try {
            // 获取 WebSocket 地址
            val wsHost = apiClient.getWsHost(cloudToken, device.deviceId)
            if (wsHost.isEmpty()) {
                onStatusChanged("获取 WS 地址失败")
                isRecording = false
                return
            }

            // 获取中继服务器
            val relayServer = apiClient.getRelayIp(
                cloudToken,
                device.deviceId,
                device.channelNo
            )

            // 准备输出文件
            val baseDir = File(
                Environment.getExternalStorageDirectory(),
                "unicomMonitor/${device.name}"
            )
            if (!baseDir.exists()) baseDir.mkdirs()

            val timeFormat = SimpleDateFormat("yyyyMMdd/HHmmss", Locale.getDefault())
            val fileName = timeFormat.format(Date()) + ".flv"
            val outputFile = File(baseDir, fileName)

            onStatusChanged("录制中: ${outputFile.name}")

            // 启动 WebSocket 录制
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            wsClient = WsClient(
                client = okHttpClient,
                wsHost = wsHost,
                token = cloudToken,
                deviceId = device.deviceId,
                channelNo = device.channelNo,
                relayServer = relayServer,
                deviceName = device.name,
                outputFile = outputFile,
                onStatusChanged = onStatusChanged
            )
            wsClient?.start()

            // 保持运行
            while (isRecording) {
                delay(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            onStatusChanged("录制出错: ${e.message}")
        } finally {
            wsClient?.stop()
            wsClient = null
            isRecording = false
        }
    }

    fun stop() {
        isRecording = false
        wsClient?.stop()
    }
}
