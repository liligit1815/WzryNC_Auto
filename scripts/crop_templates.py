#!/usr/bin/env python3
"""从用户截图裁剪模板图片 - 修正版"""
import cv2
import os

SRC = "/vol1/1000/Hermes/warync/720_1280"
DST = "/vol1/1000/Hermes/WZRY_Farm/assets/templates"

def save(name, img):
    path = os.path.join(DST, name)
    cv2.imwrite(path, img)
    h, w = img.shape[:2]
    print(f"  ✅ {name}: {w}x{h}")

# 清理旧模板
for f in os.listdir(DST):
    if f.endswith('.png'):
        os.remove(os.path.join(DST, f))

# ============================================================
# 1. 直接复制用户已裁剪的小图（这些是精确的UI元素）
# ============================================================
print("=== 复制用户已裁剪模板 ===")

copy_map = {
    "start_game.png": "kaishiyouxi.png",         # 开始游戏文字
    "close_popup.png": "tanchuangguanbi.png",     # 弹窗关闭X
    "oneclick_farm.png": "yijianwunong.png",      # 一键务农按钮
    "refresh_pos.png": "shuaxinzhanwei.png",      # 刷新站位按钮
    "harvest_continue.png": "wunongjixu.png",     # 点击继续
    "back_arrow.png": "zuoshangjiaofanhui.png",   # 回退箭头
    "crop_panel.png": "nongzuowuchengshuxinxi.png", # 成熟时间文字
    "statue_platform.png": "diaoxiangpingtai.png", # 雕像平台
    "soil_sample.png": "tudi.png",                # 土壤样本
}

for dst_name, src_name in copy_map.items():
    img = cv2.imread(os.path.join(SRC, src_name))
    if img is not None:
        save(dst_name, img)
    else:
        print(f"  ❌ 读取失败: {src_name}")

# ============================================================
# 2. 从1280x720全屏截图精确裁剪
# ============================================================
print("\n=== 从全屏截图裁剪 ===")

# --- 农场入口 "来农场 干农活" ---
# 坐标来源：视觉分析 center=(350, 480)，裁剪区域扩大一些
farm = cv2.imread(os.path.join(SRC, "nongchangzhuye.png"))
if farm is not None:
    # 农场入口按钮（菠萝图标+文字）
    fe = farm[440:530, 260:460]
    save("farm_entry.png", fe)

    # 农场回退箭头（左上角）
    ba = farm[5:65, 5:65]
    save("back_arrow_farm.png", ba)

    # 摇杆区域
    js = farm[580:720, 30:220]
    save("joystick_area.png", js)

    # 雕像区域
    st = farm[280:420, 280:450]
    save("statue_area.png", st)

    # 右侧按钮面板（一键务农等）
    rp = farm[400:650, 950:1280]
    save("farm_right_panel.png", rp)

# --- 从有弹窗的大厅裁剪农场入口 ---
popup = cv2.imread(os.path.join(SRC, "youtanchuangdating.png"))
if popup is not None:
    # 农场入口（从有弹窗的大厅截图）
    fe_popup = popup[440:530, 260:460]
    save("farm_entry_popup.png", fe_popup)

    # 弹窗关闭按钮区域（弹窗右上角）
    pc = popup[80:230, 700:900]
    save("popup_close_area.png", pc)

# --- 从角色站在土地上的截图裁剪 ---
soil_full = cv2.imread(os.path.join(SRC, "juesezhanzaitudishang.png"))
if soil_full is not None:
    # 绿色边框区域（角色左侧农田）
    gb = soil_full[300:550, 100:450]
    save("green_border_area.png", gb)

    # 左侧作物信息面板
    cp = soil_full[150:400, 0:280]
    save("crop_info_panel.png", cp)

# --- 从触发一键务农的截图裁剪 ---
trigger = cv2.imread(os.path.join(SRC, "chufayijianwunongnongchangzhuye.png"))
if trigger is not None:
    # 一键务农按钮区域（右侧蓝色按钮）
    oc = trigger[420:580, 1050:1250]
    save("oneclick_area.png", oc)

# --- 从登录界面裁剪 ---
login = cv2.imread(os.path.join(SRC, "wutanchuangzhuye.png"))
if login is not None:
    # "开始游戏"按钮区域（底部中央）
    sg = login[620:720, 420:680]
    save("login_start_area.png", sg)

print(f"\n=== 完成 ===")
print(f"输出目录: {DST}")
print(f"模板数量: {len([f for f in os.listdir(DST) if f.endswith('.png')])}")
