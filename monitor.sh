#!/bin/bash
# 务农监控脚本
LOG_FILE="/tmp/wzry_run9.log"
STATE_FILE="/tmp/wzry_state.txt"
ADB="/tmp/platform-tools/adb"

echo "=========================================="
echo "王者荣耀农场监控"
echo "=========================================="
echo ""

# 1. 检查ADB连接
echo "📱 ADB连接状态:"
if $ADB -s 192.168.31.165:5557 get-state >/dev/null 2>&1; then
    echo "  ✅ LDPlayer已连接"
else
    echo "  ❌ LDPlayer未连接"
fi
echo ""

# 2. 检查游戏进程
echo "🎮 游戏进程:"
GAME_PID=$($ADB -s 192.168.31.165:5557 shell pidof com.tencent.tmgp.sgame 2>/dev/null)
if [ -n "$GAME_PID" ]; then
    echo "  ✅ 王者荣耀运行中 (PID: $GAME_PID)"
else
    echo "  ⚠️ 王者荣耀未运行"
fi
echo ""

# 3. 检查脚本状态
echo "📝 自动化脚本:"
SCRIPT_PID=$(pgrep -f "wzry_auto.py")
if [ -n "$SCRIPT_PID" ]; then
    echo "  ✅ 脚本运行中 (PID: $SCRIPT_PID)"
else
    echo "  ⚠️ 脚本未运行"
fi
echo ""

# 4. 检查务农进程
echo "👨‍🌾 务农进程:"
FARM_PID=$($ADB -s 192.168.31.165:5557 shell pidof com.tencent.tmgp.sgame 2>/dev/null | head -1)
if [ -n "$FARM_PID" ]; then
    echo "  ✅ 游戏进程运行中 (PID: $FARM_PID)"
else
    echo "  ⚠️ 游戏进程未运行"
fi
echo ""

# 5. 读取日志文件状态
echo "📊 日志文件状态:"
if [ -f "$LOG_FILE" ]; then
    LOG_SIZE=$(du -h "$LOG_FILE" | cut -f1)
    LOG_MOD=$(stat -c %y "$LOG_FILE" | cut -d'.' -f1)
    echo "  📄 日志文件: $LOG_FILE"
    echo "  📦 文件大小: $LOG_SIZE"
    echo "  🕐 最后修改: $LOG_MOD"
    
    # 读取最后20行日志
    echo ""
    echo "📋 最近日志:"
    tail -20 "$LOG_FILE"
else
    echo "  ⚠️ 日志文件不存在"
fi
echo ""

# 6. 读取状态文件
echo "🎯 任务状态:"
if [ -f "$STATE_FILE" ]; then
    echo "  📄 状态文件: $STATE_FILE"
    cat "$STATE_FILE"
else
    echo "  ⚠️ 状态文件不存在"
fi
echo ""

# 7. 检查截图
echo "📸 截图状态:"
SCREENSHOT="/vol1/1000/Hermes/WZRY_Farm/tmp/screenshot.png"
if [ -f "$SCREENSHOT" ]; then
    SCREEN_SIZE=$(du -h "$SCREENSHOT" | cut -f1)
    SCREEN_MOD=$(stat -c %y "$SCREENSHOT" | cut -d'.' -f1)
    echo "  📄 截图文件: $SCREENSHOT"
    echo "  📦 文件大小: $SCREEN_SIZE"
    echo "  🕐 最后修改: $SCREEN_MOD"
else
    echo "  ⚠️ 截图文件不存在"
fi
echo ""

echo "=========================================="
echo "监控完成！"
echo "=========================================="
echo ""
echo "实时监控命令:"
echo "  tail -f $LOG_FILE"
echo ""
echo "启动新监控:"
echo "  bash /vol1/1000/Hermes/WZRY_Farm/monitor.sh"
