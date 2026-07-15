@echo off
chcp 65001 >nul
echo build start
set rootPath=%~dp0
set outPath=%rootPath%/build/unicomMonitor_
if not exist "%rootPath%/build" mkdir "%rootPath%/build"
set windows_archs=386 amd64 arm64
set linux_archs=386 amd64 arm64 mips mipsle mips64 mips64le
set darwin_archs=amd64 arm64
set freebsd_archs=386 amd64 arm64
setlocal EnableDelayedExpansion
for %%o in (windows linux darwin freebsd) do (
    for %%b in (!%%o_archs!) do (
            echo building for %%o/%%b
            set CGO_ENABLED=0
            set GOOS=%%o
            set GOARCH=%%b
            set exe_suffix=
            if "%%o"=="windows" set exe_suffix=.exe
            cd /d "%rootPath%/src"
            set outputFile=%outPath%%%o_%%b!exe_suffix!
            go build -ldflags="-w -s" -trimpath -o "!outputFile!" ./cmd/console/
            if exist "!outputFile!" (
                powershell -Command "Compress-Archive -LiteralPath '!outputFile!' -DestinationPath '!outputFile!.zip' -Force; Remove-Item -LiteralPath '!outputFile!' -Force"
            )
        )
    )
)
cd /d "%rootPath%"
pause
