package main

import (
	"context"
	"crypto/tls"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

var wsDialer *websocket.Dialer

func initWSDialer(dns string) {
	if dns == "" {
		dns = "8.8.8.8:53"
	}
	if !strings.Contains(dns, ":") {
		dns = net.JoinHostPort(dns, "53")
	}

	wsDialer = &websocket.Dialer{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
		NetDialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			d := net.Dialer{
				Resolver: &net.Resolver{
					PreferGo: true,
					Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
						dd := net.Dialer{}
						return dd.DialContext(ctx, network, dns)
					},
				},
			}
			return d.DialContext(ctx, network, addr)
		},
	}
}

// 开始录制
func GoRecording(config *Config, video *Video) {
	// 临时变量
	tempPath := filepath.Join(config.Path, video.Name)
	// 断开后重连
	for {
		// 连接服务器传输数据
		bytes := linkServer(video)
		// 检查数据
		if len(bytes) == 0 {
			FmtPrint(video.Name + " 连接失败，稍后重连(" + strconv.Itoa(config.Sleep) + "秒)")
			timeout := time.Duration(config.Sleep)
			time.Sleep(timeout * time.Second)
			continue
		}
		// 文件名称
		fileName := getFileName(tempPath) + ".flv"
		// 保存文件
		saveFile(fileName, &bytes)
		// 录制完成
		FmtPrint(video.Name + " 录制完成：" + fileName)
	}
}

// 连接服务器
func linkServer(video *Video) []byte {
	bytes := []byte{}
	uri := url.URL{
		Scheme: "wss",
		Host:   video.WsHost,
		Path:   "/h5player/live",
	}
	// 跳过证书验证 - 使用全局 dialer
	// 请求头
	headers := http.Header{}
	headers.Set("User-Agent", "ChinaUnicom/12.1200 (Android 16)")
	// 发起连接
	conn, _, err := wsDialer.Dial(uri.String(), headers)
	if err != nil {
		FmtPrint(video.Name+" 无法连接: %v", err)
		return bytes
	}
	defer conn.Close()

	// 发送消息
	paramMsg := BuildParamMsg(video.Token, video.DeviceId, video.ChannelNo, video.RelayServer, video.Name)
	message := "_paramStr_=" + paramMsg
	// FmtPrint(DecryptParam(paramMsg))
	err = conn.WriteMessage(websocket.TextMessage, []byte(message))
	if err != nil {
		FmtPrint(video.Name+" 发送消息失败: %v", err)
		return bytes
	}
	FmtPrint(video.Name + " 已连接，开始录制")

		// 接收消息
	for {
		_, response, err := conn.ReadMessage()
		if err != nil {
			FmtPrint(video.Name+" 连接断开: %v", err)
			return bytes
		}
		if len(response) <= 1 {
			continue
		}
		// 跳过 JSON 控制消息 (URL 编码的 JSON 以 %7B 开头)
		if isJSONControl(response) {
			continue
		}
		// 去掉首字节(消息类型标记)，保留 FLV 裸数据
		flvData := response[1:]
		bytes = append(bytes, flvData...)
		if len(bytes) > 1024*1024*video.Size {
			return bytes
		}
	}
}

func isJSONControl(data []byte) bool {
	limit := len(data)
	if limit > 50 {
		limit = 50
	}
	return strings.Contains(string(data[:limit]), "%7B")
}

// 获取文件名称
func getFileName(dirPath string) string {
	// 添加日期文件夹
	dateFolder := time.Now().Format("20060102")
	fullPath := filepath.Join(dirPath, dateFolder)
	// 检查文件夹是否存在
	if _, err := os.Stat(fullPath); os.IsNotExist(err) {
		if err := os.MkdirAll(fullPath, 0755); err != nil {
			FmtPrint("创建文件夹失败：", err)
			os.Exit(0)
		}
	}
	fileName := time.Now().Format("150405")
	return filepath.Join(fullPath, fileName)
}

// 保存文件
func saveFile(fileName string, bytes *[]byte) {
	file, err := os.OpenFile(fileName, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0666)
	if err != nil {
		FmtPrint("保存文件失败: ", err)
		os.Exit(0)
	}
	defer file.Close()
	file.Write(*bytes)
}

// 删除文件夹下的旧文件夹
func DeleteOldFiles(config *Config, video *Video) {
	// 临时变量
	dirPath := filepath.Join(config.Path, video.Name)
	foldersToKeep := video.Count
	// 读取文件夹
	var folders []fs.FileInfo
	entries, _ := os.ReadDir(dirPath)
	for _, entry := range entries {
		if entry.IsDir() {
			info, _ := os.Stat(filepath.Join(dirPath, entry.Name()))
			folders = append(folders, info)
		}
	}
	// 检查文件夹数量
	if len(folders) <= foldersToKeep {
		return
	}
	// 按时间排序
	sort.Slice(folders, func(i, j int) bool {
		return folders[i].ModTime().After(folders[j].ModTime())
	})
	// 删除最旧的文件夹
	for i := foldersToKeep; i < len(folders); i++ {
		oldFolder := filepath.Join(dirPath, folders[i].Name())
		_ = os.RemoveAll(oldFolder)
	}
}
