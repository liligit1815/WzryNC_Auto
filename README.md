# 王者荣耀农场自动化工具 V3

自动化完成王者荣耀农场务农：启动游戏 → 进入农场 → 一键务农 → 等待作物成熟 → 自动唤醒继续。

## ✨ V3 新功能

- **多分辨率支持**：自动识别设备分辨率，使用对应模板
- **智能唤醒**：执行时间前2分钟自动唤醒屏幕并解锁
- **亮度控制**：支持ROOT权限设置亮度为0，减少烧屏风险
- **作物周期持久化**：自动记录作物周期，避免计算错误

## 📁 文件结构

```
WZRY_Farm/
├── wzry_auto.py           # 主自动化脚本 (V3)
├── start.sh               # 一键启动脚本 (Linux)
├── monitor.sh             # 监控脚本
├── realtime_monitor.sh    # 实时监控脚本
├── README.md              # 说明文档
├── assets/
│   ├── templates/         # 默认模板 (1280x720)
│   │   ├── start_game.png
│   │   ├── close_popup.png
│   │   ├── lainongchang.png
│   │   ├── oneclick_farm.png
│   │   ├── refresh_pos.png
│   │   └── harvest_continue.png
│   ├── templates/2400x1080/  # 1080x2400设备专用模板
│   └── crop_cycle.json    # 作物周期记录
├── logs/                  # 日志目录
└── tmp/                   # 临时文件
```

---

## 🖥️ Windows 运行指南

### 1. 安装 Python 3.11+

从 [python.org](https://www.python.org/downloads/) 下载安装，安装时勾选 **"Add Python to PATH"**。

验证：
```cmd
python --version
```

### 2. 安装 ADB

1. 下载 [platform-tools](https://developer.android.com/tools/releases/platform-tools)
2. 解压到任意目录（如 `C:\platform-tools`）
3. 把该目录加到系统 PATH：
   - 右键「此电脑」→ 属性 → 高级系统设置 → 环境变量
   - 在「系统变量」中找到 `Path`，编辑，添加 `C:\platform-tools`

验证：
```cmd
adb version
```

### 3. 连接设备

确保设备开启了**无线调试**，获取 IP 和端口后连接：
```cmd
adb connect 192.168.31.197:38983
adb devices
```

### 4. 克隆仓库并安装依赖

```cmd
git clone https://github.com/liligit1815/WzryNC_Auto.git
cd WzryNC_Auto

# 创建虚拟环境
python -m venv venv
venv\Scripts\activate

# 安装依赖
pip install opencv-python numpy rapidocr_onnxruntime -i https://mirrors.aliyun.com/pypi/simple/
```

如果 `onnxruntime` 安装失败或运行时报 DLL 错误：
```cmd
pip install onnxruntime==1.17.0
```
同时安装 [Visual C++ Redistributable](https://aka.ms/vs/17/release/vc_redist.x64.exe)。

### 5. 运行脚本

```cmd
venv\Scripts\activate
python -u wzry_auto.py
```

### 6. 环境变量配置

```cmd
# 设备地址
set WZRY_DEVICE=192.168.31.197:38983

# 锁屏密码（可选）
set WZRY_UNLOCK_PWD=1234

python -u wzry_auto.py
```

---

## 🐧 Linux 运行指南

### 1. 连接设备
```bash
adb connect 192.168.31.197:38983
```

### 2. 一键启动
```bash
cd WZRY_Farm
bash start.sh
```

### 3. 或手动启动
```bash
cd WZRY_Farm
.venv/bin/python3 -u wzry_auto.py
```

### 4. 后台运行
```bash
.venv/bin/python3 -u wzry_auto.py > /tmp/wzry_run.log 2>&1 &
```

---

## 💡 启动选项

脚本启动时会提示：

```
============================================================
💡 是否降低屏幕亮度以减少烧屏风险？
============================================================
  Y - 关闭自动亮度，亮度降至最低(1)
  R - 使用ROOT权限，亮度设为0（突破厂商限制）
  N - 保持当前亮度设置
============================================================
请选择 (Y/R/N):
```

- **Y**：普通模式，亮度降至1
- **R**：ROOT模式，亮度设为0（需要设备已ROOT）
- **N**：不修改亮度

脚本退出时会自动恢复原始亮度设置。

---

## 📊 监控命令

### 查看日志
```bash
# 查看最近日志
tail -100 /tmp/wzry_run.log

# 实时监控
tail -f /tmp/wzry_run.log

# 查看错误
grep "❌" /tmp/wzry_run.log
```

### 查看脚本状态
```bash
# 检查脚本是否运行
pgrep -f wzry_auto.py

# 停止脚本
pkill -f wzry_auto.py
```

---

## 📝 工作流程

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 检测状态 | 检测游戏是否在前台，是则退出重启 |
| 2 | 启动游戏 | 启动王者荣耀，等待40秒 |
| 3 | 点击开始游戏 | 匹配并点击登录界面的开始按钮 |
| 4 | 关闭弹窗 | 关闭活动/公告弹窗 |
| 5 | 进入农场 | 点击"来农场干农活"按钮 |
| 6 | 移动到雕像 | 刷新站位 + 摇杆移动到雕像位置 |
| 7 | 一键务农 | 点击一键务农按钮 |
| 8 | 关闭收获弹窗 | 处理收获后的弹窗，记录作物周期 |
| 9 | 移动到土地 | 摇杆移动到农田，OCR读取成熟时间 |
| 10 | 计算等待 | 计算下次执行时间，退出游戏等待唤醒 |

---

## ⚠️ 注意事项

1. **多分辨率支持**：脚本自动识别设备分辨率，优先使用专用模板
2. **ADB 连接不稳定**：脚本启动时会自动重连，3次重试机制
3. **截图自动旋转**：竖屏截图会自动旋转为横屏
4. **脚本运行必须加 `-u` 参数**：否则后台模式无输出
5. **模板匹配阈值**：`start_game` ≥ 0.7，`close_popup` ≥ 0.9，其他 ≥ 0.5

---

## 🔧 常见问题

### Q: 脚本运行无输出
A: 确保使用 `-u` 参数：
```cmd
python -u wzry_auto.py
```

### Q: 步骤3匹配失败（score < 0.7）
A: 检查设备画面是否显示了"开始游戏"按钮。可能原因：
- 游戏还在加载中（增加等待时间）
- 模板与设备分辨率不匹配
- 游戏卡在更新/公告页面

### Q: 作物周期计算错误
A: 脚本会在首次种植时自动记录周期。如果计算错误，删除 `assets/crop_cycle.json` 后重新运行。

### Q: onnxruntime DLL 错误
A: 安装 Visual C++ Redistributable 并降级 onnxruntime：
```cmd
pip install onnxruntime==1.17.0
```

### Q: ROOT模式亮度未生效
A: 检查设备是否已ROOT，并确认 `/sys/class/backlight/` 路径存在。

---

## 📞 联系

如有问题，请查看日志文件或运行监控脚本查看状态。
