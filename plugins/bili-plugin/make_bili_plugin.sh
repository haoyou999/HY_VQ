#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# make_bili_plugin.sh — B站插件打包脚本
#   BiliModule.java → javac → d8 → zip(module.json + classes.dex) → bili-plugin.zip
#
# 用法:
#   sh make_bili_plugin.sh
# 输出:
#   plugins/bili-plugin/out/bili-plugin-0.1.0-beta.zip
#
# 前置:
#   - JDK(javac)
#   - Android SDK build-tools(d8)
#   - 宿主壳已 Build(取编译产物做 classpath: HyVqModule 等契约类)
# ═══════════════════════════════════════════════════════════════

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ_DIR="$(cd "$PLUGIN_DIR/../.." && pwd)"
SRC="$PLUGIN_DIR/src"
OUT_DIR="$PLUGIN_DIR/out"
VERSION="0.1.0-beta"
OUT_FILE="$OUT_DIR/bili-plugin-$VERSION.zip"

ANDROID_JAR=${ANDROID_JAR:-/data/data/com.termux/files/home/android-sdk/platforms/android-33/android.jar}
SHELL_CLASSES=${SHELL_CLASSES:-"$PROJ_DIR/app/build/intermediates/javac/debug/classes"}
BUILD_TOOLS=${BUILD_TOOLS:-/data/data/com.termux/files/home/android-sdk/build-tools}

[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar: $ANDROID_JAR"; exit 1; }
D8_BIN=$(ls "$BUILD_TOOLS"/*/d8 2>/dev/null | head -1)
[ -n "$D8_BIN" ] || { echo "缺少 d8"; exit 1; }
[ -d "$SHELL_CLASSES" ] || { echo "宿主壳未编译,先 assembleDebug 一次"; exit 1; }

WORK="$PLUGIN_DIR/.work"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$OUT_DIR"

echo "==> [1/4] javac 编译"
CP="$ANDROID_JAR:$SHELL_CLASSES"
javac -source 8 -target 8 -Xlint:-options -cp "$CP" \
    -d "$WORK/classes" "$SRC/BiliModule.java" || exit 1

echo "==> [2/4] d8 → classes.dex"
( cd "$WORK/classes" && java -cp "$(dirname "$D8_BIN")/lib/d8.jar" \
    com.android.tools.r8.D8 --release --lib "$ANDROID_JAR" \
    --output "$WORK" $(find . -name '*.class') ) || exit 1
[ -f "$WORK/classes.dex" ] || { echo "d8 未产出 classes.dex"; exit 1; }

echo "==> [3/4] 打包 zip(module.json + classes.dex)"
python3 - "$OUT_FILE" "$WORK" "$PLUGIN_DIR/module.json" <<'PYEOF'
import sys, zipfile, os
out, work, mjson = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(mjson, 'module.json')
    z.write(os.path.join(work, 'classes.dex'), 'classes.dex')
PYEOF

echo "==> [4/4] 完成"
ls -la "$OUT_FILE" | awk '{print "    输出: "$NF" ("$5" bytes)"}'
rm -rf "$WORK"
echo "    安装: HY_VQ → 插件 → 安装 → 选此 zip"
