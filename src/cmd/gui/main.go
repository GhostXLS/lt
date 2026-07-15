package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"unicomMonitor"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

type RecordingStatus struct {
	DeviceName string
	FilePath   string
	Size       int64
	Running    bool
	Error      string
}

var (
	state      unicomMonitor.AppState
	statuses   = make(map[string]*RecordingStatus)
	statusMu   sync.RWMutex
	recordings = make(map[string]context.CancelFunc)
	recordMu   sync.RWMutex
	monitorWin fyne.Window
	statusCard *widget.Card
	recordBtn  *widget.Button
	fileList   *widget.List
)

func addStatus(s *RecordingStatus) {
	statusMu.Lock()
	statuses[s.DeviceName] = s
	statusMu.Unlock()
}

func getStatus(name string) *RecordingStatus {
	statusMu.RLock()
	defer statusMu.RUnlock()
	return statuses[name]
}

func startRecording(dev unicomMonitor.DeviceInfo) {
	recordMu.Lock()
	if _, exists := recordings[dev.DeviceName]; exists {
		recordMu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	recordings[dev.DeviceName] = cancel
	recordMu.Unlock()

	s := &RecordingStatus{
		DeviceName: dev.DeviceName,
		Running:    true,
	}
	addStatus(s)
	refreshFileList()

	go func() {
		defer func() {
			recordMu.Lock()
			delete(recordings, dev.DeviceName)
			recordMu.Unlock()
			if s.Running {
				s.Running = false
				refreshFileList()
			}
		}()

		recordingLoop(ctx, dev, s)
	}()
}

func stopRecording(deviceName string) {
	recordMu.RLock()
	cancel, exists := recordings[deviceName]
	recordMu.RUnlock()
	if exists {
		cancel()
	}
}

func recordingLoop(ctx context.Context, video unicomMonitor.DeviceInfo, s *RecordingStatus) {
	tempPath := state.SavePath
	if tempPath == "" {
		tempPath, _ = os.UserHomeDir()
		tempPath = filepath.Join(tempPath, " recordings")
	}

	videoDir := filepath.Join(tempPath, video.DeviceName)
	if err := os.MkdirAll(videoDir, 0755); err != nil {
		s.Error = "创建目录失败: " + err.Error()
		updateStatusUI()
		return
	}

	for {
		select {
		case <-ctx.Done():
			s.Running = false
			s.Error = "已停止录制"
			updateStatusUI()
			return
		default:
		}

		videoForServer := unicomMonitor.Video{
			Name:        video.DeviceName,
			Size:        10,
			Count:       10,
			WsHost:      video.Region,
			DeviceId:    video.DeviceId,
			ChannelNo:   video.ChannelNo,
			Token:       video.Token,
			RelayServer: video.RelayServer,
		}

		bytes, err := unicomMonitor.LinkServer(videoForServer)
		if err != nil {
			s.Error = "连接失败: " + err.Error()
			s.Running = false
			updateStatusUI()
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}

		s.Error = ""
		s.Running = true
		updateStatusUI()

		fileName := unicomMonitor.GetFileName(videoDir)
		s.FilePath = fileName

		n, err := unicomMonitor.SaveFile(fileName+".hevc", bytes)
		if err != nil {
			s.Error = "保存失败: " + err.Error()
			s.Running = false
		} else {
			s.Size += n
			s.Running = false
		}
		updateStatusUI()
		refreshFileList()

		select {
		case <-ctx.Done():
			s.Running = false
			s.Error = "已停止录制"
			updateStatusUI()
			return
		case <-time.After(1 * time.Second):
		}
	}
}

func updateStatusUI() {
	if monitorWin == nil || statusCard == nil {
		return
	}

	s := getStatus(monitorWin.Title())
	if s == nil {
		return
	}

	statusText := fmt.Sprintf("状态: %s\n文件: %s\n大小: %.2f MB",
		map[bool]string{true: "录制中", false: "已停止"}[s.Running],
		filepath.Base(s.FilePath),
		float64(s.Size)/1024/1024,
	)
	if s.Error != "" {
		statusText += "\n" + s.Error
	}

	fyne.Do(func() {
		statusCard.SetContent(widget.NewLabel(statusText))
	})
}

func refreshFileList() {
	if fileList == nil {
		return
	}

	files, _ := listRecordFiles()
	fyne.Do(func() {
		fileList.Length = func() int { return len(files) }
		fileList.UpdateItem = func(id widget.ListItemID, item fyne.CanvasObject) {
			if int(id) < len(files) {
				item.(*widget.Label).SetText(files[id])
			}
		}
		fileList.Refresh()
	})
}

func listRecordFiles() []string {
	searchPath := state.SavePath
	if searchPath == "" {
		searchPath, _ = os.UserHomeDir()
		searchPath = filepath.Join(searchPath, " recordings")
	}

	var files []string
	entries, err := os.ReadDir(searchPath)
	if err != nil {
		return files
	}

	for _, e := range entries {
		if e.IsDir() {
			sub, _ := os.ReadDir(filepath.Join(searchPath, e.Name()))
			for _, f := range sub {
				if !f.IsDir() {
					files = append(files, e.Name()+"/"+f.Name())
				}
			}
		}
	}
	return files
}

func main() {
	unicomMonitor.InitHTTPClient("")
	unicomMonitor.InitWSDialer("")

	a := app.NewWithID("com.unicom.monitor.gui")
	a.Settings().SetTheme(theme.LightTheme())

	w := a.NewWindow("联通摄像头监控")
	w.Resize(fyne.NewSize(1024, 720))

	home, _ := os.UserHomeDir()
	state.SavePath = filepath.Join(home, " recordings")

	go func() {
		time.Sleep(150 * time.Millisecond)
		showLoginScreen(w)
	}()

	w.ShowAndRun()
}

func showLoginScreen(w fyne.Window) {
	w.SetTitle("联通摄像头监控 - 登录")

	tokenEntry := widget.NewEntry()
	tokenEntry.SetPlaceHolder("请输入 token_online")

	mobileEntry := widget.NewEntry()
	mobileEntry.SetPlaceHolder("请输入手机号")

	pathEntry := widget.NewEntry()
	pathEntry.SetText(state.SavePath)
	pathEntry.Disable = true

	pathBtn := widget.NewButtonWithIcon("", theme.FolderOpenIcon(), func() {
		dialog.ShowFolderOpen(func(uri fyne.URIReadCloser, err error) {
			if err == nil && uri != nil {
				state.SavePath = uri.URI().Path()
				pathEntry.SetText(state.SavePath)
			}
		}, w)
	})

	statusLabel := widget.NewLabel("")

	loginBtn := widget.NewButtonWithIcon("登录并获取设备", theme.LoginIcon(), func() {
		token := tokenEntry.Text
		mobile := mobileEntry.Text
		if token == "" || mobile == "" {
			statusLabel.SetText("请输入 token 和手机号")
			return
		}

		statusLabel.SetText("正在登录...")
		loginBtn.Disable()

		go func() {
			devices, err := doLogin(token, mobile)
			fyne.Do(func() {
				loginBtn.Enable()
				if err != nil {
					statusLabel.SetText("登录失败: " + err.Error())
					return
				}
				state.Token = token
				state.Mobile = mobile
				state.Devices = devices
				showDeviceListScreen(w)
			})
		}()
	})

	form := container.NewVBox(
		widget.NewLabel("联通摄像头监控"),
		widget.NewSeparator(),
		widget.NewLabel("Token (token_online):"),
		tokenEntry,
		widget.NewLabel("手机号:"),
		mobileEntry,
		widget.NewLabel("录制保存路径:"),
		container.NewHBox(pathEntry, pathBtn),
		statusLabel,
		loginBtn,
	)

	w.SetContent(container.NewCenter(
		container.NewVBox(
			widget.NewCard("用户登录", "", form),
		),
	))
}

func showDeviceListScreen(w fyne.Window) {
	monitorWin = w
	w.SetTitle("设备列表")
	w.Resize(fyne.NewSize(900, 600))

	backBtn := widget.NewButtonWithIcon("返回", theme.NavigateBackIcon(), func() {
		showLoginScreen(w)
	})

	refreshBtn := widget.NewButtonWithIcon("刷新", theme.ViewRefreshIcon(), func() {
		go func() {
			devices, err := doLogin(state.Token, state.Mobile)
			fyne.Do(func() {
				if err != nil {
					dialog.ShowError(err, w)
					return
				}
				state.Devices = devices
				showDeviceListScreen(w)
			})
		}()
	})

	if len(state.Devices) == 0 {
		w.SetContent(container.NewBorder(container.NewHBox(backBtn, refreshBtn), nil, nil, nil,
			widget.NewLabel("未发现任何设备")))
		return
	}

	list := widget.NewList(
		func() int { return len(state.Devices) },
		func() fyne.CanvasObject {
			return container.NewHBox(
				widget.NewIcon(theme.VideoIcon()),
				widget.NewLabel("设备名称"),
				widget.NewLabel("通道号"),
				widget.NewLabel("状态"),
			)
		},
		func(id widget.ListItemID, item fyne.CanvasObject) {
			dev := state.Devices[id]
			c := item.(*fyne.Container)
			nameLbl := c.Objects[1].(*widget.Label)
			chanLbl := c.Objects[2].(*widget.Label)
			statusLbl := c.Objects[3].(*widget.Label)
			nameLbl.SetText(dev.DeviceName)
			chanLbl.SetText(dev.ChannelNo)
			statusLbl.SetText(map[string]string{"available": "在线", "offline": "离线"}[dev.Status])
		},
	)

	list.OnSelected = func(id widget.ListItemID) {
		if id < len(state.Devices) {
			showMonitorScreen(w, state.Devices[id])
		}
	}

	w.SetContent(container.NewBorder(container.NewHBox(backBtn, refreshBtn), nil, nil, nil, list))
}

func showMonitorScreen(w fyne.Window, dev unicomMonitor.DeviceInfo) {
	monitorWin = w
	w.SetTitle(dev.DeviceName)
	w.Resize(fyne.NewSize(1024, 720))

	backBtn := widget.NewButtonWithIcon("返回列表", theme.NavigateBackIcon(), func() {
		stopRecording(dev.DeviceName)
		monitorWin = nil
		showDeviceListScreen(w)
	})

	infoLabel := widget.NewLabel(fmt.Sprintf("设备: %s | ID: %s | 通道: %s", dev.DeviceName, dev.DeviceId, dev.ChannelNo))

	statusCard = widget.NewCard("录制状态", "", widget.NewLabel("未开始录制"))

	recordBtn = widget.NewButtonWithIcon("开始录制", theme.RecordIcon(), func() {
		if recordBtn.Text == "开始录制" {
			videoForServer := unicomMonitor.Video{
				Name:        dev.DeviceName,
				Size:        10,
				Count:       10,
				WsHost:      dev.Region,
				DeviceId:    dev.DeviceId,
				ChannelNo:   dev.ChannelNo,
				Token:       dev.Token,
				RelayServer: dev.RelayServer,
			}
			startRecording(unicomMonitor.DeviceInfo{
				DeviceId:   dev.DeviceId,
				DeviceName: dev.DeviceName,
				ChannelNo:  dev.ChannelNo,
				Status:     dev.Status,
				Region:     dev.Region,
				RelayHost:  dev.RelayHost,
				RelayPort:  dev.RelayPort,
			})
			recordBtn.SetText("停止录制")
			recordBtn.SetIcon(theme.StopIcon())
		} else {
			stopRecording(dev.DeviceName)
			recordBtn.SetText("开始录制")
			recordBtn.SetIcon(theme.RecordIcon())
		}
	})

	fileList = widget.NewList(
		func() int { return 0 },
		func() fyne.CanvasObject { return widget.NewLabel("") },
		func(id widget.ListItemID, item fyne.CanvasObject) {
			files, _ := listRecordFiles()
			if int(id) < len(files) {
				item.(*widget.Label).SetText(files[id])
			}
		},
	)

	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			if monitorWin == nil {
				return
			}
			files, _ := listRecordFiles()
			fyne.Do(func() {
				if fileList != nil {
					fileList.Length = func() int { return len(files) }
					fileList.Refresh()
				}
			})
		}
	}()

	w.SetContent(container.NewBorder(
		backBtn,
		container.NewVBox(infoLabel, container.NewHBox(recordBtn), statusCard),
		nil, nil,
		fileList,
	))
}

