#!/usr/bin/env python3
"""
王者荣耀农场自动化务农 v3
完全基于模板匹配 + 摇杆操作
新增：浇水时间计算、统计功能、多分辨率支持、唤醒解锁、亮度控制
"""

import cv2
import numpy as np
import subprocess
import time
import math
import os
import signal
import sys
from pathlib import Path
from datetime import datetime, timedelta

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
DEVICE = os.environ.get("WZRY_DEVICE", "192.168.31.197:38983")
UNLOCK_PWD = os.environ.get("WZRY_UNLOCK_PWD", "")  # 锁屏密码，为空则不输入密码
BASE_W, BASE_H = 1280, 720

# 摇杆配置（从测试结果）
JOYSTICK_CENTER = (160, 486)
JOYSTICK_RADIUS = 200

# 步骤6移动参数（按分辨率配置）
# 格式: (w,h): {"center": (cx,cy), "angle": 度, "distance": px, "duration": ms}
STEP6_CONFIG = {
    (1280, 720): {"center": (160, 486), "angle": 120, "distance": 200, "duration": 1500},
    (2400, 1080): {"center": (430, 755), "angle": 120, "distance": 250, "duration": 1500},
}

# 默认步骤6配置
_step6_cfg = {"center": (160, 486), "angle": 120, "distance": 200, "duration": 1500}

# ============================================================
# 统计数据
# ============================================================
class Stats:
    def __init__(self):
        self.rounds = 0          # 执行轮数
        self.harvests = 0        # 成熟收获次数
        self.total_exp = 0       # 累计获得经验
        self.total_crops = {}    # 累计收获作物 {作物名: 数量}
        self.start_time = datetime.now()
    
    def add_harvest(self, exp=0, crops=None):
        """记录一次收获"""
        self.harvests += 1
        if exp > 0:
            self.total_exp += exp
        if crops:
            for name, count in crops.items():
                self.total_crops[name] = self.total_crops.get(name, 0) + count
    
    def summary(self):
        elapsed = datetime.now() - self.start_time
        hours = elapsed.total_seconds() / 3600
        print("\n" + "=" * 60)
        print("📊 务农统计")
        print("=" * 60)
        print(f"  执行轮数: {self.rounds}")
        print(f"  成熟收获: {self.harvests} 次")
        if self.total_exp > 0:
            print(f"  累计经验: +{self.total_exp}")
        if self.total_crops:
            for name, qty in self.total_crops.items():
                print(f"  {name}: {qty} 个")
        print(f"  运行时长: {hours:.1f} 小时")
        print(f"  开始时间: {self.start_time.strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"  结束时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print("=" * 60)

stats = Stats()

def signal_handler(sig, frame):
    print("\n\n⚠️ 收到终止信号，正在退出...")
    stats.summary()
    sys.exit(0)

signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)

# ============================================================
# 浇水时间计算
# ============================================================
import json

# 存储文件路径
CYCLE_FILE = str(ASSETS_DIR / "crop_cycle.json")

