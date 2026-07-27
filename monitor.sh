#!/usr/bin/env bash
set -u

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ADB="${WZRY_ADB:-adb}"
DEVICE="${WZRY_DEVICE:-}"
LOG_FILE="${WZRY_LOG_FILE:-/tmp/wzry_run.log}"

if [ -z "$DEVICE" ]; then
    DEVICE="$("$ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi

echo "=========================================="
echo "王者荣耀农场监控"
echo "=========================================="
echo "设备: ${DEVICE:-未发现}"

if [ -n "$DEVICE" ] && "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; then
    echo "✅ ADB 已连接"
    GAME_PID="$("$ADB" -s "$DEVICE" shell pidof com.tencent.tmgp.sgame 2>/dev/null)"
    if [ -n "$GAME_PID" ]; then
        echo "🎮 游戏运行中: $GAME_PID"
    else
        echo "ℹ️ 游戏未运行"
    fi
else
    echo "❌ ADB 设备未连接"
fi

SCRIPT_PID="$(pgrep -f '[w]zry_auto.py' || true)"
if [ -n "$SCRIPT_PID" ]; then
    echo "✅ 自动化脚本运行中: $SCRIPT_PID"
else
    echo "ℹ️ 自动化脚本未运行"
fi

if [ -f "$LOG_FILE" ]; then
    echo
    echo "最近日志: $LOG_FILE"
    tail -30 "$LOG_FILE"
else
    echo "ℹ️ 日志尚不存在: $LOG_FILE"
fi

if [ -f "$PROJECT_DIR/assets/current.png" ]; then
    echo "📸 最近截图: $PROJECT_DIR/assets/current.png"
fi
