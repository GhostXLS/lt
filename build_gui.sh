#!/bin/bash
# Build Fyne GUI version for native platform
echo "build GUI start"
rootPath=$(cd "$(dirname "$0")" && pwd)
outPath="$rootPath/build/unicomMonitor_gui_$(go env GOOS)_$(go env GOARCH)"

mkdir -p "$rootPath/build"
cd "$rootPath/src"

echo "tidy dependencies..."
go mod tidy

echo ""
echo "building GUI (CGO_ENABLED=1, requires system OpenGL/Mesa dev packages)..."
export CGO_ENABLED=1
go build -ldflags="-s -w" -trimpath -o "$outPath" ./cmd/gui/

if [ -f "$outPath" ]; then
    echo ""
    echo "build success: $outPath"
    ls -lh "$outPath"
else
    echo "build failed"
fi
