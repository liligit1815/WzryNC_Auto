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
import shlex
import sys
import shutil
import tempfile
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
_ADB = os.environ.get("WZRY_ADB") or _shutil.which("adb") or (
    "/home/lili/android-tools/platform-tools/adb"
    if Path("/home/lili/android-tools/platform-tools/adb").exists()
    else "/tmp/platform-tools/adb" if Path("/tmp/platform-tools/adb").exists() else "adb"
)
ADB = _ADB
DEVICE = os.environ.get("WZRY_DEVICE", "")
DEFAULT_DEVICE = os.environ.get("WZRY_DEFAULT_DEVICE", "192.168.31.204:38041")
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
    (2560, 1600): {"center": (385, 1165), "angle": 120, "distance": 370, "duration": 1500},
}

# 默认步骤6配置
_step6_cfg = {"center": (160, 486), "angle": 120, "distance": 200, "duration": 1500}

# 模板搜索区域使用归一化坐标 (x1, y1, x2, y2)，减少动态背景误匹配。
TEMPLATE_ROIS = {
    "start_game.png": (0.25, 0.55, 0.75, 1.00),
    "close_popup.png": (0.78, 0.04, 1.0, 0.28),
    "close_popup_event.png": (0.78, 0.04, 1.0, 0.28),
    "lainongchang.png": (0.00, 0.55, 0.55, 1.00),
    "refresh_pos.png": (0.75, 0.70, 1.00, 1.00),
    "oneclick_farm.png": (0.45, 0.35, 0.80, 0.80),
    "harvest_continue.png": (0.30, 0.70, 0.70, 1.00),
}

TEMPLATE_THRESHOLDS = {
    "start_game.png": 0.75,
    "close_popup.png": 0.90,
    "close_popup_event.png": 0.78,
    "lainongchang.png": 0.75,
    "refresh_pos.png": 0.60,
    "oneclick_farm.png": 0.75,
    "harvest_continue.png": 0.85,
}

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
FARM_STATE_FILE = ASSETS_DIR / "farm_state.json"

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

def load_farm_state():
    """读取当前种植批次；损坏或不完整的数据按无状态处理。"""
    try:
        with FARM_STATE_FILE.open("r", encoding="utf-8") as file:
            data = json.load(file)
        data["batch_started_at"] = datetime.fromisoformat(data["batch_started_at"])
        return data
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        return None

def save_farm_state(batch_started_at, cycle_min, maturity_at=None):
    data = {
        "batch_started_at": batch_started_at.isoformat(timespec="seconds"),
        "cycle_min": int(cycle_min),
        "observed_maturity_at": (
            maturity_at.isoformat(timespec="seconds") if maturity_at else None
        ),
        "updated_at": datetime.now().isoformat(timespec="seconds"),
    }
    temp_path = FARM_STATE_FILE.with_suffix(".tmp")
    with temp_path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
    temp_path.replace(FARM_STATE_FILE)

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
    
    # 新种植时记录批次起点；后续轮次必须沿用它，不能把每次点击都
    # 重新解释成第一次浇水。
    state = load_farm_state()
    if save_if_fresh or not state or state.get("cycle_min") != cycle_min:
        batch_started_at = first_water_time
        save_farm_state(batch_started_at, cycle_min, show_mature_time)
        print(f"  🌱 记录种植批次起点：{batch_started_at.strftime(time_format)}")
    else:
        batch_started_at = state["batch_started_at"]
        print(f"  📌 沿用种植批次起点：{batch_started_at.strftime(time_format)}")

    # 计算完美浇水时间点（从批次第一次浇水开始算）
    water2_rel = cycle_min / 3
    water3_rel = cycle_min * 2 / 3
    water4_rel = cycle_min * 11 / 15  # 第四次 = 完美成熟
    
    # 转换为真实时间
    water2_time = batch_started_at + timedelta(minutes=water2_rel)
    water3_time = batch_started_at + timedelta(minutes=water3_rel)
    water4_time = batch_started_at + timedelta(minutes=water4_rel)
    now = datetime.now()
    next_watering = next(
        (
            candidate
            for candidate in (water2_time, water3_time, water4_time)
            if candidate > now and candidate < show_mature_time
        ),
        None,
    )
    
    print(f"  🌱 作物原始周期：{cycle_hour} 小时（{cycle_min} 分钟）")
    print(f"  💧 第二次完美浇水时间：{water2_time.strftime(time_format)}")
    print(f"  💧 第三次完美浇水时间：{water3_time.strftime(time_format)}")
    print(f"  🌾 第四次浇水（成熟收获）：{water4_time.strftime(time_format)}")
    
    return {
        "plant_cycle_min": cycle_min,
        "water2": water2_time,
        "water3": water3_time,
        "mature_time": water4_time,
        "next_watering": next_watering,
    }

