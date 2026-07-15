# 联通监控 Android 版

将 Go 版 GO_UnicomMonitor 重写为 Android 原生应用 (Kotlin + OkHttp)。

## 项目结构

```
app/src/main/java/com/unicom/monitor/
├── MainActivity.kt           # 主界面 (输入 token + 手机号，开始/停止录制)
├── model/
│   ├── Config.kt             # 全局配置
│   └── Device.kt             # 设备信息
├── network/
│   ├── ApiClient.kt          # HTTP 接口 (refreshToken / cloudLogin / deviceList / 等)
│   └── WsClient.kt           # WebSocket 长连接 + FLV 数据过滤录制
├── recorder/
│   └── RecordingTask.kt      # 录制任务编排
└── service/
    └── MonitorService.kt     # 前台 Service (后台录制 + 通知栏)

util/
└── CryptoUtils.kt            # _paramStr_ 加解密 / MD5 / 随机数
```

## 核心流程

```
MainActivity
  └─> MonitorService (foreground)
        └─> ApiClient (refreshToken → getTicket → getAutoLoginToken → cloudLogin → deviceList)
              └─> WsClient (WebSocket → type=0 FLV 数据 → 写 .flv 文件)
```

## 构建方式

用 Android Studio 打开 `unicomMonitor-android` 文件夹，Sync Gradle 后直接 Run。

## 配置

编辑 `app/src/main/assets/config.json`:

```json
{
  "token": "你的 token_online",
  "mobile": "13800138000",
  "path": "/storage/emulated/0/unicomMonitor/videos/",
  "sleep": 60
}
```

## 注意事项

- 录制的 `.flv` 文件包含音频 (AAC/MP3) 和视频 (H.265)，可用 `ffmpeg -i file.flv -c copy out.mp4` 转换
- Android 10+ 使用分区存储，文件保存在 `Android/data/com.unicom.monitor/files/` 下
- 需要授予存储权限和通知权限
