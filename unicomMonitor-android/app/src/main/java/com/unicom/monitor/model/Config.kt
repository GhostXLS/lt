package com.unicom.monitor.model

data class Config(
    val host: String = ":25678",
    val user: String = "root:root",
    val path: String = "/storage/emulated/0/unicomMonitor/videos/",
    val sleep: Int = 60,
    val mobile: String = "",
    val token: String = "",
    val mode: String = "record",
    val dns: String = ""
)

data class Device(
    val name: String,
    val size: Int = 10,
    val count: Int = 7,
    val splitMin: Int = 10,
    val wsHost: String = "",
    val deviceId: String = "",
    val channelNo: String = "1",
    val shareId: String = "",
    val token: String = "",
    val relayServer: String = ""
)