# ============================================================
# ADB 基础操作
# ============================================================
def _run_adb(args, timeout=10, retries=1):
    """执行ADB并校验结果；瞬时断线时进行一次有限重试。"""
    last_error = ""
    for attempt in range(retries + 1):
        try:
            result = subprocess.run(
                [ADB, "-s", DEVICE, *args],
                capture_output=True, text=True, encoding="utf-8",
                errors="replace", timeout=timeout,
            )
        except (subprocess.TimeoutExpired, OSError) as exc:
            last_error = str(exc)
        else:
            combined = f"{result.stdout}\n{result.stderr}".lower()
            disconnected = any(
                marker in combined
                for marker in (
                    "device offline", "device not found", "no devices",
                    "closed", "cannot connect",
                )
            )
            if result.returncode == 0 and not disconnected:
                return result
            last_error = (result.stderr or result.stdout).strip()
        if attempt < retries:
            print(f"  ⚠️ ADB操作失败，正在重试: {last_error}")
            if DEVICE and ":" in DEVICE:
                try:
                    subprocess.run(
                        [ADB, "connect", DEVICE],
                        capture_output=True, text=True, encoding="utf-8",
                        errors="replace", timeout=10,
                    )
                except (subprocess.TimeoutExpired, OSError):
                    pass
            time.sleep(1)
    raise RuntimeError(f"ADB操作失败: {last_error or '未知错误'}")

def adb_shell(cmd, timeout=10, retries=1):
    """执行设备端 shell 命令，不经过主机 shell。"""
    return _run_adb(
        ["shell", cmd], timeout=timeout, retries=retries
    ).stdout

def adb_shell_root(cmd):
    """通过 su 执行设备端命令，重定向只在设备端解析。"""
    # adb shell 的多个参数会在设备端重新拼接；若直接传
    # ["su", "-c", "echo 1 > ..."]，部分 adb/su 组合会丢失命令边界，
    # 导致重定向由普通 shell 执行。把整条远端命令作为一个参数，并
    # 显式引用 su 的命令字符串，确保 ">" 也运行在 root 上下文中。
    remote_cmd = f"su -c {shlex.quote(cmd)}"
    return _run_adb(["shell", remote_cmd], timeout=15).stdout

def adb_command(*args, timeout=10):
    """执行 ADB 主机命令并返回 CompletedProcess。"""
    return _run_adb(list(map(str, args)), timeout=timeout)

