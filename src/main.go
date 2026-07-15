//go:debug netdns=go

package main

import (
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
	}
}

// 主函数
func main() {
	FmtPrint("开源：https://github.com/zgcwkjOpenProject/GO_UnicomMonitor")
	FmtPrint("作者：zgcwkj")
	FmtPrint("版本：20260527_001")
	FmtPrint("请尊重开源协议，保留作者信息！")
	FmtPrint("")

	config, videos := GetConfig()
	if config.Path == "" {
		config.Path = "./"
	}

	initHTTPClient(config.Dns)

	if config.Token != "" && len(videos) == 0 {
		videos = AutoConfig(config.Token, config.Mobile)
		SaveVideoConfig(videos)
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	switch config.Mode {
	case "forward":
		go RunForwardMode(&config, videos)
	default:
		go RunRecordMode(&config, videos)
	}

	sig := <-sigChan
	FmtPrint("收到信号 %v，正在退出...", sig)
	time.Sleep(500 * time.Millisecond)
	FmtPrint("程序已退出")
}

// RunRecordMode 录制模式
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
