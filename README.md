# 王者荣耀农场自动化工具

## 📁 文件结构

```
WZRY_Farm/
├── wzry_auto.py           # 主自动化脚本
├── start.sh               # 一键启动脚本
├── monitor.sh             # 监控脚本
├── realtime_monitor.sh    # 实时监控脚本
├── README.md              # 说明文档
├── assets/
│   └── templates/         # 图片模板
├── logs/                  # 日志目录
└── tmp/                   # 临时文件
```

## 🚀 快速开始

### 1. 连接设备
```bash
/tmp/platform-tools/adb connect 192.168.31.165:5557
```

### 2. 一键启动
```bash
cd /vol1/1000/Hermes/WZRY_Farm
bash start.sh
```

### 3. 或手动启动

#### 启动脚本
```bash
cd /vol1/1000/Hermes/WZRY_Farm
.venv/bin/python3 -u wzry_auto.py > /tmp/wzry_run9.log 2>&1 &
```

#### 启动监控
```bash
bash monitor.sh
```

#### 实时监控
```bash
bash realtime_monitor.sh
```

## 📊 监控命令

### 查看日志
```bash
# 查看最近日志
tail -100 /tmp/wzry_run9.log

# 实时监控
tail -f /tmp/wzry_run9.log

# 查看错误
grep "❌" /tmp/wzry_run9.log
```

### 查看脚本状态
```bash
# 检查脚本是否运行
pgrep -f wzry_auto.py

# 停止脚本
pkill -f wzry_auto.py
```

### 查看游戏状态
```bash
# 检查游戏进程
/tmp/platform-tools/adb -s 192.168.31.165:5557 shell pidof com.tencent.tmgp.sgame

# 截图验证
/tmp/platform-tools/adb -s 192.168.31.165:5557 shell screencap -p /sdcard/test.png
/tmp/platform-tools/adb -s 192.168.31.165:5557 pull /sdcard/test.png /tmp/
```

## 🔧 常见问题

### Q: 脚本运行无输出
A: 确保使用 `-u` 参数：
```bash
.venv/bin/python3 -u wzry_auto.py
```

### Q: 游戏未启动
A: 检查ADB连接和游戏进程：
```bash
/tmp/platform-tools/adb -s 192.168.31.165:5557 get-state
/tmp/platform-tools/adb -s 192.168.31.165:5557 shell pidof com.tencent.tmgp.sgame
```

### Q: 健康提醒弹窗
A: 游戏防沉迷系统限制，需要手动点击"确定"按钮。脚本检测到弹窗后会暂停。

### Q: 步骤失败
A: 查看日志了解失败原因：
```bash
grep "❌" /tmp/wzry_run9.log | tail -20
```

## 📝 工作流程

1. **启动游戏** - 步骤1
2. **等待游戏加载** - 步骤2 (70秒)
3. **点击开始游戏** - 步骤3
4. **关闭弹窗** - 步骤4
5. **进入农场** - 步骤5
6. **刷新位置** - 步骤6
7. **一键务农** - 步骤7
8. **继续收获** - 步骤8
9. **移动到土地** - 步骤9
10. **等待作物成熟** - 步骤10

## ⚠️ 注意事项

1. 游戏可能因健康提醒弹窗而暂停
2. 游戏可能在非游戏界面（如商城），需要按返回键
3. 截图自动旋转：设备竖屏 → 横屏
4. 模板匹配阈值：`oneclick_farm` ≥ 0.65，其他 ≥ 0.5

## 📞 联系

如有问题，请查看日志文件 `/tmp/wzry_run9.log` 或运行 `bash monitor.sh` 查看状态。
