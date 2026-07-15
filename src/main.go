//go:build !gui

package unicomMonitor

import (
	"net"
	"time"
)

func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
	}
}

func RunRecordMode(config *Config, videos []Video) {
	// 启动录制协程
	FmtPrint("启动录制服务，存储路径：" + config.Path)
	for i := range videos {
		go GoRecording(config, &videos[i])
	}

	// 删除旧文件协程
	go func() {
		for {
			timeout := time.Duration(config.Sleep)
			time.Sleep(timeout * time.Second)
			for i := range videos {
				DeleteOldFiles(config, &videos[i])
			}
		}
	}()

	// 运行类型
	if config.Host == "" {
		// 后台运行
		for {
			FmtPrint("程序运行正常")
			timeout := time.Duration(config.Sleep)
			time.Sleep(timeout * time.Second)
		}
	} else {
		// 网站服务
		FmtPrint("启动网站服务：" + config.Host)
		StartHttp(config)
	}
}

// RunForwardMode 转发模式
func RunForwardMode(config *Config, videos []Video) {
	// 启动 RTSP 服务
	FmtPrint("启动 RTSP 服务：" + config.Host)
	StartRtsp(config, videos)
}
