#!/bin/zsh
# 下载友盟统计 iOS SDK 到 Vendor/Umeng（SDK 不放进仓库）。没有它也能编译，只是不发统计。
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=Vendor/Umeng
COMMON=7.6.7
DEVICE=3.6.0
[[ -d $DEST/UMCommon.xcframework && -d $DEST/UMDevice.xcframework ]] && { echo "友盟 SDK 已存在：$DEST"; exit 0; }
TMP=$(mktemp -d)
trap 'rm -rf $TMP' EXIT
BASE=https://umplus-sdk-download.oss-cn-shanghai.aliyuncs.com/iOS
curl -fsSL -o $TMP/common.zip $BASE/UMCommon/UMCommon_$COMMON.zip
curl -fsSL -o $TMP/device.zip $BASE/UMDevice/UMDevice_$DEVICE.zip
unzip -q $TMP/common.zip -d $TMP/common
unzip -q $TMP/device.zip -d $TMP/device
mkdir -p $DEST
rm -rf $DEST/UMCommon.xcframework $DEST/UMDevice.xcframework
cp -R $TMP/common/UMCommon_$COMMON/UMCommon.xcframework $DEST/
cp -R $TMP/device/UMDevice_$DEVICE/UMDevice.xcframework $DEST/
echo "已下载友盟 SDK 到 $DEST（UMCommon $COMMON，UMDevice $DEVICE）"
