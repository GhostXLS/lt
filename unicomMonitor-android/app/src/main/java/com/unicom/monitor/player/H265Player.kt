package com.unicom.monitor.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class H265Player(private val surface: Surface) {
    companion object {
        const val TAG = "H265Player"
        const val MIME_TYPE = "video/hevc"
    }

    private var decoder: MediaCodec? = null
    private var isPlaying = false
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun start() {
        if (isPlaying) return
        isPlaying = true

        try {
            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(getMediaFormat(), surface, null, 0)
                start()
            }
            Log.d(TAG, "H.265 decoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start decoder", e)
        }
    }

    fun stop() {
        isPlaying = false
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder", e)
        }
    }

    fun feedNalu(nalu: ByteArray) {
        if (!isPlaying || decoder == null) return
        if (nalu.size < 2) return

        try {
            val nalType = (nalu[0].toInt() shr 1) and 0x3F
            when (nalType) {
                32 -> { // VPS
                    vps = nalu
                    sendCodecConfig()
                }
                33 -> { // SPS
                    sps = nalu
                    sendCodecConfig()
                }
                34 -> { // PPS
                    pps = nalu
                    sendCodecConfig()
                }
                else -> {
                    sendFrame(nalu)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedNalu error", e)
        }
    }

    fun feedAnnexB(data: ByteArray) {
        var offset = 0
        while (offset < data.size - 4) {
            // 查找 Annex B start code (00 00 00 01)
            if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()
            ) {
                var end = offset + 4
                while (end < data.size - 3) {
                    if (data[end] == 0.toByte() && data[end + 1] == 0.toByte() &&
                        data[end + 2] == 0.toByte() && data[end + 3] == 1.toByte()
                    ) {
                        break
                    }
                    end++
                }
                val nalu = data.copyOfRange(offset + 4, end)
                feedNalu(nalu)
                offset = end
            } else {
                offset++
            }
        }
    }

    private fun sendCodecConfig() {
        if (vps == null || sps == null || pps == null) return
        val decoder = this.decoder ?: return

        val csd0 = ByteBuffer.wrap(combineNalus(vps!!, sps!!, pps!!))
        val format = MediaFormat.createVideoFormat(MIME_TYPE, 0, 0).apply {
            setByteBuffer("csd-0", csd0)
        }

        try {
            decoder.configure(format, surface, null, 0)
            Log.d(TAG, "Codec config sent (VPS/SPS/PPS)")
        } catch (e: Exception) {
            Log.e(TAG, "sendCodecConfig error", e)
        }
    }

    private fun sendFrame(nalu: ByteArray) {
        val decoder = this.decoder ?: return
        val inputBufferIndex = decoder.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()
            inputBuffer?.put(nalu)
            val pts = System.nanoTime() / 1000
            decoder.queueInputBuffer(inputBufferIndex, 0, nalu.size, pts, 0)
        } else {
            Log.w(TAG, "No input buffer available")
        }
    }

    private fun getMediaFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_WIDTH, 1920)
            setInteger(MediaFormat.KEY_HEIGHT, 1080)
            setInteger(MediaFormat.KEY_FRAME_RATE, 25)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BIT_RATE, 4000000)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
    }

    private fun combineNalus(vararg nalus: ByteArray): ByteArray {
        val totalSize = nalus.sumOf { it.size + 4 }
        val result = ByteArray(totalSize)
        var offset = 0
        for (nalu in nalus) {
            // 长度前缀 (4 bytes big-endian)
            result[offset++] = (nalu.size shr 24).toByte()
            result[offset++] = (nalu.size shr 16).toByte()
            result[offset++] = (nalu.size shr 8).toByte()
            result[offset++] = nalu.size.toByte()
            System.arraycopy(nalu, 0, result, offset, nalu.size)
            offset += nalu.size
        }
        return result
    }
}