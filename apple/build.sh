#!/bin/bash
# 构建 macOS 版 Q-Translator.app。用法：./build.sh          只构建到 build/
#                                  ./build.sh install  构建并安装到 /Applications
# 需要 Xcode 和 XcodeGen（brew install xcodegen）。
set -euo pipefail
cd "$(dirname "$0")"

# iOS 版要用友盟统计 SDK（不在仓库里），没下载过就先下载
./Scripts/fetch_umeng.sh >/dev/null || echo "友盟 SDK 下载失败：Mac 版不受影响，iOS 版需要先运行 Scripts/fetch_umeng.sh"
xcodegen generate --quiet
# -quiet 模式下 xcodebuild 会多打两行无害的 “exit code 0” 提示，过滤掉
set +e
xcodebuild -project QTranslator.xcodeproj -scheme QTranslator-macOS -configuration Release \
    -derivedDataPath build/DerivedData -quiet build 2>&1 | grep -v "produced no further output"
status=${PIPESTATUS[0]}
set -e
if [[ $status -ne 0 ]]; then
    echo "构建失败"
    exit "$status"
fi
APP="build/DerivedData/Build/Products/Release/Q-Translator.app"

# 用固定的开发证书重新签名。系统的“辅助功能”“屏幕录制”授权认的是签名：
# 临时签名每编译一次就变一次，之前给的授权会失效，系统会一直要求重新授权。
# 默认用钥匙串里第一张 Apple Development 证书，也可以用 QTRANSLATOR_SIGN_IDENTITY 指定；
# 没有证书时保留临时签名（每次更新后需要重新授权）。
IDENTITY="${QTRANSLATOR_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk '/Apple Development/ {print $2; exit}')}"
if [[ -n "$IDENTITY" ]]; then
    codesign --force --sign "$IDENTITY" --timestamp=none "$APP"
    echo "已用开发证书签名"
else
    echo "没有找到 Apple Development 证书，使用临时签名：每次更新后需要重新授权辅助功能和屏幕录制"
fi
echo "已生成 $APP"

if [[ "${1:-}" == "install" ]]; then
    rm -rf "/Applications/Q-Translator.app"
    cp -R "$APP" /Applications/
    # 让系统重新扫描右键“服务”菜单
    /System/Library/CoreServices/pbs -update
    echo "已安装到 /Applications/Q-Translator.app"
fi
