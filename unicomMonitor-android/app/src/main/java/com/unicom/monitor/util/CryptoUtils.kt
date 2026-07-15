package com.unicom.monitor.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object CryptoUtils {
    private val secureRandom = SecureRandom()

    fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(text.toByteArray(StandardCharsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun randomDigits(length: Int): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes.map { '0' + (it.toInt() and 0xFF) % 10 }.joinToString("")
    }

    fun jsEscape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '*' || c == '@' || c == '-' || c == '_' ||
                c == '+' || c == '.' || c == '/' -> sb.append(c)
                c.code <= 0xFF -> sb.append("%%")
                    .append("%02X".format(c.code))
                else -> sb.append("%%u")
                    .append("%04X".format(c.code))
            }
        }
        return sb.toString()
    }

    fun jsUnescape(s: String): String {
        var result = s
        while (true) {
            val idx = result.indexOf("%u")
            if (idx == -1 || idx + 6 > result.length) break
            val cp = result.substring(idx + 2, idx + 6).toInt(16)
            result = result.substring(0, idx) + cp.toChar() + result.substring(idx + 6)
        }
        val sb = StringBuilder()
        var i = 0
        while (i < result.length) {
            if (result[i] == '%' && i + 2 < result.length) {
                val hv = result.substring(i + 1, i + 3).toIntOrNull(16)
                if (hv != null) {
                    sb.append(hv.toByte().toInt().toChar())
                    i += 3
                    continue
                }
            }
            sb.append(result[i])
            i++
        }
        return sb.toString()
    }

    fun encryptParam(plaintext: String): String {
        val runes = plaintext.toCharArray()
        val half = runes.size / 2
        val swapped = (runes.slice(half until runes.size) + runes.slice(0 until half))
            .joinToString("")
        val escaped = jsEscape(swapped)
        val b64 = android.util.Base64.encodeToString(
            escaped.toByteArray(StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val prefixInt = secureRandom.nextLong().mod(9000000000L) + 1000000000L
        val prefixBytes = "%010d".format(prefixInt).toByteArray(StandardCharsets.UTF_8)
        val prefixB64 = android.util.Base64.encodeToString(prefixBytes, android.util.Base64.NO_WRAP)
        return prefixB64.take(10) + b64
    }

    fun decryptParam(encrypted: String): String {
        if (encrypted.isEmpty()) return encrypted
        val withoutPrefix = encrypted.substring(10)
        val decoded = try {
            android.util.Base64.decode(withoutPrefix, android.util.Base64.NO_WRAP)
                .toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
        val urlDecoded = jsUnescape(decoded)
        val runes = urlDecoded.toCharArray()
        val l = runes.size
        val half = l / 2
        return (runes.slice(l - half until l) + runes.slice(0 until l - half))
            .joinToString("")
    }
}
