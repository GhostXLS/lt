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
