#!/bin/zsh
# 创建快译的 Android 发布签名密钥。所有内容都由你自己输入：密码不会显示，也不会保存到仓库。
# 官网的 APK 和 Google Play 必须用同一把密钥，请先想好密码、并备份好生成的 .jks 文件。
#
# 用法：./scripts/create-release-key.sh
set -e
JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"}
KEYTOOL="$JAVA_HOME/bin/keytool"
[[ -x $KEYTOOL ]] || KEYTOOL=$(command -v keytool) || { echo "找不到 keytool，请先安装 JDK 17 或 Android Studio"; exit 1; }

DEFAULT_DIR=~/Keys/qtranslator
read "DIR?密钥文件保存到哪个文件夹？[$DEFAULT_DIR] "
DIR=${DIR:-$DEFAULT_DIR}
DIR=${DIR/#\~/$HOME}
mkdir -p "$DIR" && chmod 700 "$DIR"
KS="$DIR/qtranslator-release.jks"
[[ -e $KS ]] && { echo "$KS 已经存在，不会覆盖。请换个文件夹，或先自己处理掉旧文件。"; exit 1; }

read "ALIAS?密钥别名 [qtranslator] "; ALIAS=${ALIAS:-qtranslator}
read -s "PW1?设置密钥库密码（至少 6 位，输入时不显示）: "; echo
read -s "PW2?再输入一次: "; echo
[[ $PW1 == $PW2 && ${#PW1} -ge 6 ]] || { echo "两次密码不一致，或少于 6 位。"; exit 1; }

echo "接下来输入证书信息（姓名、组织等），直接回车可以跳过。"
"$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10950 -storepass "$PW1" -keypass "$PW1"
chmod 600 "$KS"

echo; echo "证书指纹（以后上架 Google Play 时会用到，请记下）："
"$KEYTOOL" -list -v -alias "$ALIAS" -keystore "$KS" -storepass "$PW1" | grep -E "SHA1|SHA256|Valid"

read "SAVE?要把路径和密码写进 android/local.properties 方便以后一键打包吗？（这个文件不会提交到 git）[y/N] "
if [[ $SAVE == [yY]* ]]; then
  L="$(dirname "$0")/../local.properties"
  {
    echo ""
    echo "# 发布签名（不提交到仓库）"
    echo "QT_KEYSTORE_FILE=$KS"
    echo "QT_KEYSTORE_PASSWORD=$PW1"
    echo "QT_KEY_ALIAS=$ALIAS"
    echo "QT_KEY_PASSWORD=$PW1"
  } >> "$L"
  echo "已写入 $L"
fi
echo; echo "完成。请现在就把 $KS 和密码备份到安全的地方：丢了就无法再更新已发布的应用。"
