package com.unicom.monitor.rtsp

import android.util.Log
import java.io.*
import java.net.*
import java.util.concurrent.*

class SimpleRtspServer(
    private val port: Int = 554,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit
) {
    companion object {
        const val TAG = "SimpleRtspServer"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var isRunning = false

    // 简单的会话管理
    private val sessions = ConcurrentHashMap<String, RtspSession>()
    private var sessionIdCounter = 0

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            serverSocket = ServerSocket(port)
            onStart()
            Log.d(TAG, "RTSP server started on port $port")

            while (isRunning) {
                val client = serverSocket?.accept()
                if (client != null) {
                    executor.execute { handleClient(client) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP server error", e)
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        executor.shutdownNow()
        sessions.clear()
        onStop()
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 30000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

        try {
            var session: RtspSession? = null
            var cseq = 1

            while (isRunning) {
                val request = readRequest(reader) ?: break
                val method = request.method
                val path = request.path

                Log.d(TAG, "RTSP $method $path")

                when (method) {
                    "OPTIONS" -> {
                        sendResponse(writer, 200, "OK", mapOf(
                            "CSeq" to cseq++.toString(),
                            "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"
                        ))
                    }
                    "DESCRIBE" -> {
                        val sdp = generateSdp(path)
                        val response = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: ${cseq++}\r\n" +
                                "Content-Type: application/sdp\r\n" +
                                "Content-Length: ${sdp.length}\r\n" +
                                "\r\n" +
                                sdp
                        writer.write(response)
                        writer.flush()
                    }
                    "SETUP" -> {
                        val transport = request.headers["Transport"] ?: "RTP/AVP;unicast"
                        val sessionId = "session-${++sessionIdCounter}"
                        session = RtspSession(sessionId, path)
                        sessions[sessionId] = session

                        val response = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: ${cseq++}\r\n" +
                                "Transport: $transport;client_port=8000-8001;server_port=9000-9001\r\n" +
                                "Session: $sessionId\r\n" +
                                "\r\n"
                        writer.write(response)
                        writer.flush()
                    }
                    "PLAY" -> {
                        val sessionId = request.headers["Session"] ?: ""
                        session = sessions[sessionId]
                        if (session != null) {
                            session.isPlaying = true
                        }

                        val response = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: ${cseq++}\r\n" +
                                "Session: $sessionId\r\n" +
                                "RTP-Info: url=rtsp://localhost:$port/${request.path};seq=0\r\n" +
                                "\r\n"
                        writer.write(response)
                        writer.flush()
                    }
                    "TEARDOWN" -> {
                        val sessionId = request.headers["Session"] ?: ""
                        sessions.remove(sessionId)
                        sendResponse(writer, 200, "OK", mapOf(
                            "CSeq" to cseq++.toString()
                        ))
                    }
                    else -> {
                        sendResponse(writer, 501, "Not Implemented", mapOf(
                            "CSeq" to cseq++.toString()
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun readRequest(reader: BufferedReader): RtspRequest? {
        val requestLine = reader.readLine() ?: return null
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(" ")
        if (parts.size < 3) return null

        val method = parts[0]
        val path = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIndex = line.indexOf(":")
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }

        return RtspRequest(method, path, headers)
    }

    private fun sendResponse(
        writer: BufferedWriter,
        statusCode: Int,
        statusText: String,
        headers: Map<String, String>
    ) {
        writer.write("RTSP/1.0 $statusCode $statusText\r\n")
        for ((key, value) in headers) {
            writer.write("$key: $value\r\n")
        }
        writer.write("\r\n")
        writer.flush()
    }

    private fun generateSdp(path: String): String {
        return "v=0\r\n" +
                "o=- 0 0 IN IP4 127.0.0.1\r\n" +
                "s=UniCom Monitor\r\n" +
                "c=IN IP4 0.0.0.0\r\n" +
                "t=0 0\r\n" +
                "a=control:*\r\n" +
                "m=video 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 H265/90000\r\n" +
                "a=control:track0\r\n" +
                "a=fmtp:96 profile-id=1\r\n"
    }

    fun sendVideoData(path: String, nalu: ByteArray) {
        // 简化：实际应 RTP 封装并通过 UDP 发送
        // 这里仅演示接口
    }
}

data class RtspRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>
)

data class RtspSession(
    val id: String,
    val path: String,
    var isPlaying: Boolean = false
)
