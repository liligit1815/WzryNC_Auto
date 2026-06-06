#!/bin/bash
# 一键启动务农脚本和监控

echo "=========================================="
echo "王者荣耀农场自动化启动"
echo "=========================================="
echo ""
echo "📱 步骤1: 检查ADB连接..."
/tmp/platform-tools/adb -s 192.168.31.165:5557 get-state >/dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "  ❌ LDPlayer未连接，请先连接设备"
    echo "  命令: /tmp/platform-tools/adb connect 192.168.31.165:5557"
    exit 1
fi
echo "  ✅ LDPlayer已连接"
echo ""

echo "🎮 步骤2: 检查游戏进程..."
GAME_PID=$(/tmp/platform-tools/adb -s 192.168.31.165:5557 shell pidof com.tencent.tmgp.sgame 2>/dev/null)
if [ -n "$GAME_PID" ]; then
    echo "  ✅ 王者荣耀已运行 (PID: $GAME_PID)"
else
    echo "  ⚠️ 王者荣耀未运行，将自动启动"
fi
echo ""

echo "📝 步骤3: 检查脚本状态..."
SCRIPT_PID=$(pgrep -f "wzry_auto.py")
if [ -n "$SCRIPT_PID" ]; then
    echo "  ⚠️ 脚本已在运行 (PID: $SCRIPT_PID)"
    echo "  是否要停止并重新启动？(y/n)"
    read -r choice
    if [ "$choice" = "y" ] || [ "$choice" = "Y" ]; then
        echo "  停止脚本..."
        kill $SCRIPT_PID
        sleep 2
    else
        echo "  跳过启动"
        exit 0
    fi
fi
echo ""

echo "🚀 步骤4: 启动自动化脚本..."
cd /vol1/1000/Hermes/WZRY_Farm
nohup .venv/bin/python3 -u wzry_auto.py > /tmp/wzry_run9.log 2>&1 &
NEW_PID=$!
echo "  ✅ 脚本已启动 (PID: $NEW_PID)"
echo "  📄 日志文件: /tmp/wzry_run9.log"
echo ""

echo "📊 步骤5: 启动实时监控..."
echo "  正在启动监控..."
echo "  按 Ctrl+C 停止监控"
echo ""
bash /vol1/1000/Hermes/WZRY_Farm/realtime_monitor.sh
