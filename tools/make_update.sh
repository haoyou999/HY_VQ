#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# HY_VQ 更新包制作脚本（标准 .zip 容器 + RSA 签名）
#   源码目录 → javac 编译 → d8 转 dex → MD5 → 签名 → zip 打包 → .zip
#   2026-08-28: 容器回退标准 zip(不再用 .hyv 魔数),签名验签机制保留
#   manifest.json 内含 RSA 签名 + classes.dex MD5 → 篡改/伪造包会被壳拒绝
#
# 用法：
#   make_update.sh <源码目录> <versionCode> <versionName> <入口类> [输出目录]
# 示例：
#   make_update.sh ./update_sample 13 1.3.0 com.hyv.update.sample.SampleEntry ./out
#
# 前置：
#   - JDK（javac）
#   - Android SDK build-tools（d8）——AndroidIDE 环境已内置
#   - 壳已 Build 过一次（取壳编译产物做 classpath：HyVqAppEntry 等契约类）
# 可选环境变量：
#   SHELL_RJAR=.../R.jar   壳资源 R 类（更新包代码引用 material/attr 等 R 时传入，
#                          取自 app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar）
#   SIGN_JKS=.../xxx.jks   签名密钥库（正式发布必填；壳强制验签，未签名包会被拒绝）
#   SIGN_PASS=密码         密钥库/私钥密码
#   SIGN_ALIAS=hyvq        密钥别名（默认 hyvq）
# ═══════════════════════════════════════════════════════════════════

SRC_DIR=${1:?用法: make_update.sh <源码目录> <versionCode> <versionName> <入口类> [输出目录]}
VCODE=${2:?缺少 versionCode}
VNAME=${3:?缺少 versionName}
ENTRY=${4:?缺少入口类全名}
# 默认输出到项目根目录/update_packages（方便查找）；可用第5个参数覆盖
OUT_DIR=${5:-"$(cd "$(dirname "$0")/.." && pwd)/update_packages"}

# ── 环境路径（AndroidIDE 默认；可用环境变量覆盖） ──
ANDROID_JAR=${ANDROID_JAR:-/data/user/0/com.itsaky.androidide/files/home/android-sdk/platforms/android-33/android.jar}
BUILD_TOOLS=${BUILD_TOOLS:-/data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools}
SHELL_CLASSES=${SHELL_CLASSES:-/storage/emulated/0/AndroidIDEProjects/HY_VQ/app/build/intermediates/javac/debug/classes}
SHELL_RJAR=${SHELL_RJAR:-}
SIGN_JKS=${SIGN_JKS:-}
SIGN_PASS=${SIGN_PASS:-}
SIGN_ALIAS=${SIGN_ALIAS:-hyvq}
TOOLS_DIR=$(cd "$(dirname "$0")" && pwd)

echo "==> [1/6] 检查环境"
[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar: $ANDROID_JAR"; exit 1; }
D8_BIN=$(ls "$BUILD_TOOLS"/*/d8 2>/dev/null | head -1)
[ -n "$D8_BIN" ] || { echo "缺少 d8 (build-tools)"; exit 1; }
[ -d "$SHELL_CLASSES" ] || { echo "缺少壳编译产物: $SHELL_CLASSES（请先 Build 一次 HY_VQ）"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "==> [2/6] javac 编译（classpath: android.jar + 壳编译产物）"
find "$SRC_DIR" -name '*.java' > "$WORK/sources.txt"
wc -l < "$WORK/sources.txt" | xargs echo "    源码文件数:"
CP="$ANDROID_JAR:$SHELL_CLASSES"
[ -n "$SHELL_RJAR" ] && CP="$CP:$SHELL_RJAR"
javac -source 8 -target 8 -Xlint:-options \
  -classpath "$CP" \
  -d "$WORK/classes" @"$WORK/sources.txt" 2>&1 | grep -v '^Note:' || true
[ -d "$WORK/classes" ] && [ -n "$(ls -A "$WORK/classes" 2>/dev/null)" ] || { echo "编译失败，无输出"; exit 1; }

echo "==> [3/6] d8 转 dex"
D8_CP=""
[ -n "$SHELL_CLASSES" ] && [ -d "$SHELL_CLASSES" ] && D8_CP="--classpath $SHELL_CLASSES"
[ -n "$SHELL_RJAR" ] && D8_CP="$D8_CP --classpath $SHELL_RJAR"
( cd "$WORK/classes" && java -cp "$(dirname "$D8_BIN")/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$ANDROID_JAR" $D8_CP --output "$WORK" $(find . -name '*.class') ) || { echo "d8 失败"; exit 1; }
[ -f "$WORK/classes.dex" ] || { echo "未生成 classes.dex"; exit 1; }
ls -la "$WORK/classes.dex" | awk '{print "    classes.dex: "$5" bytes"}'

echo "==> [4/6] 计算 MD5 + 生成 manifest.json"
MD5=$(md5sum "$WORK/classes.dex" | awk '{print $1}')
cat > "$WORK/manifest.json" <<EOF
{
  "format": "hyv-update",
  "versionCode": $VCODE,
  "versionName": "$VNAME",
  "minBaseVersion": 1,
  "entry": "$ENTRY",
  "dexMd5": "$MD5",
  "buildTime": "$(date '+%Y-%m-%d %H:%M:%S')"
}
EOF
echo "    dexMd5: $MD5"

echo "==> [5/7] RSA 签名（可选：提供 SIGN_JKS 时签名；壳强制验签）"
if [ -n "$SIGN_JKS" ] && [ -f "$SIGN_JKS" ]; then
  [ -n "$SIGN_PASS" ] || { echo "签名失败: 缺少 SIGN_PASS"; exit 1; }
  java -cp "$TOOLS_DIR" SignTool "$SIGN_JKS" "$SIGN_PASS" "$SIGN_ALIAS" "$WORK/manifest.json" "$WORK/classes.dex" || { echo "签名失败"; exit 1; }
else
  echo "    ⚠ 未签名（未提供 SIGN_JKS）——壳会拒绝安装，仅用于开发调试"
fi

echo "==> [6/7] 打包 .zip（标准 zip 容器,无魔数;签名已在 manifest 内）"
mkdir -p "$OUT_DIR"
OUT_FILE="$OUT_DIR/hyv_update_v${VCODE}.zip"
python3 - "$OUT_FILE" "$WORK" <<'PYEOF'
import sys, zipfile, os
out, work = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    # 标准 zip,不写魔数 comment(2026-08-28 改版)
    z.write(os.path.join(work, 'manifest.json'), 'manifest.json')
    z.write(os.path.join(work, 'classes.dex'), 'classes.dex')
PYEOF
ls -la "$OUT_FILE" | awk '{print "    输出: "$NF" ("$5" bytes)"}'

echo "==> [7/7] 完成"
echo "    更新包: $OUT_FILE"
echo "    版本: v$VCODE ($VNAME)  入口: $ENTRY"
echo "    安装方式: HY_VQ → 设置 → 软件更新 → 导入更新包"