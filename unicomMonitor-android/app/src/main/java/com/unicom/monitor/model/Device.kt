package com.unicom.monitor.model

data class Device(
    val name: String,
    val deviceId: String = "",
    val channelNo: String = "1",
    val wsHost: String = "",
    val relayServer: String = "",
    val onlineStatus: String = "",
    val region: String = "",
    var token: String = ""
)
