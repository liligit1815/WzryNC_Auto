# 王者荣耀农场自动化务农

自动完成王者荣耀农场的务农操作，基于 **ADB + 模板匹配 + 摇杆操作**。

## 功能

1. 自动启动游戏并进入农场
2. 自动移动角色到雕像位置
3. 一键务农（收获作物）
4. OCR 读取作物成熟时间
5. 等待成熟前1分钟自动重新执行

## 环境要求

- Python 3.11+
- ADB 工具
- 雷电模拟器（1280x720 分辨率）
- 依赖：OpenCV, RapidOCR

## 使用方法

```bash
# 安装依赖
pip install -r requirements.txt

# 运行脚本
python3 wzry_auto.py
```

## 项目结构

```
WZRY_Farm/
├── wzry_auto.py          # 主脚本
├── ocr_time.py           # OCR 成熟时间模块
├── assets/
│   ├── templates/        # 模板图片
│   └── screenshots/      # 参考截图
└── requirements.txt      # 依赖列表
```

## 模板说明

- `start_game.png` - 开始游戏按钮
- `close_popup.png` - 关闭弹窗按钮
- `lainongchang.png` - 进入农场按钮
- `refresh_pos.png` - 刷新站位按钮
- `oneclick_farm.png` - 一键务农按钮
- `harvest_continue.png` - 收获继续按钮
- `back_arrow.png` - 返回箭头按钮

## 注意事项

- 模拟器分辨率需设为 1280x720
- 游戏需已登录并可进入大厅
- 每次操作后脚本会等待足够时间再进行下一步
