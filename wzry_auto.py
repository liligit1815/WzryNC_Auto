#!/usr/bin/env python3
"""
王者荣耀农场自动化务农 v3
完全基于模板匹配 + 摇杆操作
"""

import cv2
import numpy as np
import subprocess
import time
import math
import os
from pathlib import Path

# ============================================================
# 配置
# ============================================================
SCRIPT_DIR = Path(__file__).parent
ASSETS_DIR = SCRIPT_DIR / "assets"
TEMPLATE_DIR = ASSETS_DIR / "templates"
SCREENSHOT_PATH = str(ASSETS_DIR / "current.png")

GAME_PKG = "com.tencent.tmgp.sgame"
GAME_ACT = f"{GAME_PKG}/com.tencent.tmgp.sgame.SGameActivity"

# 模拟器配置
import shutil as _shutil
_ADB = _shutil.which("adb") or (
    "/tmp/platform-tools/adb" if Path("/tmp/platform-tools/adb").exists() else "adb"
)
ADB = _ADB
DEVICE = os.environ.get("WZRY_DEVICE", "192.168.31.165:5557")
BASE_W, BASE_H = 1280, 720

# 摇杆配置（从测试结果）
JOYSTICK_CENTER = (160, 486)
JOYSTICK_RADIUS = 200

# ============================================================
# ADB 基础操作
# ============================================================
def adb_shell(cmd):
    """执行ADB shell命令"""
    full_cmd = f"{ADB} -s {DEVICE} shell {cmd}"
    result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=10)
    return result.stdout

def screenshot(path=SCREENSHOT_PATH):
    """截图"""
    adb_shell("screencap -p /sdcard/screen.png")
    subprocess.run(f"{ADB} -s {DEVICE} pull /sdcard/screen.png {path}", 
                   shell=True, capture_output=True, timeout=10)
    return path

def tap(x, y, label=""):
    """点击屏幕"""
    if label:
        print(f"  👆 tap ({x}, {y}) [{label}]")
    adb_shell(f"input tap {x} {y}")

def swipe(x1, y1, x2, y2, duration_ms=1000):
    """滑动"""
    adb_shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")