def save_crop_cycle(crop_name, cycle_min):
    """保存作物周期到文件"""
    data = {
        "crop_name": crop_name,
        "cycle_min": cycle_min,
        "update_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    }
    with open(CYCLE_FILE, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"  💾 已保存作物周期: {crop_name} = {cycle_min}分钟")

def load_crop_cycle():
    """从文件加载作物周期，返回 (crop_name, cycle_min) 或 (None, None)"""
    if not os.path.exists(CYCLE_FILE):
        return None, None
    try:
        with open(CYCLE_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)
        crop_name = data.get("crop_name")
        cycle_min = data.get("cycle_min")
        update_time = data.get("update_time", "未知")
        print(f"  📂 读取存储周期: {crop_name} = {cycle_min}分钟 (更新于 {update_time})")
        return crop_name, cycle_min
    except:
        return None, None

def clear_crop_cycle():
    """清除存储的作物周期（新种植时调用）"""
    if os.path.exists(CYCLE_FILE):
        os.remove(CYCLE_FILE)
        print("  🗑️ 已清除旧作物周期记录")

def calculate_plant_cycle_and_water_time(first_water_time, show_mature_time, save_if_fresh=False):
    """
    根据第一次浇水时间 + 第一次浇水后显示的成熟时间
    自动计算：作物周期、后续完美浇水时间、最终成熟时间
    :param first_water_time: 第一次浇水时间 (datetime)
    :param show_mature_time: 第一次浇水后显示的成熟时间 (datetime)
    :param save_if_fresh: 如果是新种植（刚收获），保存计算的周期
    :return: dict with plant_cycle_min, water2, water3, mature_time
    """
    time_format = "%H:%M:%S"
    
    # 计算第一次浇水后剩余分钟
    delta_sec = (show_mature_time - first_water_time).total_seconds()
    remain_min = delta_sec / 60
    print(f"  💧 第一次浇水后显示剩余时间：{remain_min:.2f} 分钟")
    
    # 优先使用存储的作物周期
    stored_crop, stored_cycle = load_crop_cycle()
    
    if stored_cycle is not None and not save_if_fresh:
        cycle_min = stored_cycle
        print(f"  ✅ 使用存储的作物周期：{stored_crop} = {cycle_min}分钟")
    else:
        # 匹配作物原始周期（游戏固定5种作物）
        plant_rules = [
            {"cycle": 5, "remain": 5},       # 5分钟
            {"cycle": 60, "remain": 55},     # 1小时
            {"cycle": 480, "remain": 400},   # 8小时
            {"cycle": 960, "remain": 800},   # 16小时
            {"cycle": 1920, "remain": 1600}, # 32小时
        ]
        
        # 找最接近的作物周期
        best_match = min(plant_rules, key=lambda x: abs(x["remain"] - remain_min))
        cycle_min = best_match["cycle"]
        
        # 如果是新种植，保存计算的周期
        if save_if_fresh:
            save_crop_cycle("作物", cycle_min)
            print(f"  💾 新种植，已保存周期：{cycle_min}分钟")
        else:
            print(f"  ⚠️ 无存储周期，自动匹配为：{cycle_min}分钟")
    
    cycle_hour = cycle_min // 60
    
    # 计算完美浇水时间点（从第一次浇水开始算）
    water2_rel = cycle_min / 3
    water3_rel = cycle_min * 2 / 3
    water4_rel = cycle_min * 11 / 15  # 第四次 = 完美成熟
    
    # 转换为真实时间
    water2_time = first_water_time + timedelta(minutes=water2_rel)
    water3_time = first_water_time + timedelta(minutes=water3_rel)
    water4_time = first_water_time + timedelta(minutes=water4_rel)
    
    print(f"  🌱 作物原始周期：{cycle_hour} 小时（{cycle_min} 分钟）")
    print(f"  💧 第二次完美浇水时间：{water2_time.strftime(time_format)}")
    print(f"  💧 第三次完美浇水时间：{water3_time.strftime(time_format)}")
    print(f"  🌾 第四次浇水（成熟收获）：{water4_time.strftime(time_format)}")
    
    return {
        "plant_cycle_min": cycle_min,
        "water2": water2_time,
        "water3": water3_time,
        "mature_time": water4_time
    }

# ============================================================
# ADB 基础操作
# ============================================================
def adb_shell(cmd):
    """执行ADB shell命令（不使用管道，兼容Windows）"""
    full_cmd = f"{ADB} -s {DEVICE} shell {cmd}"
    result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=10)
    return result.stdout

def adb_shell_root(cmd):
    """执行ADB root shell命令（用列表传参，避免cmd.exe解析>等特殊字符）"""
    args = [ADB, "-s", DEVICE, "shell", "su", "-c", cmd]
    result = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=10)
    return result.stdout

