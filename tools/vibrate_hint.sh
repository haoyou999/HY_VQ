#!/system/bin/sh
# HY_VQ 构建提醒：震动 1 秒 → 停 0.3 秒 → 震动 0.3 秒
# 用法: sh tools/vibrate_hint.sh
# 依赖: cmd vibrator_manager（Android 12+ / ColorOS 可用；synced oneshot 语法）

cmd vibrator_manager synced oneshot 1000 2>/dev/null || cmd vibrator_manager oneshot 1000 2>/dev/null
sleep 0.3
cmd vibrator_manager synced oneshot 300 2>/dev/null || cmd vibrator_manager oneshot 300 2>/dev/null
echo "vibrate_hint: done (1s + 0.3s gap + 0.3s)"