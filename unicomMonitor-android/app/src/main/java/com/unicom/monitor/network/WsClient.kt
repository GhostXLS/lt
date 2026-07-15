package com.unicom.monitor.network

import android.util.Log
import com.unicom.monitor.util.CryptoUtils
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class WsClient(
    private val client: OkHttpClient,
    private val wsHost: String,
    private val token: String,
    private val deviceId: String,
    private val channelNo: String,
    private val relayServer: String,
    private val deviceName: String,
    private val outputFile: File,
    private val onStatusChanged: (String) -> Unit,
    private val onVideoFrame: ((ByteArray) -> Unit)? = null
) {
    companion object {
        const val PRODUCT_KEY = "3bd0c1bc-f50"
        const val CHANNEL_NAME = "720p"
        const val TAG = "WsClient"
    }

    private var ws: WebSocket? = null
    private var recordingJob: Job? = null
    private var fileSink: okio.BufferedSink? = null
    private var fileBuffer: okio.BufferedSink? = null
    private var isRecording = false

    fun start() {
        if (isRecording) return
        isRecording = true

        val url = "wss://$wsHost/h5player/live"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "ChinaUnicom/12.1200 (Android 16)")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                onStatusChanged("已连接")
                val paramMsg = buildParamMsg()
                val message = "_paramStr_=$paramMsg"
                val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
                webSocket.send(okio.ByteString.of(*messageBytes))
                Log.d(TAG, "Sent paramStr")
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size <= 1) return
                    // 首字节: 0 = FLV_STREAM_DATA, 4 = RESPONSE (JSON), 其他跳过
                    if (data[0] != 0.toByte()) return

                    // 写入 FLV 数据 (跳过首字节)
                    if (fileSink == null) {
                        fileSink = outputFile.sink().buffer()
                        fileBuffer = fileSink
                    }
                    val flvData = data.copyOfRange(1, data.size)
                    fileBuffer?.write(flvData)

                    // 回调视频帧 (用于预览/RTSP)
                    onVideoFrame?.invoke(flvData)
                } catch (e: Exception) {
                    Log.e(TAG, "onMessage error", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                onStatusChanged("连接关闭")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed")
                onStatusChanged("已断开")
                closeFile()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                onStatusChanged("连接失败: ${t.message}")
                closeFile()
            }
        })
    }

    fun stop() {
        isRecording = false
        ws?.close(1000, "user stop")
        ws = null
        closeFile()
        onStatusChanged("已停止")
    }

    private fun closeFile() {
        try {
            fileBuffer?.close()
            fileBuffer = null
            fileSink = null
        } catch (e: Exception) {
            Log.e(TAG, "closeFile error", e)
        }
    }

    private fun buildParamMsg(): String {
        val payload = hashMapOf(
            "requestTime" to System.currentTimeMillis(),
            "productKey" to PRODUCT_KEY,
            "deviceId" to deviceId,
            "channelNo" to channelNo,
            "token" to token,
            "hasAudio" to "true",
            "region" to "",
            "isPermanentStorage" to "false",
            "channel" to CHANNEL_NAME,
            "deviceName" to deviceName,
            "clientId" to "WEBCLIENT_H5_" + CryptoUtils.randomDigits(22) + System.currentTimeMillis(),
            "shareId" to "",
            "relayServer" to relayServer,
            "isSDCardPlayback" to "false",
            "preConnect" to "false",
            "releaseVersion" to "H5PlayerServer_220719_B1072_4a25458_xml2json",
            "isSupportWASM" to "1"
        )
        val json = com.google.gson.Gson().toJson(payload)
        return CryptoUtils.encryptParam(json)
    }
}