func doLogin(token, mobile string) ([]unicomMonitor.DeviceInfo, error) {
	privateToken, _, err := unicomMonitor.RefreshToken(token, mobile)
	if err != nil {
		return nil, err
	}

	ticket, err := unicomMonitor.GetTicketNative(privateToken)
	if err != nil {
		return nil, err
	}

	accessToken, err := unicomMonitor.GetAutoLoginToken(ticket)
	if err != nil {
		return nil, err
	}

	cloudToken, err := unicomMonitor.CloudLogin(mobile, accessToken)
	if err != nil {
		return nil, err
	}

	devices := unicomMonitor.GetDeviceList(cloudToken)
	if len(devices) == 0 {
		return nil, fmt.Errorf("未发现任何设备")
	}

	var wsHost string
	for _, dev := range devices {
		if dev.Status == "available" && dev.Region != "" {
			wsHost = dev.Region
			break
		}
	}
	if wsHost == "" {
		return nil, fmt.Errorf("无法获取 WebSocket 地址")
	}

	var videos []unicomMonitor.DeviceInfo
	for _, dev := range devices {
		if dev.Status != "available" {
			continue
		}
		if dev.Region == "" {
			dev.Region = wsHost
		}
		if dev.RelayHost == "" || dev.RelayPort == "" {
			continue
		}
		relayServer := dev.RelayHost + ":" + dev.RelayPort
		videos = append(videos, unicomMonitor.DeviceInfo{
			DeviceId:   dev.DeviceId,
			DeviceName: dev.DeviceName,
			ChannelNo:  dev.ChannelNo,
			Status:     dev.Status,
			Region:     dev.Region,
			RelayHost:  dev.RelayHost,
			RelayPort:  dev.RelayPort,
		})
	}

	return videos, nil
}
