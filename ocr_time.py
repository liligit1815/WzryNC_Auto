#!/usr/bin/env python3
"""
WZRY Farm - 成熟时间OCR模块
使用 rapidocr-onnxruntime 识别作物成熟时间
"""

import re
import subprocess
from datetime import datetime, timedelta

# 全局OCR实例（避免重复加载模型）
_ocr_instance = None

def get_ocr():
    """获取OCR实例（单例）"""
    global _ocr_instance
    if _ocr_instance is None:
        from rapidocr_onnxruntime import RapidOCR
        _ocr_instance = RapidOCR()
    return _ocr_instance

def take_screenshot(device="192.168.31.165:5557", save_path="/tmp/wzry_screenshot.png"):
    """通过ADB截图"""
    adb = "/tmp/platform-tools/adb"
    subprocess.run([adb, "-s", device, "shell", "screencap", "-p", "/sdcard/tmp.png"], 
                   capture_output=True)
    subprocess.run([adb, "-s", device, "pull", "/sdcard/tmp.png", save_path], 
                   capture_output=True)
    return save_path

def read_maturity_time(screenshot_path):
    """
    从截图中读取成熟时间
    返回: (minutes, seconds) 或 None
    """
    import cv2
    
    img = cv2.imread(screenshot_path)
    if img is None:
        return None
    
    # 裁剪成熟时间区域（左侧面板）
    # 这个区域需要根据实际界面调整
    roi = img[250:400, 0:300]
    
    # OCR识别
    ocr = get_ocr()
    result, _ = ocr(roi)
    
    if result:
        for line in result:
            text = line[1]
            # 解析时间格式 "14:41成熟" 或 "14:41"
            time_match = re.search(r'(\d{1,2}):(\d{2})', text)
            if time_match:
                minutes = int(time_match.group(1))
                seconds = int(time_match.group(2))
                return minutes, seconds
    
    return None

def parse_time_string(text):
    """
    解析时间字符串，格式如 "14:41成熟"
    返回: (minutes, seconds) 或 None
    """
    match = re.search(r'(\d{1,2}):(\d{2})', text)
    if match:
        return int(match.group(1)), int(match.group(2))
    return None

def calculate_next_farm_time(maturity_minutes, maturity_seconds, advance_minutes=1):
    """
    计算下次务农时间（成熟前N分钟）
    返回字典包含各种时间信息
    """
    now = datetime.now()
    maturity_total = maturity_minutes * 60 + maturity_seconds
    maturity_time = now + timedelta(seconds=maturity_total)
    next_farm = maturity_time - timedelta(minutes=advance_minutes)
    wait_seconds = max(0, (next_farm - now).total_seconds())
    
    return {
        "current": now.strftime("%H:%M:%S"),
        "maturity_total_seconds": maturity_total,
        "maturity": maturity_time.strftime("%H:%M:%S"),
        "next_farm": next_farm.strftime("%H:%M:%S"),
        "wait_seconds": wait_seconds,
        "wait_minutes": wait_seconds / 60
    }

def get_maturity_info(screenshot_path=None, device="192.168.31.165:5557"):
    """
    完整流程：截图 → OCR → 解析 → 计算
    返回成熟时间信息字典
    """
    # 截图
    if screenshot_path is None:
        screenshot_path = take_screenshot(device)
    
    # OCR识别
    time_tuple = read_maturity_time(screenshot_path)
    
    if time_tuple is None:
        return {"error": "无法识别成熟时间"}
    
    minutes, seconds = time_tuple
    
    # 计算下次务农时间
    info = calculate_next_farm_time(minutes, seconds)
    info["maturity_text"] = f"{minutes}:{seconds:02d}"
    
    return info

if __name__ == "__main__":
    # 测试
    print("WZRY Farm OCR Module (rapidocr-onnxruntime)")
    print("=" * 50)
    
    info = get_maturity_info("/tmp/cur.png")
    
    if "error" in info:
        print(f"错误: {info['error']}")
    else:
        print(f"成熟时间: {info['maturity_text']}")
        print(f"当前时间: {info['current']}")
        print(f"成熟时间: {info['maturity']}")
        print(f"启动时间: {info['next_farm']} (提前1分钟)")
        print(f"等待时间: {info['wait_minutes']:.1f} 分钟")
