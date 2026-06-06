#!/bin/bash
# 实时务农监控
LOG_FILE="/tmp/wzry_run9.log"

echo "=========================================="
echo "王者荣耀农场实时监控"
echo "=========================================="
echo ""
echo "按 Ctrl+C 停止监控"
echo ""
echo "日志文件: $LOG_FILE"
echo ""
echo "开始监控..."
echo "------------------------------------------"

# 实时监控日志
tail -f "$LOG_FILE" 2>/dev/null | while IFS= read -r line; do
    # 高亮重要信息
    if echo "$line" | grep -q "✅"; then
        echo -e "\033[32m$line\033[0m"
    elif echo "$line" | grep -q "❌"; then
        echo -e "\033[31m$line\033[0m"
    elif echo "$line" | grep -q "步骤"; then
        echo -e "\033[33m$line\033[0m"
    elif echo "$line" | grep -q "错误\|异常"; then
        echo -e "\033[31m$line\033[0m"
    else
        echo "$line"
    fi
done
