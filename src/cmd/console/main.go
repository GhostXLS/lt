//go:debug netdns=go

package main

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"unicomMonitor"
)

func main() {
	fmt.Println("开源：https://github.com/zgcwkjOpenProject/GO_UnicomMonitor")
	fmt.Println("作者：zgcwkj")
	fmt.Println("版本：20260527_001")
	fmt.Println("请尊重开源协议，保留作者信息！")
	fmt.Println("")

	config, videos := unicomMonitor.GetConfig()
	if config.Path == "" {
		config.Path = "./"
	}

	unicomMonitor.InitHTTPClient(config.Dns)
	unicomMonitor.InitWSDialer(config.Dns)

	if config.Token != "" && len(videos) == 0 {
		videos = unicomMonitor.AutoConfig(config.Token, config.Mobile)
		unicomMonitor.SaveVideoConfig(videos)
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	switch config.Mode {
	case "forward":
		unicomMonitor.RunForwardMode(&config, videos)
	default:
		unicomMonitor.RunRecordMode(&config, videos)
	}

	sig := <-sigChan
	fmt.Printf("收到信号 %v，正在退出...\n", sig)
	time.Sleep(500 * time.Millisecond)
	fmt.Println("程序已退出")
}
