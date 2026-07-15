@echo off
chcp 65001 >nul
echo 编译 Fyne GUI 版本...
echo.

set rootPath=%~dp0
set outPath=%rootPath%build\unicomMonitor_gui_windows_amd64.exe

if not exist "%rootPath%build" mkdir "%rootPath%build"

cd /d "%rootPath%src"

echo 下载依赖...
go mod tidy

echo.
echo 编译 GUI (CGO_ENABLED=1, 需要 Go + MinGW-w64)...
set CGO_ENABLED=1
set GOOS=windows
set GOARCH=amd64
go build -ldflags="-s -w" -trimpath -o "%outPath%" ./cmd/gui/

if exist "%outPath%" (
    echo.
    echo 编译成功: %outPath%
    powershell -Command "(Get-Item '%outPath%').Length / 1MB"
) else (
    echo 编译失败
)

pause
