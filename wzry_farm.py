#!/usr/bin/env python3
"""
王者荣耀农场自动化 - 主程序
自动执行: 进入农场 → 一键务农 → 读取成熟时间 → 定时重复
"""

import os
import sys
import time
import subprocess
import cv2
import numpy as np
from datetime import datetime, timedelta

# ============ 配置 ============
DEVICE = "192.168.31.165:5557"
ADB = "/tmp/platform-tools/adb"
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.join(PROJECT_DIR, "assets", "templates")
SCREENSHOT_PATH = "/tmp/wzry_current.png"

# 摇杆参数
JOYSTICK_CENTER = (160, 486)
REFRESH_BUTTON = (1184, 653)  # 仅用于备用，正常应模板匹配

# 移动参数
MOVE_TO_STATUE = {
    "angle": 60,  # 左上方60°
    "distance": 200,  # px
    "duration": 1500,  # ms
    "end_point": (60, 313)
}
MOVE_TO_FARMLAND = {
    "direction": "up",
    "distance": 200,
    "duration": 1000,
    "end_point": (160, 286)
}

# ============ ADB工具 ============
def adb_cmd(cmd):
    """执行ADB命令"""
    full_cmd = [ADB, "-s", DEVICE] + cmd.split()
    result = subprocess.run(full_cmd, capture_output=True, text=True, timeout=10)
    return result.stdout.strip()

def adb_shell(cmd):
    """执行ADB shell命令"""
    return adb_cmd(f"shell {cmd}")

def take_screenshot(save_path=SCREENSHOT_PATH):
    """截图"""
    adb_shell("screencap -p /sdcard/tmp.png")
    adb_cmd(f"pull /sdcard/tmp.png {save_path}")
    return save_path

def tap(x, y):
    """点击屏幕"""
    adb_shell(f"input tap {x} {y}")

def swipe(x1, y1, x2, y2, duration):
    """滑动"""
    adb_shell(f"input touchscreen swipe {x1} {y1} {x2} {y2} {duration}")

def start_app():
    """启动王者荣耀"""
    adb_shell("am start -n com.tencent.tmgp.sgame/.SGameActivity")

def kill_app():
    """杀死王者荣耀"""
    adb_shell("am force-stop com.tencent.tmgp.sgame")

# ============ 模板匹配 ============
def load_template(name):
    """加载模板图片"""
    path = os.path.join(TEMPLATE_DIR, name)
    if os.path.exists(path):
        return cv2.imread(path)
    return None