def wake_and_unlock(password=""):
    """唤醒屏幕并解锁"""
    global _original_brightness, _original_auto_brightness
    
    # 检测屏幕是否亮着
    out = adb_shell("dumpsys power | grep mHoldingDisplaySuspendBlocker")
    if "mHoldingDisplaySuspendBlocker=true" in out:
        print("  📱 屏幕已亮，无需唤醒")
        return True
    
    print("  🔆 唤醒屏幕...")
    
    # 如果之前设置了低亮度，先应用再唤醒（防止闪亮）
    if _original_brightness is not None:
        if _brightness_mode == 'root':
            set_brightness_zero_root()
        else:
            adb_shell("settings put system screen_brightness_mode 0")
            adb_shell("settings put system screen_brightness 1")
    
    # 唤醒屏幕
    adb_shell("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    
    # 上滑显示密码输入框
    adb_shell("input swipe 540 1800 540 500 300")
    time.sleep(1)
    
    # 输入密码
    if password:
        print("  🔑 输入密码...")
        adb_shell(f"input text {password}")
        time.sleep(0.5)
        adb_shell("input keyevent 66")  # 回车确认
        time.sleep(2)
    
    print("  ✅ 屏幕已唤醒并解锁")
    return True

# ============================================================
# 屏幕亮度控制
# ============================================================
_original_brightness = None
_original_auto_brightness = None
_brightness_mode = None  # 'low' 或 'root'

def get_brightness_settings():
    """获取当前亮度设置"""
    global _original_brightness, _original_auto_brightness
    
    # 获取当前亮度值 (0-255)
    out = adb_shell("settings get system screen_brightness")
    try:
        _original_brightness = int(out.strip())
    except:
        _original_brightness = 128
    
    # 获取自动亮度设置 (0=关闭, 1=开启)
    out = adb_shell("settings get system screen_brightness_mode")
    try:
        _original_auto_brightness = int(out.strip())
    except:
        _original_auto_brightness = 1
    
    print(f"  📊 当前亮度: {_original_brightness}/255, 自动亮度: {'开启' if _original_auto_brightness else '关闭'}")
    return _original_brightness, _original_auto_brightness

def set_brightness_low():
    """关闭自动亮度，将亮度降到最低"""
    print("  🔅 设置最低亮度...")
    # 关闭自动亮度
    adb_shell("settings put system screen_brightness_mode 0")
    # 设置亮度为最低 (1-255, 1为最低)
    adb_shell("settings put system screen_brightness 1")
    print("  ✅ 已关闭自动亮度，亮度设为最低")

def _reapply_low_brightness():
    """重新应用低亮度（杀游戏后调用，防止系统恢复亮度）"""
    if _original_brightness is None:
        return
    if _brightness_mode == 'root':
        adb_shell_root("echo 0 > /sys/class/backlight/panel0-backlight/brightness")
    else:
        adb_shell("settings put system screen_brightness 1")

def set_brightness_zero_root():
    """使用ROOT权限将亮度设为0"""
    print("  🔅 使用ROOT权限设置亮度为0...")
    # 关闭自动亮度
    adb_shell("settings put system screen_brightness_mode 0")
    # 使用ROOT权限直接写入亮度节点
    result = adb_shell_root("echo 0 > /sys/class/backlight/panel0-backlight/brightness")
    if result and ("Permission denied" in result or "error" in result.lower()):
        print(f"  ⚠️ 写入失败: {result}")
    else:
        # 验证是否写入成功
        verify = adb_shell_root("cat /sys/class/backlight/panel0-backlight/brightness")
        if verify.strip() == "0":
            print("  ✅ 已使用ROOT权限将亮度设为0")
        else:
            print(f"  ⚠️ 验证失败，当前亮度: {verify.strip()}")

def restore_brightness():
    """恢复原始亮度设置"""
    global _original_brightness, _original_auto_brightness, _brightness_mode
    
    if _original_brightness is None:
        return
    
    print("  🔆 恢复亮度设置...")
    
    # 如果使用了ROOT权限设置亮度0，先尝试恢复节点
    if _brightness_mode == 'root':
        # 尝试恢复亮度节点
        adb_shell_root(f"echo {_original_brightness} > /sys/class/backlight/panel0-backlight/brightness")
        adb_shell_root(f"echo {_original_brightness} > /sys/class/backlight/lcd-backlight/brightness")
    
    # 恢复亮度值
    adb_shell(f"settings put system screen_brightness {_original_brightness}")
    # 恢复自动亮度设置
    adb_shell(f"settings put system screen_brightness_mode {_original_auto_brightness}")
    print(f"  ✅ 已恢复亮度: {_original_brightness}/255, 自动亮度: {'开启' if _original_auto_brightness else '关闭'}")
    
    # 清空全局变量
    _original_brightness = None
    _original_auto_brightness = None
    _brightness_mode = None

def prompt_brightness_control():
    """询问用户是否降低亮度"""
    print("\n" + "=" * 60)
    print("💡 是否降低屏幕亮度以减少烧屏风险？")
    print("=" * 60)
    print("  Y - 关闭自动亮度，亮度降至最低(1)")
    print("  R - 使用ROOT权限，亮度设为0（突破厂商限制）")
    print("  N - 保持当前亮度设置")
    print("=" * 60)
    
    while True:
        choice = input("请选择 (Y/R/N): ").strip().upper()
        if choice in ['Y', 'YES']:
            get_brightness_settings()
            set_brightness_low()
            return 'low'
        elif choice in ['R', 'ROOT']:
            get_brightness_settings()
            set_brightness_zero_root()
            return 'root'
        elif choice in ['N', 'NO']:
            print("  ℹ️ 保持当前亮度设置")
            return None
        else:
            print("  ⚠️ 请输入 Y、R 或 N")

def screenshot(path=SCREENSHOT_PATH):
    """截图"""
    adb_shell("screencap -p /sdcard/screen.png")
    subprocess.run(f"{ADB} -s {DEVICE} pull /sdcard/screen.png {path}",
                   shell=True, capture_output=True, timeout=10)
    # 自动旋转：竖屏截图 → 横屏
    img = cv2.imread(path)
    if img is not None:
        h, w = img.shape[:2]
        if h > w:
            img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
            cv2.imwrite(path, img)
    return path

def detect_resolution():
    """检测设备横屏分辨率"""
    import re
    out = adb_shell("wm size")
    match = re.search(r'(\d+)x(\d+)', out)
    if match:
        w, h = int(match.group(1)), int(match.group(2))
        if h > w:
            w, h = h, w
        return w, h
    return 1280, 720

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
    """在截图中查找模板（支持多分辨率）"""
    img = cv2.imread(screenshot_path)
    if img is None:
        return None
    
    img_h, img_w = img.shape[:2]
    
    # 构建模板搜索路径：分辨率专用 > 默认
    template_dirs = []
    res_dir = TEMPLATE_DIR / f"{img_w}x{img_h}"
    if res_dir.exists():
        template_dirs.append(res_dir)
    template_dirs.append(TEMPLATE_DIR)
    
    best_score = -1
    best_loc = None
    best_tw, best_th = 0, 0
    
    for tdir in template_dirs:
        template_path = tdir / template_name
        if not template_path.exists():
            continue
        
        tmpl = cv2.imread(str(template_path))
        if tmpl is None:
            continue
        
        tmpl_h, tmpl_w = tmpl.shape[:2]
        tdir_label = "专用" if tdir != TEMPLATE_DIR else "默认"
        
        # 多尺度匹配（使用V1/V2的固定比例）
        scales = [1.0]
        if tmpl_w > 200:
            # 大模板：按截图比例缩放
            scales.append(img_w / tmpl_w)
        else:
            # 小模板：使用固定缩放比例
            for s in [0.5, 0.75, 1.25, 1.5, 2.0]:
                scales.append(s)
        
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
    
    if not best_loc:
        print(f"  ❌ '{template_name}': 模板不存在")
        return None
    
    if best_score >= threshold:
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
def move_joystick(angle_deg, distance=200, duration_ms=1500, center=None):
    """向指定角度推动摇杆"""
    cx, cy = center or JOYSTICK_CENTER
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
    返回: ((hour, minute), is_mature) 
        - 识别到时间: ((hour, minute), False)
        - 作物已成熟可收获: (None, True)
        - 无法识别: (None, False)
    """
    import re

    img = cv2.imread(screenshot_path)
    if img is None:
        return None, False

    # 裁剪成熟时间区域（左侧面板，覆盖"xx:xx成熟"和"可收获"区域）
    h, w = img.shape[:2]
    roi = img[h//3:2*h//3, 0:w//3]

    ocr = get_ocr()
    result, _ = ocr(roi)

    if result:
        all_text = " ".join([line[1] for line in result])
        print(f"  📝 OCR文本: {all_text}")
        
        # 检查是否显示"可收获"或"已成熟"（作物已经成熟）
        if "可收获" in all_text or "已成熟" in all_text:
            return None, True
        
        # 匹配成熟时间（如 "18:25成熟"、"明天00：02成熟"，兼容全角冒号）
        for line in result:
            text = line[1]
            time_match = re.search(r'(\d{1,2})[:\uff1a](\d{2})', text)
            if time_match:
                hour = int(time_match.group(1))
                minute = int(time_match.group(2))
                print(f"  🕐 OCR识别: {hour}点{minute:02d}分")
                return (hour, minute), False

    return None, False

def read_harvest_info(screenshot_path):
    """从收获弹窗截图中OCR识别收获信息
    返回: {"exp": int, "crops": {作物名: 数量}} 或 None
    """
    import re
    
    img = cv2.imread(screenshot_path)
    if img is None:
        return None
    
    h, w = img.shape[:2]
    # 收获弹窗通常在屏幕中央区域
    roi = img[int(h*0.2):int(h*0.8), int(w*0.2):int(w*0.8)]
    
    try:
        ocr = get_ocr()
        result, _ = ocr(roi)
    except Exception as e:
        print(f"  ⚠️ OCR失败: {e}")
        return None
    
    if not result:
        return None
    
    all_text = " ".join([line[1] for line in result])
    print(f"  📝 OCR文本: {all_text}")
    
    harvest = {"exp": 0, "crops": {}}
    
    # 识别经验
    # 匹配: "XP" + 数字, "农场经验" + 数字, "+数字经验", "经验+数字"
    exp_match = re.search(r'XP\s*(\d+)', all_text)
    if not exp_match:
        exp_match = re.search(r'(\d+)\s*XP', all_text)
    if not exp_match:
        exp_match = re.search(r'[+＋](\d+)\s*[经経]验', all_text)
    if not exp_match:
        exp_match = re.search(r'[经経]验\s*[+＋](\d+)', all_text)
    if exp_match:
        harvest["exp"] = int(exp_match.group(1))
    
    # 识别作物名和数量
    # OCR输出格式: 作物名和数字可能在不同行，按x坐标排序配对
    crop_names = {"番茄", "洋葱", "小麦", "土豆", "胡萝卜", "白菜", "玉米",
                  "南瓜", "草莓", "西瓜", "辣椒", "茄子", "黄瓜", "大豆"}
    
    # 提取所有文字及其位置
    items = []
    for line in result:
        text = line[1]
        x = line[0][0][0]  # 左上角x坐标
        y = line[0][0][1]  # 左上角y坐标
        items.append({"text": text, "x": x, "y": y})
    
    # 按y坐标排序，找数字行和作物名行的配对
    numbers = []  # [(x, value)]
    crops_found = []  # [(x, name)]
    
    for item in items:
        text = item["text"].strip()
        if text in crop_names:
            crops_found.append((item["x"], text))
        elif text.isdigit() and int(text) > 0:
            numbers.append((item["x"], int(text)))
    
    # 配对: 每个作物名找最近的数字（同y坐标或下方）
    for cx, cname in crops_found:
        best_num = 0
        best_dist = float("inf")
        for nx, nval in numbers:
            dist = abs(nx - cx)
            if dist < best_dist:
                best_dist = dist
                best_num = nval
        if best_num > 0:
            harvest["crops"][cname] = harvest["crops"].get(cname, 0) + best_num
    
    if harvest["exp"] > 0 or harvest["crops"]:
        return harvest
    return None

# ============================================================
# 步骤1: 检测状态
# ============================================================
def step1_check_status():
    """步骤1: 检测APP是否在前台"""
    print("\n[步骤1] 检测状态...")
    
    out = ""
    for attempt in range(3):
        # 方法1: 精确匹配ResumedActivity行（不在历史/缓存中误匹配）
        out = adb_shell("dumpsys activity activities")
        found = False
        for line in out.splitlines():
            stripped = line.strip()
            if ("ResumedActivity" in stripped or "topResumedActivity" in stripped) and GAME_PKG in stripped:
                found = True
                break
        if found:
            break
        # 方法2: mCurrentFocus
        out2 = adb_shell("dumpsys window")
        found2 = False
        for line in out2.splitlines():
            stripped = line.strip()
            if ("mCurrentFocus" in stripped or "mFocusedApp" in stripped) and GAME_PKG in stripped:
                found2 = True
                break
        if found2:
            break
        # 方法3: top activity
        out3 = adb_shell("dumpsys activity top")
        found3 = False
        for line in out3.splitlines():
            stripped = line.strip()
            if "ACTIVITY" in stripped and GAME_PKG in stripped:
                found3 = True
                break
        if found3:
            break
        print(f"  ⚠️ 检测失败，重试 {attempt+1}/3...")
        time.sleep(2)
    
    in_foreground = found or found2 or found3
    if in_foreground:
        print("  🎮 王者荣耀在前台，退出...")
        adb_shell(f"am force-stop {GAME_PKG}")
        print("  ⏳ 等待5秒...")
        time.sleep(5)
        return True
    else:
        print(f"  ✅ 王者荣耀不在前台")
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
        result = find_template("close_popup.png", SCREENSHOT_PATH, 0.9)

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
    
    # 使用分辨率专属配置或默认参数
    cfg = _step6_cfg
    move_joystick(cfg["angle"], cfg["distance"], cfg["duration"], center=cfg["center"])
    print(f"  ⏳ 等待移动...")
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
    """步骤8: 关闭收获弹窗，返回 (success, harvested)"""
    print("\n[步骤8] 关闭收获弹窗...")
    harvested = False
    
    for attempt in range(3):
        screenshot(SCREENSHOT_PATH)
        
        if has_template("harvest_continue.png", SCREENSHOT_PATH, 0.8):
            print("  ✅ 找到收获弹窗")
            harvested = True
            
            # OCR识别收获信息
            harvest_info = read_harvest_info(SCREENSHOT_PATH)
            if harvest_info:
                exp = harvest_info["exp"]
                crops = harvest_info["crops"]
                detail = []
                if exp > 0:
                    detail.append(f"经验+{exp}")
                for cname, ccount in crops.items():
                    detail.append(f"{cname}×{ccount}")
                print(f"  🎉 收获: {' '.join(detail)}")
                stats.add_harvest(exp=exp, crops=crops)
            else:
                stats.add_harvest()
            
            print("  ⏳ 等待3秒...")
            time.sleep(3)
            
            click_template("harvest_continue.png", SCREENSHOT_PATH, 0.8, "继续")
            print("  ⏳ 等待5秒...")
            time.sleep(5)
            return True, harvested
        else:
            print(f"  ⚠️ 未找到收获弹窗，等待3秒后重试 ({attempt+1}/3)")
            time.sleep(3)
    
    print("  ⚠️ 连续3次未找到收获弹窗，进入步骤9")
    return False, False

# ============================================================
# 步骤9: 移动到土地
# ============================================================
def step9_move_to_farmland(first_water_time, save_if_fresh=False):
    """步骤9: 移动到土地，读取成熟时间，计算浇水计划
    
    :param first_water_time: 一键务农时间（由main传入，在移动前记录）
    :param save_if_fresh: 如果是新种植（刚收获），保存计算的周期
    """
    print("\n[步骤9] 移动到土地...")
    
    # 向上方移动
    move_joystick(90, 200, 1200)  # 90度是正上方
    print("  ⏳ 等待移动...")
    time.sleep(5)
    
    # 截图OCR
    screenshot(SCREENSHOT_PATH)
    maturity_time, is_mature = read_maturity_time(SCREENSHOT_PATH)
    
    # 如果作物已成熟（可收获），不需要等待
    if is_mature:
        print("  🌾 作物已成熟，无需等待")
        return None, None, None
    
    # 计算浇水计划
    result = None
    maturity_dt = None
    
    if maturity_time:
        hour, minute = maturity_time
        # OCR读取的是成熟时间（绝对时间）
        maturity_dt = first_water_time.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if maturity_dt <= first_water_time:
            maturity_dt += timedelta(days=1)
        
        # 调用新的浇水计算函数
        result = calculate_plant_cycle_and_water_time(first_water_time, maturity_dt, save_if_fresh=save_if_fresh)
    
    return maturity_time, result, maturity_dt

# ============================================================
# 步骤10: 计算等待时间
# ============================================================
def step10_calculate_wait(maturity_time, result, maturity_dt):
    """步骤10: 根据成熟时间和浇水时间计算等待时间
    
    :param maturity_time: OCR识别的 (hour, minute) 元组
    :param result: 浇水计算结果 dict
    :param maturity_dt: 成熟时间 (datetime)
    :return: wake_time (下次唤醒时间)
    """
    print("\n[步骤10] 计算等待时间...")
    
    # 如果所有参数都为None，说明作物已成熟可收获，直接重启收割
    if maturity_time is None and result is None and maturity_dt is None:
        print("  🌾 作物已成熟，退出游戏重新进入收割...")
        adb_shell(f"am force-stop {GAME_PKG}")
        _reapply_low_brightness()
        time.sleep(3)
        return None
    
    # 先杀掉游戏
    print("  🛑 退出王者荣耀...")
    adb_shell(f"am force-stop {GAME_PKG}")
    _reapply_low_brightness()
    
    now = datetime.now()
    
    # 确定唤醒时间
    wake_time = None
    reason = ""
    
    if result and maturity_dt:
        # 获取下次浇水时间（water2）
        next_watering = result.get("water2")
        
        if next_watering and next_watering < maturity_dt:
            wake_time = next_watering
            reason = "浇水"
            print(f"  💧 下次浇水时间: {next_watering.strftime('%H:%M:%S')} (早于成熟时间)")
        else:
            wake_time = maturity_dt
            reason = "成熟"
            print(f"  🌾 成熟时间: {maturity_dt.strftime('%H:%M:%S')} (早于浇水时间)")
    elif maturity_dt:
        wake_time = maturity_dt
        reason = "成熟"
        print(f"  🌾 成熟时间: {maturity_dt.strftime('%H:%M:%S')}")
    else:
        print("  ⚠️ 无法识别时间，5分钟后重试...")
        return now + timedelta(minutes=5)
    
    # 提前2分钟唤醒
    wake_time -= timedelta(minutes=2)
    
    if wake_time <= now:
        print(f"  ⚠️ {reason}时间已到，立即重新启动")
        return now
    
    wait_seconds = int((wake_time - now).total_seconds())
    hours = wait_seconds // 3600
    minutes = (wait_seconds % 3600) // 60
    seconds = wait_seconds % 60
    
    print(f"  🎯 目标时间: {(wake_time + timedelta(minutes=2)).strftime('%H:%M:%S')} ({reason})")
    print(f"  🔔 提前2分钟唤醒: {wake_time.strftime('%H:%M:%S')}")
    print(f"  ⏳ 等待 {hours}小时{minutes}分{seconds:02d}秒")
    
    return wake_time
# ============================================================
# 主流程
# ============================================================
def check_adb_connection():
    """检查ADB连接状态，未连接则自动连接"""
    print("🔍 检查ADB连接...")
    result = adb_shell("get-state")
    if "device" in result:
        print(f"  ✅ 设备已连接: {DEVICE}")
        return True
    
    # 自动连接
    print(f"  ⚠️ 设备未连接，正在自动连接: {DEVICE}")
    connect_result = subprocess.run(
        f"{ADB} connect {DEVICE}", 
        shell=True, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=10
    )
    
    if "connected" in connect_result.stdout:
        print(f"  ✅ 自动连接成功: {DEVICE}")
        return True
    else:
        print(f"  ❌ 自动连接失败: {connect_result.stdout.strip()}")
        return False

def main():
    """主流程"""
    global JOYSTICK_CENTER, _step6_cfg
    print("=" * 60)
    print("王者荣耀农场自动化务农 v3")
    print("=" * 60)
    # 显示支持的分辨率及参数
    for (w, h), cfg in STEP6_CONFIG.items():
        print(f"  📐 {w}x{h}: 中心{cfg['center']} {cfg['angle']}° {cfg['distance']}px {cfg['duration']}ms")
    
    # 启动前检查ADB连接
    if not check_adb_connection():
        return
    
    # 询问是否降低亮度
    global _brightness_mode
    _brightness_mode = prompt_brightness_control()
    
    # 检测设备分辨率
    dev_w, dev_h = detect_resolution()
    print(f"  📐 分辨率 {dev_w}x{dev_h}")
    
    # 加载分辨率专属步骤6配置
    res_key = (dev_w, dev_h)
    if res_key in STEP6_CONFIG:
        _step6_cfg = STEP6_CONFIG[res_key].copy()
        JOYSTICK_CENTER = _step6_cfg["center"]
        print(f"  🎯 步骤6使用专属配置: 中心{JOYSTICK_CENTER} 角度{_step6_cfg['angle']}° 距离{_step6_cfg['distance']}px 时间{_step6_cfg['duration']}ms")
    else:
        if dev_w != BASE_W or dev_h != BASE_H:
            sx = dev_w / BASE_W
            sy = dev_h / BASE_H
            JOYSTICK_CENTER = (int(160 * sx), int(486 * sy))
        _step6_cfg = {"center": JOYSTICK_CENTER, "angle": 120, "distance": 200, "duration": 1500}
        print(f"  🎯 步骤6使用默认配置: 中心{JOYSTICK_CENTER} 角度120° 距离200px 时间1500ms")
    
    round_num = 0
    while True:
        round_num += 1
        stats.rounds = round_num
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
        _, harvested = step8_close_harvest()
        
        # 记录一键务农时间（在步骤9移动前）
        first_water_time = datetime.now()
        print(f"  🕐 一键务农时间: {first_water_time.strftime('%H:%M:%S')}")
        
        # 步骤9: 移动到土地，读取成熟时间，计算浇水计划
        maturity_time, result, maturity_dt = step9_move_to_farmland(first_water_time, save_if_fresh=harvested)
        
        # 步骤10: 计算等待时间，返回唤醒时间
        wake_time = step10_calculate_wait(maturity_time, result, maturity_dt)
        
        if wake_time is None:
            # 作物已成熟，立即重新开始
            continue
        
        # 等待到唤醒时间
        now = datetime.now()
        if wake_time > now:
            wait_seconds = int((wake_time - now).total_seconds())
            print(f"\n⏳ 等待到 {wake_time.strftime('%H:%M:%S')} 唤醒...")
            time.sleep(wait_seconds)
        
        # 唤醒屏幕并解锁
        wake_and_unlock(UNLOCK_PWD)

def run_main():
    """运行主流程，确保退出时恢复亮度"""
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️ 用户中断 (Ctrl+C)")
    except Exception as e:
        print(f"\n\n❌ 发生错误: {e}")
    finally:
        # 恢复亮度设置
        restore_brightness()
        print("\n👋 脚本已退出")

if __name__ == "__main__":
    run_main()
