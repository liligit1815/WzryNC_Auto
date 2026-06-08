# 王者荣耀农场自动化工具

自动化完成王者荣耀农场务农：启动游戏 → 进入农场 → 一键务农 → 等待作物成熟。

## 📁 文件结构

```
WZRY_Farm/
├── wzry_auto.py           # 主自动化脚本 (V2.1)
├── start.sh               # 一键启动脚本 (Linux)
├── monitor.sh             # 监控脚本
├── realtime_monitor.sh    # 实时监控脚本
├── README.md              # 说明文档
├── assets/
│   └── templates/         # 图片模板 (1280x720)
│       ├── start_game.png
│       ├── close_popup.png
│       ├── lainongchang.png
│       ├── oneclick_farm.png
│       ├── refresh_pos.png
│       └── harvest_continue.png
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

### 3. 连接模拟器

确保模拟器开启了**无线调试**，获取 IP 和端口后连接：
```cmd
adb connect 192.168.31.165:5557
adb devices
```
看到 `192.168.31.165:5557   device` 表示连接成功。

### 4. 克隆仓库并安装依赖

```cmd
git clone https://github.com/your-username/WZRY_Farm.git
cd WZRY_Farm

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

### 6. 修改设备地址

脚本默认连接 `192.168.31.165:5557`。如需修改：

**方法一：直接改脚本**
打开 `wzry_auto.py`，找到：
```python
DEVICE = os.environ.get("WZRY_DEVICE", "192.168.31.165:5557")
```
改为你的模拟器地址。

**方法二：用环境变量**
```cmd
set WZRY_DEVICE=192.168.31.100:5555
python -u wzry_auto.py
```

---

## 🐧 Linux 运行指南

### 1. 连接设备
```bash
adb connect 192.168.31.165:5557
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

### 查看游戏状态
```bash
adb -s 192.168.31.165:5557 shell pidof com.tencent.tmgp.sgame
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
| 8 | 关闭收获弹窗 | 处理收获后的弹窗 |
| 9 | 移动到土地 | 摇杆移动到农田，OCR读取成熟时间 |
| 10 | 等待成熟 | 计算浇水时间，智能休眠 |

---

## ⚠️ 注意事项

1. **模板分辨率**：模板在 1280x720 设备上截取，不同分辨率设备可能需要重新截取
2. **ADB 连接不稳定**：脚本启动时会自动重连，3次重试机制
3. **截图自动旋转**：竖屏截图会自动旋转为横屏
4. **脚本运行必须加 `-u` 参数**：否则后台模式无输出
5. **模板匹配阈值**：`oneclick_farm` ≥ 0.65，其他 ≥ 0.5

---

## 🔧 常见问题

### Q: 脚本运行无输出
A: 确保使用 `-u` 参数：
```cmd
python -u wzry_auto.py
```

### Q: 步骤1检测不到游戏在前台
A: 脚本使用三种方式检测（ResumedActivity / mCurrentFocus / top activity），请确认模拟器上游戏确实在前台。

### Q: 步骤3匹配失败（score < 0.7）
A: 检查模拟器画面是否显示了"开始游戏"按钮。可能原因：
- 游戏还在加载中（增加等待时间）
- 模拟器分辨率与模板不匹配
- 游戏卡在更新/公告页面

### Q: onnxruntime DLL 错误
A: 安装 Visual C++ Redistributable 并降级 onnxruntime：
```cmd
pip install onnxruntime==1.17.0
```

### Q: 健康提醒弹窗
A: 游戏防沉迷系统限制，需手动点击"确定"。

---

## 📞 联系

如有问题，请查看日志文件或运行监控脚本查看状态。