# ============================================================
# 模板匹配
# ============================================================
def find_template(template_name, screenshot_path, threshold=0.6):
    """在截图中查找模板"""
    template_path = TEMPLATE_DIR / template_name
    if not template_path.exists():
        print(f"  ⚠️ 模板不存在: {template_path}")
        return None
    
    img = cv2.imread(screenshot_path)
    tmpl = cv2.imread(str(template_path))
    if img is None or tmpl is None:
        return None
    
    # 多尺度匹配
    img_h, img_w = img.shape[:2]
    tmpl_h, tmpl_w = tmpl.shape[:2]
    
    scales = [1.0]
    if tmpl_w > 200:
        scales.append(img_w / tmpl_w)
    else:
        for s in [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]:
            if abs(s - 1.0) > 0.05:
                scales.append(s)
    
    best_score = -1
    best_loc = None
    best_tw, best_th = tmpl_w, tmpl_h
    
    for s in scales:
        if abs(s - 1.0) < 0.01:
            t = tmpl
            tw, th = tmpl_w, tmpl_h
        else:
            nw, nh = int(tmpl_w * s), int(tmpl_h * s)
            if nw > img_w or nh > img_h or nw < 5 or nh < 5:
                continue
            t = cv2.resize(tmpl, (nw, nh))
            tw, th = nw, nh
        
        result = cv2.matchTemplate(img, t, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, max_loc = cv2.minMaxLoc(result)
        
        if max_val > best_score:
            best_score = max_val
            best_loc = max_loc
            best_tw, best_th = tw, th
    
    if best_score >= threshold and best_loc is not None:
        cx = best_loc[0] + best_tw // 2
        cy = best_loc[1] + best_th // 2
        print(f"  ✅ '{template_name}': score={best_score:.3f} @ ({cx},{cy})")
        return {"x": cx, "y": cy, "score": best_score}
    else:
        print(f"  ❌ '{template_name}': 未匹配 ({best_score:.3f} < {threshold})")
        return None

def has_template(template_name, screenshot_path, threshold=0.6):
    """检查模板是否存在"""
    return find_template(template_name, screenshot_path, threshold) is not None

def click_template(template_name, screenshot_path, threshold=0.6, label=""):
    """匹配并点击模板"""
    result = find_template(template_name, screenshot_path, threshold)
    if result:
        tap(result["x"], result["y"], label or template_name)
        return True
    return False

# ============================================================
# 摇杆操作
# ============================================================
def move_joystick(angle_deg, distance=200, duration_ms=1500):
    """向指定角度推动摇杆"""
    cx, cy = JOYSTICK_CENTER
    angle_rad = math.radians(angle_deg)
    tx = int(cx + distance * math.cos(angle_rad))
    ty = int(cy - distance * math.sin(angle_rad))
    swipe(cx, cy, tx, ty, duration_ms)
    print(f"  🎮 摇杆 ({cx},{cy})→({tx},{ty}) {angle_deg}° {duration_ms}ms")

def check_at_initial_position():
    """检查角色是否在初始位置（石盘）"""
    screenshot(SCREENSHOT_PATH)
    # 检查是否有refresh_pos按钮（在初始位置才会显示）
    return has_template("refresh_pos.png", SCREENSHOT_PATH, 0.5)

def reset_position():
    """刷新站位重置角色位置"""
    print("  🔄 刷新站位...")
    if click_template("refresh_pos.png", SCREENSHOT_PATH, 0.5, "刷新站位"):
        print("  ⏳ 等待5秒...")
        time.sleep(5)
        return True
    return False

# ============================================================
# OCR 成熟时间
# ============================================================
_ocr_engine = None

def get_ocr():
    global _ocr_engine
    if _ocr_engine is None:
        from rapidocr_onnxruntime import RapidOCR
        _ocr_engine = RapidOCR()
    return _ocr_engine

def read_maturity_time(screenshot_path):
    """从截图中读取成熟时间（绝对时间，如16:46=16点46分）
    返回: (hour, minute) 元组，或None
    """
    import re

    img = cv2.imread(screenshot_path)
    if img is None:
        return None

    # 裁剪成熟时间区域（左侧面板）
    roi = img[250:400, 0:300]

    ocr = get_ocr()
    result, _ = ocr(roi)

    if result:
        for line in result:
            text = line[1]
            time_match = re.search(r'(\d{1,2}):(\d{2})', text)
            if time_match:
                hour = int(time_match.group(1))
                minute = int(time_match.group(2))
                print(f"  🕐 OCR识别: {hour}点{minute:02d}分")
                return (hour, minute)

    return None

# ============================================================
# 步骤1: 检测状态
# ============================================================
def step1_check_status():
    """步骤1: 检测APP是否在前台"""
    print("\n[步骤1] 检测状态...")
    
    out = adb_shell("dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'")
    
    if GAME_PKG in out:
        print("  🎮 王者荣耀在前台，退出...")
        adb_shell(f"am force-stop {GAME_PKG}")
        print("  ⏳ 等待5秒...")
        time.sleep(5)
        return True
    else:
        print("  ✅ 王者荣耀不在前台")
        return False

# ============================================================
# 步骤2: 启动游戏
# ============================================================
def step2_launch_game():
    """步骤2: 启动游戏"""
    print("\n[步骤2] 启动游戏...")
    adb_shell(f"am start -n {GAME_ACT}")
    print("  ⏳ 等待40秒...")
    time.sleep(40)
    return True

# ============================================================
# 步骤3: 点击开始游戏
# ============================================================
def step3_click_start_game():
    """步骤3: 点击开始游戏"""
    print("\n[步骤3] 点击开始游戏...")
    
    for attempt in range(5):
        print(f"  尝试 {attempt+1}/5...")
        screenshot(SCREENSHOT_PATH)
        
        if click_template("start_game.png", SCREENSHOT_PATH, 0.7, "开始游戏"):
            print("  ⏳ 等待40秒...")
            time.sleep(40)
            return True
        
        if attempt < 4:
            print("  ⏳ 等待5秒...")
            time.sleep(5)
    
    print("  ❌ 连续5次失败，返回步骤1")
    return False

# ============================================================
# 步骤4: 关闭弹窗
# ============================================================
def step4_close_popup():
    """步骤4: 关闭弹窗（多个弹窗依次关闭）"""
    print("\n[步骤4] 关闭弹窗...")

    miss_count = 0
    for i in range(10):  # 最多处理10个弹窗
        screenshot(SCREENSHOT_PATH)
        result = find_template("close_popup.png", SCREENSHOT_PATH, 0.7)

        if result:
            x, y = result["x"], result["y"]
            # 位置校验：弹窗关闭按钮应在屏幕中央区域（x>800, y<200）
            if x > 800 and y < 200:
                tap(x, y, "关闭弹窗")
                miss_count = 0  # 重置未匹配计数
                print("  ⏳ 等待5秒...")
                time.sleep(5)
            else:
                miss_count += 1
                print(f"  ⚠️ 位置不匹配 ({x},{y})，未匹配 {miss_count}/3")
        else:
            miss_count += 1
            print(f"  ⚠️ 未找到弹窗，未匹配 {miss_count}/3")

        # 连续3次未匹配，认为弹窗全部关闭
        if miss_count >= 3:
            print("  ✅ 弹窗处理完毕（连续3次未匹配）")
            break

    return True

# ============================================================
# 步骤5: 进入农场
# ============================================================
def step5_enter_farm():
    """步骤5: 进入农场"""
    print("\n[步骤5] 进入农场...")

    for attempt in range(10):  # 最多尝试10次
        screenshot(SCREENSHOT_PATH)

        if click_template("lainongchang.png", SCREENSHOT_PATH, 0.6, "进入农场"):
            print("  ⏳ 等待40秒...")
            time.sleep(40)
            return True

        print(f"  ⏳ 等待5秒... ({attempt+1}/10)")
        time.sleep(5)

    print("  ❌ 连续10次未找到进入农场按钮")
    return False

# ============================================================
# 步骤6: 移动到雕像
# ============================================================
def step6_move_to_statue():
    """步骤6: 移动到雕像"""
    print("\n[步骤6] 移动到雕像...")
    
    # 检查是否在初始位置
    if check_at_initial_position():
        print("  ✅ 在初始位置")
    else:
        print("  🔄 不在初始位置，刷新站位...")
        reset_position()
    
    # 向左上方60度移动
    move_joystick(120, 200, 1500)  # 120度是左上方
    print("  ⏳ 等待移动...")
    time.sleep(5)
    return True

# ============================================================
# 步骤7: 一键务农
# ============================================================
def step7_oneclick_farm():
    """步骤7: 一键务农"""
    print("\n[步骤7] 一键务农...")
    
    screenshot(SCREENSHOT_PATH)
    
    if has_template("oneclick_farm.png", SCREENSHOT_PATH, 0.5):
        print("  ✅ 找到一键务农按钮")
        print("  ⏳ 等待3秒...")
        time.sleep(3)
        
        click_template("oneclick_farm.png", SCREENSHOT_PATH, 0.5, "一键务农")
        print("  ⏳ 等待5秒...")
        time.sleep(5)
        return True
    else:
        print("  ❌ 未找到一键务农，返回步骤6")
        return False

# ============================================================
# 步骤8: 关闭收获弹窗
# ============================================================
def step8_close_harvest():
    """步骤8: 关闭收获弹窗"""
    print("\n[步骤8] 关闭收获弹窗...")
    
    for attempt in range(3):
        screenshot(SCREENSHOT_PATH)
        
        if has_template("harvest_continue.png", SCREENSHOT_PATH, 0.8):
            print("  ✅ 找到收获弹窗")
            print("  ⏳ 等待3秒...")
            time.sleep(3)
            
            click_template("harvest_continue.png", SCREENSHOT_PATH, 0.8, "继续")
            print("  ⏳ 等待5秒...")
            time.sleep(5)
            return True
        else:
            print(f"  ⚠️ 未找到收获弹窗，等待3秒后重试 ({attempt+1}/3)")
            time.sleep(3)
    
    print("  ⚠️ 连续3次未找到收获弹窗，进入步骤9")
    return False

# ============================================================
# 步骤9: 移动到土地
# ============================================================
def step9_move_to_farmland():
    """步骤9: 移动到土地"""
    print("\n[步骤9] 移动到土地...")
    
    # 向上方移动
    move_joystick(90, 200, 1500)  # 90度是正上方
    print("  ⏳ 等待移动...")
    time.sleep(5)
    
    # 截图OCR
    screenshot(SCREENSHOT_PATH)
    maturity_time = read_maturity_time(SCREENSHOT_PATH)
    
    return maturity_time

# ============================================================
# 步骤10: 计算等待时间
# ============================================================
def step10_calculate_wait(maturity_time):
    """步骤10: 根据预计成熟时间计算等待时间并退出游戏
    maturity_time: (hour, minute) 元组
    """
    from datetime import datetime, timedelta

    print("\n[步骤10] 计算等待时间...")

    # 先杀掉游戏
    print("  🛑 退出王者荣耀...")
    adb_shell(f"am force-stop {GAME_PKG}")

    if maturity_time is None:
        print("  ⚠️ 无法识别成熟时间，等待5分钟后重试...")
        time.sleep(300)
        return True

    hour, minute = maturity_time
    now = datetime.now()
    target = now.replace(hour=hour, minute=minute, second=0, microsecond=0)

    # 提前1分钟启动
    target -= timedelta(minutes=1)

    if target <= now:
        print(f"  ⚠️ 成熟时间 {hour}点{minute:02d}分 已过，立即重新启动")
        return True

    wait_seconds = int((target - now).total_seconds())
    minutes = wait_seconds // 60
    seconds = wait_seconds % 60
    print(f"  🕐 预计成熟时间: {hour}点{minute:02d}分")
    print(f"  ⏳ 等待 {minutes}分{seconds:02d}秒 后重新启动（提前1分钟）")
    time.sleep(wait_seconds)
    return True
# ============================================================
# 主流程
# ============================================================
def main():
    """主流程"""
    print("=" * 60)
    print("王者荣耀农场自动化务农 v3")
    print("=" * 60)
    
    round_num = 0
    while True:
        round_num += 1
        print(f"\n{'='*60}")
        print(f"# 第 {round_num} 轮务农")
        print(f"{'='*60}")
        
        # 步骤1: 检测状态
        step1_check_status()
        
        # 步骤2: 启动游戏
        step2_launch_game()
        
        # 步骤3: 点击开始游戏
        if not step3_click_start_game():
            print("\n⚠️ 步骤3失败，重新开始...")
            continue
        
        # 步骤4: 关闭弹窗
        step4_close_popup()
        
        # 步骤5: 进入农场
        if not step5_enter_farm():
            print("\n⚠️ 步骤5失败，重新开始...")
            continue
        
        # 步骤6: 移动到雕像
        step6_move_to_statue()
        
        # 步骤7: 一键务农
        if not step7_oneclick_farm():
            print("\n⚠️ 步骤7失败，返回步骤6...")
            step6_move_to_statue()
            step7_oneclick_farm()
        
        # 步骤8: 关闭收获弹窗
        step8_close_harvest()
        
        # 步骤9: 移动到土地
        maturity_time = step9_move_to_farmland()

        # 步骤10: 根据成熟时间计算等待并退出
        step10_calculate_wait(maturity_time)

if __name__ == "__main__":
    main()
