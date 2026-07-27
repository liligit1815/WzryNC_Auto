#!/usr/bin/env bash
set -u

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="${WZRY_LOG_FILE:-/tmp/wzry_run.log}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
ADB_BIN="${WZRY_ADB:-}"

echo "=========================================="
echo "王者荣耀农场自动化启动"
echo "=========================================="

if [ -z "$ADB_BIN" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB_BIN="$(command -v adb)"
    elif [ -x "$HOME/android-tools/platform-tools/adb" ]; then
        ADB_BIN="$HOME/android-tools/platform-tools/adb"
    elif [ -x /tmp/platform-tools/adb ]; then
        ADB_BIN=/tmp/platform-tools/adb
    else
        echo "❌ 未找到 adb，请安装 Android platform-tools 或设置 WZRY_ADB"
        exit 1
    fi
fi

if [ ! -x "$ADB_BIN" ]; then
    echo "❌ WZRY_ADB 不可执行: $ADB_BIN"
    exit 1
fi
export WZRY_ADB="$ADB_BIN"

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "❌ 未找到 Python: $PYTHON_BIN"
    exit 1
fi

if [ -n "${WZRY_VENV_DIR:-}" ]; then
    VENV_DIR="$WZRY_VENV_DIR"
elif [ -x "$PROJECT_DIR/.venv/bin/python" ]; then
    VENV_DIR="$PROJECT_DIR/.venv"
elif [ -x "$PROJECT_DIR/venv/bin/python" ]; then
    VENV_DIR="$PROJECT_DIR/venv"
else
    VENV_DIR="$PROJECT_DIR/.venv"
fi

if [ ! -x "$VENV_DIR/bin/python" ]; then
    echo "📦 创建虚拟环境: $VENV_DIR"
    "$PYTHON_BIN" -m venv "$VENV_DIR" || exit 1
fi

if ! "$VENV_DIR/bin/python" "$PROJECT_DIR/scripts/check_requirements.py" \
    "$PROJECT_DIR/requirements.txt" >/dev/null 2>&1; then
    echo "📦 安装项目依赖..."
    "$VENV_DIR/bin/python" -m pip install -r "$PROJECT_DIR/requirements.txt" || exit 1
fi

if pgrep -f -- "$PROJECT_DIR/wzry_auto.py" >/dev/null 2>&1; then
    echo "⚠️ 本项目的 wzry_auto.py 已在运行，本次不重复启动"
    exit 0
fi

mkdir -p "$(dirname -- "$LOG_FILE")" || {
    echo "❌ 无法创建日志目录: $(dirname -- "$LOG_FILE")"
    exit 1
}

echo "🚀 启动脚本"
echo "🐍 虚拟环境: $VENV_DIR"
echo "🔧 ADB: $ADB_BIN"
echo "📱 设备: ${WZRY_DEVICE:-自动选择}"
echo "📄 日志: $LOG_FILE"

cd "$PROJECT_DIR" || exit 1
"$VENV_DIR/bin/python" -u "$PROJECT_DIR/scripts/run_with_log.py" \
    "$PROJECT_DIR/wzry_auto.py" "$LOG_FILE"
exit $?
