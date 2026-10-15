#!/usr/bin/env bash
# Photo Delete AIUI Bridge - companion HTTP bridge for Rokid Glasses AIUI gallery access
# Copyright (C) 2026 OSAGEM
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, version 3 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only

# 免 Gradle 构建 dcim-bridge APK
# 依赖：Android SDK build-tools 35.0.1 + platforms/android-33 + JDK(Android Studio JBR)
#
# 用法：
#   ./build.sh                          # 调试签名（debug.keystore）→ photodelaiuibridge.apk
#                                       #   可用 `adb install -r` 覆盖安装，**不丢**「所有文件访问」授权
#   ./build.sh release                  # ★ 正式发布签名（内置 release.keystore）→ photodelaiuibridge-release.apk
#                                       #   ★★ 这是本项目**唯一**的发布签名，后续所有更新都用它
#                                       #   ⚠ 与调试签名不同 → 首次切换必须卸载后重装，授权需重新授予
#                                       #   release.keystore + keystore.properties 必须成对备份（丢了就无法再发更新）
#   ./build.sh release <属性文件>       # 备选（非当前方案）：用外部/自备 keystore 签名
#                                       #   属性文件模板见 keystore.external.properties.template
#                                       #   说明见 RELEASE.md
# 详见 RELEASE.md
set -euo pipefail

MODE="${1:-debug}"
PROPS_ARG="${2:-}"
case "$MODE" in
  debug|release) ;;
  *) echo "用法: $0 [debug|release] [属性文件]（默认 debug）" >&2; exit 2 ;;
esac

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# ---- 工具链定位（全部可用环境变量覆盖，不写死本机路径）----
# Python 只用于两处小任务：注入 classes.dex、生成随机口令
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi
[ -n "$PY" ] || { echo "找不到 python（可用 PYTHON=/path/to/python 指定）" >&2; exit 1; }

# Android SDK：优先 ANDROID_SDK_ROOT / ANDROID_HOME，其次常见安装位置
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for c in "$LOCALAPPDATA/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" \
           "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$c" ] && { SDK="$c"; break; }
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "找不到 Android SDK（请设 ANDROID_SDK_ROOT）" >&2; exit 1; }

# build-tools：优先 35.0.1（33 自带的 d8 无法解析新版 JDK 产出的 class 文件），缺失则回落最新版
BT="${BT_DIR:-}"
if [ -z "$BT" ]; then
  if [ -d "$SDK/build-tools/35.0.1" ]; then
    BT="$SDK/build-tools/35.0.1"
  else
    BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    BT="${BT%/}"
  fi
fi
AJ="$SDK/platforms/android-33/android.jar"

# JDK：优先已有 JAVA_HOME，其次 Android Studio 自带的 JBR
if [ -z "${JAVA_HOME:-}" ]; then
  for c in "$LOCALAPPDATA/Programs/Android Studio/jbr" \
           "/c/Program Files/Android/Android Studio/jbr" \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -d "$c" ] && { JAVA_HOME="$c"; break; }
  done