def force_stop_game():
    """退出游戏；所有成功、失败和异常出口统一调用。"""
    if not DEVICE:
        return
    try:
        adb_shell(f"am force-stop {GAME_PKG}")
        _reapply_low_brightness()
    except Exception as exc:
        print(f"  ⚠️ 退出游戏失败: {exc}")

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
        if _brightness_mode == 'root_zero':
            adb_shell_root("echo 0 > /sys/class/backlight/panel0-backlight/brightness")
        elif _brightness_mode == 'root_one':
            adb_shell_root("echo 1 > /sys/class/backlight/panel0-backlight/brightness")
        else:
            adb_shell("settings put system screen_brightness_mode 0")
            adb_shell("settings put system screen_brightness 1")
    
    # 唤醒屏幕
    adb_shell("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    
    # 按设备物理尺寸计算解锁手势，兼容手机和模拟器。
    import re
    size_out = adb_shell("wm size")
    match = re.search(r"(\d+)x(\d+)", size_out)
    if match:
        width, height = map(int, match.groups())
        portrait_w, portrait_h = min(width, height), max(width, height)
    else:
        portrait_w, portrait_h = 1080, 2400
    unlock_x = portrait_w // 2
    adb_shell(
        f"input swipe {unlock_x} {int(portrait_h * 0.75)} "
        f"{unlock_x} {int(portrait_h * 0.25)} 300"
    )
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
_brightness_mode = None  # 'low' / 'root_zero' / 'root_one'

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
    if _brightness_mode == 'root_zero':
        adb_shell_root("echo 0 > /sys/class/backlight/panel0-backlight/brightness")
    elif _brightness_mode == 'root_one':
        adb_shell_root("echo 1 > /sys/class/backlight/panel0-backlight/brightness")
    else:
        adb_shell("settings put system screen_brightness 1")

def set_brightness_zero_root():
    """使用ROOT权限将亮度设为0"""
    print("  🔅 使用ROOT权限设置亮度为0")
    # 关闭自动亮度
    adb_shell("settings put system screen_brightness_mode 0")
    # 使用ROOT权限直接写入亮度节点
    result = adb_shell_root("echo 0 > /sys/class/backlight/panel0-backlight/brightness")
    if result and ("Permission denied" in result or "error" in result.lower()):
        print(f"    ⚠️ 写入失败: {result}")
    else:
        # 验证是否写入成功
        verify = adb_shell_root("cat /sys/class/backlight/panel0-backlight/brightness")
        if verify.strip() == "0":
            print("    ✅ 已使用ROOT权限将亮度设为0")
        else:
            print(f"    ⚠️ 验证失败，当前亮度: {verify.strip()}")

def set_brightness_one_root():
    """使用ROOT权限将亮度设为1"""
    print("  🔅 使用ROOT权限设置亮度为1")
    # 关闭自动亮度
    adb_shell("settings put system screen_brightness_mode 0")
    # 使用ROOT权限直接写入亮度节点
    result = adb_shell_root("echo 1 > /sys/class/backlight/panel0-backlight/brightness")
    if result and ("Permission denied" in result or "error" in result.lower()):
        print(f"    ⚠️ 写入失败: {result}")
    else:
        # 验证是否写入成功
        verify = adb_shell_root("cat /sys/class/backlight/panel0-backlight/brightness")
        if verify.strip() == "1":
            print("    ✅ 已使用ROOT权限将亮度设为1")
        else:
            print(f"    ⚠️ 验证失败，当前亮度: {verify.strip()}")

def restore_brightness():
    """尽力恢复原始亮度；退出清理失败不得覆盖主流程异常。"""
    global _original_brightness, _original_auto_brightness, _brightness_mode
    
    if _original_brightness is None:
        return
    
    print("  🔆 恢复亮度设置...")

    errors = []
    try:
        # 设备的背光节点名称并不统一。只写实际存在的节点，避免固定
        # 尝试 lcd-backlight 在部分设备上产生 No such file 异常。
        if _brightness_mode in ('root_zero', 'root_one'):
            value = int(_original_brightness)
            try:
                adb_shell_root(
                    "for node in /sys/class/backlight/*/brightness; do "
                    f"[ -e \"$node\" ] && echo {value} > \"$node\"; "
                    "done"
                )
            except Exception as exc:
                errors.append(f"背光节点恢复失败: {exc}")

        try:
            adb_shell(
                f"settings put system screen_brightness "
                f"{int(_original_brightness)}"
            )
        except Exception as exc:
            errors.append(f"系统亮度恢复失败: {exc}")

        try:
            adb_shell(
                f"settings put system screen_brightness_mode "
                f"{int(_original_auto_brightness)}"
            )
        except Exception as exc:
            errors.append(f"自动亮度模式恢复失败: {exc}")

        if errors:
            for error in errors:
                print(f"  ⚠️ {error}")
            print("  ⚠️ 亮度未能完全恢复，请检查设备当前亮度")
        else:
            print(
                f"  ✅ 已恢复亮度: {_original_brightness}/255, "
                f"自动亮度: "
                f"{'开启' if _original_auto_brightness else '关闭'}"
            )
    finally:
        _original_brightness = None
        _original_auto_brightness = None
        _brightness_mode = None

def prompt_brightness_control():
    """询问用户是否降低亮度"""
    print("\n" + "=" * 60)
    print("💡 是否降低屏幕亮度以减少烧屏风险？")
    print("=" * 60)
    print("  Y - 普通模式，亮度降至最低(1)")
    print("  R - ROOT权限，亮度设为0（屏幕全黑）")
    print("  1 - ROOT权限，亮度设为1（极低亮度）")
    print("  N - 保持当前亮度设置")
    print("=" * 60)
    
    while True:
        choice = input("请选择 (Y/R/1/N): ").strip().upper()
        if choice in ['Y', 'YES']:
            get_brightness_settings()
            set_brightness_low()
            return 'low'
        elif choice in ['R', 'ROOT']:
            get_brightness_settings()
            try:
                set_brightness_zero_root()
                return 'root_zero'
            except RuntimeError as exc:
                print(f"  ⚠️ ROOT亮度设置失败: {exc}")
                print("  ↪️ 自动改用普通最低亮度，自动化任务将继续")
                set_brightness_low()
                return 'low'
        elif choice == '1':
            get_brightness_settings()
            try:
                set_brightness_one_root()
                return 'root_one'
            except RuntimeError as exc:
                print(f"  ⚠️ ROOT亮度设置失败: {exc}")
                print("  ↪️ 自动改用普通最低亮度，自动化任务将继续")
                set_brightness_low()
                return 'low'
        elif choice in ['N', 'NO']:
            print("  ℹ️ 保持当前亮度设置")
            return None
        else:
            print("  ⚠️ 请输入 Y、R、1 或 N")

def screenshot(path=SCREENSHOT_PATH):
    """可靠截图：校验新文件后原子替换，绝不复用旧截图。"""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    # 游戏渲染繁忙或无线 ADB 抖动时，设备端 screencap 偶尔会超过
    # 通用命令的 10 秒限制。截图使用独立超时并允许额外一次重试。
    adb_shell(
        "screencap -p /sdcard/screen.png", timeout=30, retries=2
    )
    fd, temp_name = tempfile.mkstemp(
        prefix=".wzry_screen_", suffix=".png", dir=str(target.parent)
    )
    os.close(fd)
    temp_path = Path(temp_name)
    try:
        result = adb_command(
            "pull", "/sdcard/screen.png", str(temp_path), timeout=20
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"截图拉取失败: {(result.stderr or result.stdout).strip()}"
            )
        img = cv2.imread(str(temp_path))
        if img is None or img.size == 0:
            raise RuntimeError("截图文件无法解码")
        h, w = img.shape[:2]
        if min(h, w) < 480:
            raise RuntimeError(f"截图分辨率异常: {w}x{h}")
        if h > w:
            img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
            if not cv2.imwrite(str(temp_path), img):
                raise RuntimeError("旋转截图写入失败")
        temp_path.replace(target)
    finally:
        if temp_path.exists():
            temp_path.unlink()
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
    return True

def swipe(x1, y1, x2, y2, duration_ms=1000):
    """滑动"""
    adb_shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")
    return True

# ============================================================
# 模板匹配
# ============================================================
def _template_scales(tdir, img_w, img_h):
    """返回有限且可解释的模板尺度，避免把按钮放大到整屏宽。"""
    if tdir != TEMPLATE_DIR:
        return [0.90, 0.95, 1.0, 1.05, 1.10]

    predicted = min(img_w / BASE_W, img_h / BASE_H)
    scales = {0.75, 1.0, 1.25, 1.5, 2.0}
    scales.update(
        round(predicted * factor, 3)
        for factor in (0.85, 0.925, 1.0, 1.075, 1.15)
    )
    return sorted(scales)

def find_template(template_name, screenshot_path, threshold=None, roi=None):
    """在限定区域内多尺度查找模板（分辨率专用模板优先）。"""
    img = cv2.imread(screenshot_path)
    if img is None:
        return None
    
    img_h, img_w = img.shape[:2]
    threshold = TEMPLATE_THRESHOLDS.get(template_name, 0.6) if threshold is None else threshold
    roi = TEMPLATE_ROIS.get(template_name) if roi is None else roi
    if roi:
        x1, y1 = int(roi[0] * img_w), int(roi[1] * img_h)
        x2, y2 = int(roi[2] * img_w), int(roi[3] * img_h)
    else:
        x1, y1, x2, y2 = 0, 0, img_w, img_h
    search_img = img[y1:y2, x1:x2]
    
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
        scales = _template_scales(tdir, img_w, img_h)
        
        for s in scales:
            if abs(s - 1.0) < 0.01:
                t = tmpl
                tw, th = tmpl_w, tmpl_h
            else:
                nw, nh = int(tmpl_w * s), int(tmpl_h * s)
                if nw > search_img.shape[1] or nh > search_img.shape[0] or nw < 5 or nh < 5:
                    continue
                t = cv2.resize(tmpl, (nw, nh))
                tw, th = nw, nh
            
            if tw > search_img.shape[1] or th > search_img.shape[0]:
                continue
            result = cv2.matchTemplate(search_img, t, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(result)
            if max_val > best_score:
                best_score = max_val
                best_loc = (max_loc[0] + x1, max_loc[1] + y1)
                best_tw, best_th = tw, th
    
    if best_loc is None:
        print(f"  ❌ '{template_name}': 模板不存在")
        return None
    
    if best_score >= threshold:
        cx = best_loc[0] + best_tw // 2
        cy = best_loc[1] + best_th // 2
        print(f"  ✅ '{template_name}': score={best_score:.3f} @ ({cx},{cy})")
        return {
            "x": cx, "y": cy, "score": best_score,
            "template": template_name, "scale_size": (best_tw, best_th),
        }
    else:
        print(f"  ❌ '{template_name}': 未匹配 ({best_score:.3f} < {threshold})")
        return None

def has_template(template_name, screenshot_path, threshold=None):
    """检查模板是否存在"""
    return find_template(template_name, screenshot_path, threshold) is not None

def click_template(template_name, screenshot_path, threshold=None, label=""):
    """匹配并点击模板"""
    result = find_template(template_name, screenshot_path, threshold)
    if result:
        tap(result["x"], result["y"], label or template_name)
        return True
    return False

def find_any_template(template_names, screenshot_path, thresholds=None):
    """匹配同一功能的多个模板，返回超过各自阈值的最高分候选。"""
    best = None
    thresholds = thresholds or {}
    for name in template_names:
        result = find_template(name, screenshot_path, thresholds.get(name))
        if result and (best is None or result["score"] > best["score"]):
            best = result
    return best

def wait_for_any_template(template_names, timeout=60, interval=3, label="页面"):
    """轮询等待任一目标出现；成功后返回匹配结果。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        screenshot(SCREENSHOT_PATH)
        result = find_any_template(template_names, SCREENSHOT_PATH)
        if result:
            print(f"  ✅ {label}已就绪: {result['template']}")
            return result
        remaining = max(0, int(deadline - time.monotonic()))
        print(f"  ⏳ 等待{label}，剩余 {remaining}秒")
        time.sleep(min(interval, max(0, deadline - time.monotonic())))
    return None

def save_diagnostic(step, details=None):
    """保存失败现场，供游戏更新后离线复现。"""
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir = SCRIPT_DIR / "diagnostics" / f"{stamp}_{step}"
    output_dir.mkdir(parents=True, exist_ok=True)
    if Path(SCREENSHOT_PATH).exists():
        shutil.copy2(SCREENSHOT_PATH, output_dir / "screenshot.png")
    context = {
        "step": step,
        "time": datetime.now().isoformat(timespec="seconds"),
        "device": DEVICE,
        "details": details or {},
    }
    with open(output_dir / "context.json", "w", encoding="utf-8") as file:
        json.dump(context, file, ensure_ascii=False, indent=2)
    print(f"  📁 已保存失败现场: {output_dir}")
    return output_dir

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
    return has_template("refresh_pos.png", SCREENSHOT_PATH)

def reset_position():
    """刷新站位重置角色位置"""
    print("  🔄 刷新站位...")
    if click_template("refresh_pos.png", SCREENSHOT_PATH, label="刷新站位"):
        print("  ⏳ 等待5秒...")
        time.sleep(5)
        screenshot(SCREENSHOT_PATH)
        return has_template("refresh_pos.png", SCREENSHOT_PATH)
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
    # 覆盖多行奖励卡片；旧范围会截掉第二行的作物名称。
    roi = img[int(h*0.15):int(h*0.92), int(w*0.15):int(w*0.85)]
    
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
        x = sum(point[0] for point in line[0]) / 4
        y = sum(point[1] for point in line[0]) / 4
        items.append({"text": text, "x": x, "y": y})
    
    # 按y坐标排序，找数字行和作物名行的配对
    numbers = []  # [{"x", "y", "value", "used"}]
    crops_found = []  # [{"x", "y", "name"}]
    
    for item in items:
        text = item["text"].strip()
        if text in crop_names:
            crops_found.append({"x": item["x"], "y": item["y"], "name": text})
        elif text.isdigit() and int(text) > 0:
            numbers.append({
                "x": item["x"], "y": item["y"],
                "value": int(text), "used": False,
            })

    # 每张奖励卡片的数量位于作物名上方。二维配对且每个数字只能使用一次。
    for crop in sorted(crops_found, key=lambda item: item["y"]):
        candidates = []
        for number in numbers:
            dy = crop["y"] - number["y"]
            dx = abs(crop["x"] - number["x"])
            if not number["used"] and 20 <= dy <= 180 and dx <= 140:
                candidates.append((dx + dy * 0.15, number))
        if not candidates:
            continue
        _, best = min(candidates, key=lambda pair: pair[0])
        best["used"] = True
        name = crop["name"]
        harvest["crops"][name] = harvest["crops"].get(name, 0) + best["value"]
    
    if harvest["exp"] > 0 or harvest["crops"]:
        return harvest
    return None

# ============================================================
# 步骤1: 检测状态
# ============================================================
def step1_check_status():
    """步骤1: 检测APP是否在前台"""
    print("\n[步骤1] 检测状态...")
    
    found = False
    found2 = False
    found3 = False
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
        # dumpsys 有正常输出且三种方式均未命中，说明游戏确实不在前台。
        if out.strip() or out2.strip() or out3.strip():
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
    print("  ⏳ 等待登录页或启动弹窗...")
    return wait_for_any_template(
        ["start_game.png", "close_popup.png", "close_popup_event.png"],
        timeout=60, interval=3, label="游戏启动页",
    ) is not None


# ============================================================
# 步骤2b: 关闭启动弹窗
# ============================================================
def step2b_close_startup_popups():
    """步骤2b: 启动后关闭弹窗（逻辑与步骤4一致）"""
    print("\n[步骤2b] 关闭启动弹窗...")

    miss_count = 0
    for i in range(10):  # 最多处理10个弹窗
        screenshot(SCREENSHOT_PATH)
        result = find_any_template(
            ["close_popup.png", "close_popup_event.png"], SCREENSHOT_PATH
        )

        if result:
            x, y = result["x"], result["y"]
            tap(x, y, f"关闭启动弹窗/{result['template']}")
            miss_count = 0
            print("  ⏳ 等待5秒...")
            time.sleep(5)
        else:
            if find_template("start_game.png", SCREENSHOT_PATH):
                print("  ✅ 已到登录页，无需继续处理启动弹窗")
                break
            miss_count += 1
            print(f"  ⚠️ 未找到弹窗，未匹配 {miss_count}/3")

        # 连续3次未匹配，认为弹窗全部关闭
        if miss_count >= 3:
            print("  ✅ 启动弹窗处理完毕（连续3次未匹配）")
            break

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
        
        if click_template("start_game.png", SCREENSHOT_PATH, label="开始游戏"):
            print("  ⏳ 等待大厅...")
            if wait_for_any_template(
                ["close_popup.png", "close_popup_event.png", "lainongchang.png"],
                timeout=90, interval=3, label="大厅",
            ):
                return True
            save_diagnostic("step3_lobby_timeout")
            return False
        
        if attempt < 4:
            print("  ⏳ 等待5秒...")
            time.sleep(5)
    
    print("  ❌ 连续5次失败，返回步骤1")
    save_diagnostic("step3_start_game")
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
        result = find_any_template(
            ["close_popup.png", "close_popup_event.png"], SCREENSHOT_PATH
        )

        if result:
            x, y = result["x"], result["y"]
            tap(x, y, f"关闭弹窗/{result['template']}")
            miss_count = 0
            print("  ⏳ 等待5秒...")
            time.sleep(5)
        else:
            if find_template("lainongchang.png", SCREENSHOT_PATH):
                print("  ✅ 已进入大厅主页，弹窗处理完毕")
                break
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

        if click_template("lainongchang.png", SCREENSHOT_PATH, label="进入农场"):
            print("  ⏳ 等待农场加载...")
            if wait_for_any_template(
                ["refresh_pos.png"], timeout=60, interval=3, label="农场"
            ):
                return True
            save_diagnostic("step5_farm_timeout")
            return False

        print(f"  ⏳ 等待5秒... ({attempt+1}/10)")
        time.sleep(5)

    print("  ❌ 连续10次未找到进入农场按钮")
    save_diagnostic("step5_enter_farm")
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
        if not reset_position():
            print("  ❌ 刷新站位失败，停止本次移动")
            save_diagnostic("step6_reset_position")
            return False
    
    # 使用分辨率专属配置或默认参数
    cfg = _step6_cfg
    move_joystick(cfg["angle"], cfg["distance"], cfg["duration"], center=cfg["center"])
    print(f"  ⏳ 等待移动...")
    time.sleep(5)
    screenshot(SCREENSHOT_PATH)
    if has_template("oneclick_farm.png", SCREENSHOT_PATH):
        print("  ✅ 已到达雕像，一键务农按钮可用")
        return True
    print("  ❌ 移动后未找到一键务农按钮")
    save_diagnostic("step6_arrival")
    return False

# ============================================================
# 步骤7: 一键务农
# ============================================================
def step7_oneclick_farm():
    """步骤7: 一键务农，返回 (是否成功, 实际点击时间)。"""
    print("\n[步骤7] 一键务农...")
    
    screenshot(SCREENSHOT_PATH)
    
    if has_template("oneclick_farm.png", SCREENSHOT_PATH):
        print("  ✅ 找到一键务农按钮")
        print("  ⏳ 等待3秒...")
        time.sleep(3)

        # 动画或网络延迟期间按钮可能已经变化，必须使用新截图点击。
        screenshot(SCREENSHOT_PATH)
        if not click_template("oneclick_farm.png", SCREENSHOT_PATH, label="一键务农"):
            return False, None
        farm_time = datetime.now()
        print(f"  🕐 一键务农时间: {farm_time.strftime('%H:%M:%S')}")
        print("  ⏳ 等待5秒...")
        time.sleep(5)
        return True, farm_time
    else:
        print("  ❌ 未找到一键务农，返回步骤6")
        save_diagnostic("step7_oneclick")
        return False, None

# ============================================================
# 步骤8: 关闭收获弹窗
# ============================================================
def step8_close_harvest():
    """步骤8: 关闭收获弹窗，返回 (success, harvested)"""
    print("\n[步骤8] 关闭收获弹窗...")
    harvested = False
    
    for attempt in range(3):
        screenshot(SCREENSHOT_PATH)
        
        if has_template("harvest_continue.png", SCREENSHOT_PATH):
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

            # 点击必须基于最新画面，避免使用三秒前的旧坐标。
            screenshot(SCREENSHOT_PATH)
            click_template("harvest_continue.png", SCREENSHOT_PATH, label="继续")
            print("  ⏳ 等待5秒...")
            time.sleep(5)
            screenshot(SCREENSHOT_PATH)
            if has_template("harvest_continue.png", SCREENSHOT_PATH):
                print("  ❌ 收获弹窗点击后仍存在")
                save_diagnostic("step8_harvest_not_closed")
                return False, harvested
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
        # 从原始种植批次选择尚未执行的最近节点。
        next_watering = result.get("next_watering")
        
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
    if not DEVICE:
        print("  ❌ 未选择设备")
        return False
    result = adb_command("get-state")
    if result.returncode == 0 and result.stdout.strip() == "device":
        print(f"  ✅ 设备已连接: {DEVICE}")
        return True
    
    # 自动连接
    print(f"  ⚠️ 设备未连接，正在自动连接: {DEVICE}")
    connect_result = subprocess.run(
        [ADB, "connect", DEVICE],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=10,
    )
    
    if "connected" in connect_result.stdout:
        print(f"  ✅ 自动连接成功: {DEVICE}")
        return True
    else:
        print(f"  ❌ 自动连接失败: {connect_result.stdout.strip()}")
        return False

def resolve_device():
    """优先使用环境变量；否则单设备自动选择，多设备要求显式指定。"""
    global DEVICE
    if DEVICE:
        return True
    try:
        result = subprocess.run(
            [ADB, "devices"], capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=10,
        )
        devices = []
        for line in result.stdout.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2 and fields[1] == "device":
                devices.append(fields[0])
        if len(devices) == 1:
            DEVICE = devices[0]
            print(f"  📱 自动选择唯一设备: {DEVICE}")
            return True
        if len(devices) > 1:
            print(f"  ❌ 检测到多个设备: {', '.join(devices)}")
            print("  请设置 WZRY_DEVICE 指定目标设备")
            return False
    except Exception as exc:
        print(f"  ⚠️ 枚举ADB设备失败: {exc}")

    if DEFAULT_DEVICE:
        DEVICE = DEFAULT_DEVICE
        print(f"  📱 未发现在线设备，尝试默认无线设备: {DEVICE}")
        return True
    print("  ❌ 未发现在线设备，请先连接 ADB 或设置 WZRY_DEFAULT_DEVICE")
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
    
    # 启动前选择并检查ADB连接
    if not resolve_device() or not check_adb_connection():
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
            move_scale = min(sx, sy)
        else:
            move_scale = 1.0
        _step6_cfg = {
            "center": JOYSTICK_CENTER,
            "angle": 120,
            "distance": max(100, int(200 * move_scale)),
            "duration": 1500,
        }
        print(f"  🎯 步骤6使用缩放配置: {_step6_cfg}")
    
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
        if not step2_launch_game():
            print("\n⚠️ 游戏启动页超时，重新开始...")
            save_diagnostic("step2_launch")
            force_stop_game()
            time.sleep(30)
            continue

        # 步骤2b: 关闭启动弹窗
        step2b_close_startup_popups()

        # 步骤3: 点击开始游戏
        if not step3_click_start_game():
            print("\n⚠️ 步骤3失败，重新开始...")
            force_stop_game()
            continue
        
        # 步骤4: 关闭弹窗
        step4_close_popup()
        
        # 步骤5: 进入农场
        if not step5_enter_farm():
            print("\n⚠️ 步骤5失败，重新开始...")
            force_stop_game()
            continue
        
        # 步骤6: 移动到雕像
        if not step6_move_to_statue():
            print("\n⚠️ 步骤6失败，重新开始...")
            force_stop_game()
            time.sleep(30)
            continue
        
        # 步骤7: 一键务农
        farm_ok, first_water_time = step7_oneclick_farm()
        if not farm_ok:
            print("\n⚠️ 步骤7失败，返回步骤6...")
            if step6_move_to_statue():
                farm_ok, first_water_time = step7_oneclick_farm()
        if not farm_ok:
            print("\n❌ 步骤7连续失败，本轮结束")
            force_stop_game()
            time.sleep(30)
            continue
        
        # 步骤8: 关闭收获弹窗
        _, harvested = step8_close_harvest()
        
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
        force_stop_game()
        # 恢复亮度设置
        restore_brightness()
        print("\n👋 脚本已退出")

if __name__ == "__main__":
    run_main()
