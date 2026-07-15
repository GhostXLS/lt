package com.unicom.monitor.network

import android.content.Context
import com.unicom.monitor.util.CryptoUtils
import com.unicom.monitor.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookieStore = mutableMapOf<HttpUrl, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url] ?: emptyList()
            }
        })
        .build()

    companion object {
        const val VD_HOST = "https://vd-file.wojiazongguan.cn"
        const val PRODUCT_KEY = "3bd0c1bc-f50"
        const val SIGN_SECRET = "html5_open_api_check_secret"
        const val CHANNEL_NAME = "720p"
        const val APP_ID = "edop_unicom_7da41905"
        const val AUTO_LOGIN_KEY = "UnicomAppMiniProgramAutoLogin"
    }

    suspend fun refreshToken(tokenOnline: String, mobile: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val body = mapOf(
                "version" to "android@12.0900",
                "token_online" to tokenOnline
            )
            val resp = postForm(
                "https://loginxhm.10010.com/mobileService/onLine.htm",
                body
            )
            val code = resp["code"] as? String ?: ""
            if (code != "0") throw Exception("onLine error: $resp")
            val privateToken = (resp["private_token"] as? String) ?: ""
            val desMobile = (resp["desmobile"] as? String) ?: ""
            Pair(privateToken, desMobile)
        }

    suspend fun getTicketNative(privateToken: String): String = withContext(Dispatchers.IO) {
        val url =
            "https://m.client.10010.com/edop_ng/getTicketByNative?appId=$APP_ID&token=${
                java.net.URLEncoder.encode(privateToken, "UTF-8")
            }"
        val resp = get(url)
        val ticket = resp["ticket"] as? String ?: ""
        if (ticket.isEmpty()) throw Exception("ticket error: $resp")
        ticket
    }

    suspend fun getAutoLoginToken(ticket: String): String = withContext(Dispatchers.IO) {
        val reqSeq = CryptoUtils.randomDigits(5)
        val resTime = (System.currentTimeMillis()).toString()
        val sign = CryptoUtils.md5(AUTO_LOGIN_KEY + resTime + reqSeq + "wohome")

        val header = mapOf(
            "key" to AUTO_LOGIN_KEY,
            "resTime" to resTime,
            "reqSeq" to reqSeq,
            "channel" to "wohome",
            "version" to "",
            "sign" to sign
        )
        val bodyMap = mapOf(
            "header" to header,
            "body" to mapOf(
                "ticket" to ticket,
                "appId" to APP_ID,
                "clientId" to "1001000122"
            )
        )

        val req = Request.Builder()
            .url("https://iotpservice.smartont.net/wohome/dispatcher")
            .post(
                RequestBody.create(
                    "application/json".toMediaType(),
                    com.google.gson.Gson().toJson(bodyMap)
                )
            )
            .addHeader("Content-Type", "application/json")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.7151.115 Mobile Safari/537.36"
            )
            .build()

        client.newCall(req).execute().use { resp ->
            val respStr = resp.body?.string() ?: ""
            val result = com.google.gson.Gson().fromJson(respStr, Map::class.java)
            val rsp = (result as? Map<*, *>)?.get("RSP") as? Map<*, *>
            val data = rsp?.get("DATA") as? Map<*, *>
            val accessToken = data?.get("accessToken") as? String ?: ""
            if (accessToken.isEmpty()) throw Exception("dispatcher error: $result")
            accessToken
        }
    }

    suspend fun cloudLogin(mobile: String, accessToken: String): String =
        withContext(Dispatchers.IO) {
            val deviceId = CryptoUtils.randomDigits(16)
            val extra = """{"accessToken":"$accessToken","phone":"$mobile","deviceType":"WEB","deviceId":"$deviceId","appName":"smartHome","version":"0.0.1"}"""
            val payload = mapOf(
                "productKey" to PRODUCT_KEY,
                "account" to mobile,
                "loginType" to "WJJK",
                "extra" to extra
            )
            val resp = vdPost("/h5player/api/open/cloud/thirdLogin", payload)
            val data = resp["data"] as? Map<*, *>
            val cloudToken = data?.get("token") as? String ?: ""
            if (cloudToken.isEmpty()) throw Exception("thirdLogin error: $resp")
            cloudToken
        }

    suspend fun getDeviceList(token: String): List<Device> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "token" to token,
            "productKey" to PRODUCT_KEY,
            "settingCodes" to "[501,500,2067,1086,2045]"
        )
        val resp = vdPost("/h5player/api/open/esd/deviceList", payload)
        val data = resp["data"] as? Map<*, *>
        val devicesRaw = data?.get("devicelist") as? List<*>
        val devices = mutableListOf<Device>()
        devicesRaw?.forEach { d ->
            @Suppress("UNCHECKED_CAST")
            val dev = d as? Map<String, Any>
            if (dev != null) {
                val iplist = dev["iplist"] as? List<*>
                var relayHost = ""
                var relayPort = ""
                if (iplist != null && iplist.isNotEmpty()) {
                    val ip = iplist[0] as? Map<*, *>
                    relayHost = (ip?.get("relayhost") as? String) ?: ""
                    relayPort = (ip?.get("relayport") as? String) ?: ""
                }
                val region = (dev["region"] as? String) ?: ""
                val wsHost = region.removeSuffix("/cds")
                devices.add(
                    Device(
                        name = dev["devicename"] as? String ?: "",
                        deviceId = dev["deviceid"] as? String ?: "",
                        channelNo = dev["channelNo"] as? String ?: "1",
                        wsHost = wsHost,
                        relayServer = "$relayHost:$relayPort"
                    )
                )
            }
        }
        devices
    }

    suspend fun getWsHost(token: String, deviceId: String): String = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "productKey" to PRODUCT_KEY,
            "token" to token,
            "deviceId" to deviceId,
            "channelNo" to ""
        )
        val resp = vdPost("/h5player/api/open/config", payload)
        val data = resp["data"] as? Map<*, *>
        val wsServers = data?.get("html5PlayerWebSocketServer") as? Map<*, *>
        val keys = listOf("bluramsWo", "bluramsCN", "bluramsOS")
        for (key in keys) {
            val region = wsServers?.get(key) as? Map<*, *>
            val pro = region?.get("pro") as? String ?: ""
            if (pro.isNotEmpty()) return@withContext pro.removePrefix("wss://")
        }
        ""
    }

    suspend fun getRelayIp(token: String, deviceId: String, channelNo: String): String =
        withContext(Dispatchers.IO) {
            val payload = mapOf(
                "token" to token,
                "productKey" to PRODUCT_KEY,
                "channelNo" to channelNo,
                "deviceId" to deviceId,
                "channel" to CHANNEL_NAME
            )
            val resp = vdPost("/h5player/api/open/lookup/getRelayIp", payload)
            val data = resp["data"] as? Map<*, *>
            val ip = data?.get("privateip") as? String ?: ""
            val port = data?.get("relayport") as? String ?: ""
            "$ip:$port"
        }

    suspend fun vdPost(apiPath: String, payload: Map<String, Any?>): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val mutablePayload = payload.toMutableMap()
            mutablePayload["_timestamp"] = System.currentTimeMillis()
            mutablePayload["signature"] = vdSign(mutablePayload)

            val jsonBytes = com.google.gson.Gson().toJson(mutablePayload)
            val paramStr = CryptoUtils.encryptParam(jsonBytes)

            val formBody = FormBody.Builder()
                .add("_paramStr_", paramStr)
                .build()

            val url = VD_HOST + apiPath
            val req = Request.Builder()
                .url(url)
                .post(formBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("User-Agent", "ChinaUnicom/12.1200 (Android 16)")
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val plain = CryptoUtils.decryptParam(respBody)
                val result = com.google.gson.Gson().fromJson(plain, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                result as? Map<String, Any> ?: emptyMap()
            }
        }

    private suspend fun postForm(url: String, form: Map<String, String>): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val formBody = FormBody.Builder()
            form.forEach { (k, v) -> formBody.add(k, v) }
            val req = Request.Builder().url(url).post(formBody.build()).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val result = com.google.gson.Gson().fromJson(body, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                result as? Map<String, Any> ?: emptyMap()
            }
        }
    private suspend fun get(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val result = com.google.gson.Gson().fromJson(body, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            result as? Map<String, Any> ?: emptyMap()
        }
    }

    private fun vdSign(payload: Map<String, Any?>): String {
        val keys = payload.keys.filter { it != "signature" }.sorted()
        val sb = StringBuilder(SIGN_SECRET)
        for (k in keys) {
            val v = payload[k]
            sb.append(k).append("=").append(v)
        }
        return CryptoUtils.md5(sb.toString())
    }
}