def match_template(screenshot_path, template_name, threshold=0.4):
    """模板匹配，返回匹配位置和分数"""
    img = cv2.imread(screenshot_path)
    tpl = load_template(template_name)
    
    if img is None or tpl is None:
        return None, 0
    
    res = cv2.matchTemplate(img, tpl, cv2.TM_CCOEFF_NORMED)
    _, max_val, _, max_loc = cv2.minMaxLoc(res)
    
    h, w = tpl.shape[:2]
    center = (max_loc[0] + w // 2, max_loc[1] + h // 2)
    
    if max_val >= threshold:
        return center, max_val
    return None, max_val

def find_and_tap(template_name, threshold=0.4):
    """模板匹配并点击"""
    path = take_screenshot()
    center, score = match_template(path, template_name, threshold)
    
    if center:
        print(f"  ✓ 找到 {template_name}: ({center[0]},{center[1]}) score={score:.3f}")
        tap(center[0], center[1])
        return True
    else:
        print(f"  ✗ 未找到 {template_name} (score={score:.3f})")
        return False

# ============ OCR读取成熟时间 ============
_ocr_instance = None

def get_ocr():
    """获取OCR实例"""
    global _ocr_instance
    if _ocr_instance is None:
        from rapidocr_onnxruntime import RapidOCR
        _ocr_instance = RapidOCR()
    return _ocr_instance

def read_maturity_time():
    """读取成熟时间，返回 (minutes, seconds) 或 None"""
    img = cv2.imread(SCREENSHOT_PATH)
    if img is None:
        return None
    
    # 裁剪成熟时间区域（左侧面板）
    roi = img[250:400, 0:300]
    
    ocr = get_ocr()
    result, _ = ocr(roi)
    
    if result:
        import re
        for line in result:
            text = line[1]
            match = re.search(r'(\d{1,2}):(\d{2})', text)
            if match:
                return int(match.group(1)), int(match.group(2))
    
    return None

def calculate_wait_time(minutes, seconds, advance_minutes=1):
    """计算等待时间"""
    now = datetime.now()
    total_seconds = minutes * 60 + seconds
    maturity_time = now + timedelta(seconds=total_seconds)
    next_farm = maturity_time - timedelta(minutes=advance_minutes)
    wait_seconds = max(0, (next_farm - now).total_seconds())
    
    return {
        "maturity_text": f"{minutes}:{seconds:02d}",
        "maturity_time": maturity_time.strftime("%H:%M:%S"),
        "next_farm_time": next_farm.strftime("%H:%M:%S"),
        "wait_seconds": wait_seconds,
        "wait_minutes": wait_seconds / 60
    }

# ============ 核心流程 ============
def refresh_position():
    """刷新站位到初始位置"""
    print("\n[1/7] 刷新站位...")
    if find_and_tap("refresh_pos.png"):
        time.sleep(2)
        return True
    return False

def move_to_statue():
    """移动到雕像旁（触发一键务农按钮）"""
    print("\n[2/7] 移动到雕像旁...")
    x1, y1 = JOYSTICK_CENTER
    x2, y2 = MOVE_TO_STATUE["end_point"]
    swipe(x1, y1, x2, y2, MOVE_TO_STATUE["duration"])
    time.sleep(1.5)
    return True

def click_oneclick_farm():
    """点击一键务农按钮"""
    print("\n[3/7] 点击一键务农...")
    return find_and_tap("oneclick_farm.png")

def handle_harvest_popup():
    """处理收获弹窗"""
    print("\n[4/7] 处理收获弹窗...")
    time.sleep(2)
    # 点击屏幕底部"继续"
    tap(640, 650)
    time.sleep(1)
    return True

def wait_watering_animation():
    """等待浇水动画"""
    print("\n[5/7] 等待浇水动画...")
    time.sleep(4)
    return True

def move_to_farmland():
    """移动到农田"""
    print("\n[6/7] 移动到农田...")
    x1, y1 = JOYSTICK_CENTER
    x2, y2 = MOVE_TO_FARMLAND["end_point"]
    swipe(x1, y1, x2, y2, MOVE_TO_FARMLAND["duration"])
    time.sleep(1.5)
    return True

def check_crop_status():
    """检查作物状态"""
    print("\n[7/7] 检查作物状态...")
    take_screenshot()
    
    # 尝试读取成熟时间
    time_tuple = read_maturity_time()
    
    if time_tuple:
        minutes, seconds = time_tuple
        info = calculate_wait_time(minutes, seconds)
        print(f"  成熟时间: {info['maturity_text']}")
        print(f"  下次务农: {info['next_farm_time']}")
        print(f"  等待: {info['wait_minutes']:.1f} 分钟")
        return info
    
    # 检查是否可收获
    img = cv2.imread(SCREENSHOT_PATH)
    if img is not None:
        # 检查是否有收获按钮
        # 这里可以添加模板匹配逻辑
        print("  作物状态: 可能已成熟（无倒计时）")
    
    return None

def run_farm_cycle():
    """执行一次完整的务农循环"""
    print("=" * 50)
    print(f"开始务农 - {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 50)
    
    steps = [
        refresh_position,
        move_to_statue,
        click_oneclick_farm,
        handle_harvest_popup,
        wait_watering_animation,
        move_to_farmland,
        check_crop_status
    ]
    
    for i, step in enumerate(steps):
        try:
            result = step()
            if result is None and i == len(steps) - 1:
                # 最后一步返回None，可能是已成熟
                pass
        except Exception as e:
            print(f"  ✗ 步骤 {i+1} 失败: {e}")
            return None
    
    print("\n" + "=" * 50)
    print("务农完成")
    print("=" * 50)
    
    return check_crop_status()

def wait_and_repeat(wait_seconds):
    """等待指定时间后重复"""
    if wait_seconds <= 0:
        print("无需等待，立即执行")
        return
    
    print(f"\n等待 {wait_seconds/60:.1f} 分钟...")
    
    # 每60秒打印一次进度
    remaining = wait_seconds
    while remaining > 0:
        if remaining > 60:
            print(f"  剩余 {remaining/60:.0f} 分钟")
            time.sleep(60)
            remaining -= 60
        else:
            print(f"  剩余 {remaining:.0f} 秒")
            time.sleep(remaining)
            remaining = 0

def main():
    """主函数"""
    print("王者荣耀农场自动化")
    print("按 Ctrl+C 停止")
    print()
    
    # 连接设备
    print(f"连接设备: {DEVICE}")
    subprocess.run([ADB, "connect", DEVICE], capture_output=True)
    
    cycle = 0
    while True:
        cycle += 1
        print(f"\n{'='*50}")
        print(f"第 {cycle} 轮务农")
        print(f"{'='*50}")
        
        # 执行务农循环
        info = run_farm_cycle()
        
        if info and "wait_seconds" in info:
            # 等待后重复
            wait_and_repeat(info["wait_seconds"])
        else:
            # 无法获取时间，等待5分钟后重试
            print("无法获取成熟时间，5分钟后重试")
            wait_and_repeat(300)

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n用户停止")
        sys.exit(0)