fi
[ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME" ] \
  || { echo "找不到 JDK（请设 JAVA_HOME，或安装 Android Studio 以使用其自带 JBR）" >&2; exit 1; }
# 注：本脚本默认在 Windows(Git Bash) 下运行，故可执行文件带 .exe 后缀；
#     Linux/macOS 下请把下方的 .exe 去掉。
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

# ★ 凭据目录：签名密钥与口令一律存放在**本工程目录之外**，
#   因此源码目录本身不含任何敏感文件，可以整体公开/分享。
#   默认 ~/.photodelaiuibridge（Windows 上即 C:\Users\<你>\.photodelaiuibridge），
#   可用环境变量 BRIDGE_KEYSTORE_DIR 覆盖。
KEYSTORE_DIR="${BRIDGE_KEYSTORE_DIR:-$HOME/.photodelaiuibridge}"
mkdir -p "$KEYSTORE_DIR" 2>/dev/null || true

for f in "$BT/aapt2.exe" "$BT/zipalign.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$AJ"; do
  [ -e "$f" ] || { echo "缺少: $f"; exit 1; }
done

echo "== 0. 清理 =="
rm -rf build
mkdir -p build/classes build/dex build/gen

echo "== 1. 资源（applogo 固化在本工程 res/drawable/，与相册工程同款矢量图） =="
ls -R res | head -30

echo "== 2. aapt2 compile =="
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "== 3. aapt2 link =="
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 32 \
  --target-sdk-version 32 \
  -R build/res.zip \
  --java build/gen \
  --auto-add-overlay

echo "== 4. javac =="
find src -name '*.java' > build/sources.txt
# 一并编译 aapt2 生成的 R.java（本地化资源引用需要），
# 直接作为源文件编译，避免 classpath 分隔符在不同 shell 下的差异
find build/gen -name '*.java' >> build/sources.txt
"$JAVAC" --release 11 -encoding UTF-8 -nowarn \
  -classpath "$AJ" \
  -d build/classes \
  @build/sources.txt

echo "== 5. d8 =="
( cd build/classes && find . -name '*.class' > ../classes.txt )
( cd build/classes && "$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 28 --lib "$AJ" --output ../dex @../classes.txt )

echo "== 6. 合成 APK =="
cp -f build/base.apk build/unsigned.apk
"$PY" - <<'PY'
import zipfile, shutil, os
src = 'build/unsigned.apk'
dst = 'build/withdex.apk'
shutil.copyfile(src, dst)
z = zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED)
z.write('build/dex/classes.dex', 'classes.dex')
z.close()
print('injected classes.dex ->', os.path.getsize(dst), 'bytes')
PY

echo "== 7. zipalign =="
"$BT/zipalign.exe" -f -p 4 build/withdex.apk build/aligned.apk

echo "== 8. 签名（模式：$MODE） =="
if [ "$MODE" = "release" ]; then
  # ---- 正式发布签名 ----
  OUT="photodelaiuibridge-release.apk"
  KS_TYPE=""

  if [ -n "$PROPS_ARG" ]; then
    # ============ 外部 / 自备 keystore ============
    # 按属性文件里的 storeFile / storeType / keyAlias / 口令 签名。
    # 用途：复用你已有的发布密钥（如 AndroidStudioReleaseAPKKEY）
    PROPS="$PROPS_ARG"
    [ -f "$PROPS" ] || { echo "找不到属性文件: $PROPS" >&2; exit 1; }
    # shellcheck disable=SC1090
    . "$PROPS"
    KS="$storeFile"
    KS_TYPE="${storeType:-PKCS12}"
    STORE_PASS="$storePassword"; KEY_PASS="$keyPassword"; KEY_ALIAS="$keyAlias"
    [ -f "$KS" ] || { echo "找不到 keystore 文件: $KS（请检查属性文件的 storeFile）" >&2; exit 1; }
    echo "使用外部 keystore: $KS"
    echo "  类型=$KS_TYPE · 别名=$KEY_ALIAS"
  else
    # ============ 内置 release.keystore（★ 本项目唯一发布签名） ============
    # ★ 密钥与口令存放在**工程目录之外**（默认 ~/.photodelaiuibridge，可用 BRIDGE_KEYSTORE_DIR 覆盖），
    #   因此源码目录本身不含任何敏感文件，可整体公开/分享。
    KS="$KEYSTORE_DIR/release.keystore"
    PROPS="$KEYSTORE_DIR/keystore.properties"
    echo "凭据目录: $KEYSTORE_DIR"

    # ★ 防呆：keystore 在、凭据文件丢失 → 绝不静默生成新口令（会导致签名失败或换新密钥）
    if [ -f "$KS" ] && [ ! -f "$PROPS" ]; then
      echo "" >&2
      echo "❌ 中止：$KS 已存在，但凭据文件 $PROPS 缺失！" >&2
      echo "   口令的唯一来源就是这个文件 —— 它丢了就无法再用这把密钥签名（= 无法再发更新）。" >&2
      echo "   请从你自己的备份位置成对恢复 $KS + $PROPS（这两个文件必须一起保管）。" >&2
      echo "   若确认要**放弃旧密钥**从零开始（发布过的旧包将无法被覆盖升级），" >&2
      echo "   请先手动删除 $KS，再重新运行本脚本。" >&2
      exit 1
    fi

    if [ ! -f "$PROPS" ]; then
      PASS="$("$PY" -c 'import secrets,string;al=string.ascii_letters+string.digits;print("".join(secrets.choice(al) for _ in range(28)))')"
      cat > "$PROPS" <<EOF
# 发布签名凭据 —— 请与 release.keystore 一起备份到安全位置！
# 丢失 = 无法再发布任何更新（同包名必须同签名）
storeFile=release.keystore
storePassword=$PASS
keyAlias=photodelaiuibridge
keyPassword=$PASS
EOF
      chmod 600 "$PROPS" 2>/dev/null || true
      echo "已生成 $PROPS（随机 28 位口令）"
    fi
    # shellcheck disable=SC1090
    . "$PROPS"
    STORE_PASS="$storePassword"; KEY_PASS="$keyPassword"; KEY_ALIAS="$keyAlias"

    # storeFile 支持两种写法：绝对路径，或相对于凭据目录的文件名
    if [ -n "${storeFile:-}" ]; then
      case "$storeFile" in
        /*|[A-Za-z]:*) KS="$storeFile" ;;
        *)            KS="$KEYSTORE_DIR/$storeFile" ;;
      esac
    fi

    if [ ! -f "$KS" ]; then
      "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
        -dname "CN=Photo Delete AIUI Bridge, O=osagem, C=CN" >/dev/null 2>&1
      echo ""
      echo "⚠️⚠️  已**新建** $KS（RSA 4096 / 有效期 30 年）——这是全新密钥！"
      echo "     若本项目此前已用别的密钥发布过，新包签名会变 → 覆盖安装必失败、需卸载重装。"
      echo "     请立刻与 $PROPS 一起备份，并记下下方 Signer #1 SHA-256 作为指纹基线。"
      echo ""
    fi
  fi

  "$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" ${KS_TYPE:+--ks-type "$KS_TYPE"} \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" build/aligned.apk
else
  # ---- 调试签名（保持既有行为，覆盖安装不丢授权） ----
  OUT="photodelaiuibridge.apk"
  KS="$KEYSTORE_DIR/debug.keystore"
  KEY_ALIAS="androiddebugkey"; STORE_PASS="android"; KEY_PASS="android"

  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi

  "$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$OUT" build/aligned.apk
fi

echo "== 9. 校验 =="
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --verbose --print-certs "$OUT" 2>/dev/null \
  | grep -iE "Verified using|Signer #1 certificate (DN|SHA-256)" | head -8
echo "--- debuggable（应为空 / false） ---"
"$BT/aapt2.exe" dump xmltree "$OUT" --file AndroidManifest.xml 2>/dev/null | grep -i debuggable || echo "  未设置（= false）✅"
echo "--- zipalign ---"
"$BT/zipalign.exe" -c 4 "$OUT" >/dev/null 2>&1 && echo "  4 字节对齐 OK ✅"
echo "--- SHA-256 ---"
sha256sum "$OUT"

echo "== 完成（$MODE） =="
ls -la "$OUT"
"$BT/aapt2.exe" dump badging "$OUT" 2>/dev/null | head -4
