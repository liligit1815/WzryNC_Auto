#!/usr/bin/env python3
"""王者荣耀自动务农 Python v4，业务流程对齐实测 APK 0.3.20。"""
import argparse
import cv2
import numpy as np
import subprocess
import time
import os
import sys
import shutil
import json
import re
from pathlib import Path
from datetime import datetime

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
DEFAULT_DEVICE = os.environ.get("WZRY_DEFAULT_DEVICE", "")
BASE_W, BASE_H = 1280, 720

# 模板搜索区域使用归一化坐标 (x1, y1, x2, y2)，减少动态背景误匹配。
TEMPLATE_ROIS = {
    "start_game.png": (0.25, 0.55, 0.75, 1.00),
    "close_popup.png": (0.78, 0.04, 0.95, 0.28),
    "close_popup_event.png": (0.78, 0.04, 0.95, 0.28),
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

def adb_shell(cmd):
    """执行设备端 shell 命令，不经过主机 shell。"""
    result = subprocess.run(
        [ADB, "-s", DEVICE, "shell", cmd],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=10,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ADB命令失败：{result.stderr.strip() or result.stdout.strip()}")
    return result.stdout

def adb_shell_root(cmd):
    """通过 su 执行设备端命令，重定向只在设备端解析。"""
    result = subprocess.run(
        [ADB, "-s", DEVICE, "shell", "su", "-c", cmd],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=15,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ADB命令失败：{result.stderr.strip() or result.stdout.strip()}")
    return result.stdout

def adb_command(*args, timeout=10):
    """执行 ADB 主机命令并返回 CompletedProcess。"""
    return subprocess.run(
        [ADB, "-s", DEVICE, *map(str, args)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    )

def force_stop_game():
    """退出游戏；所有成功、失败和异常出口统一调用。"""
    if not DEVICE:
        return
    try:
        adb_shell(f"am force-stop {GAME_PKG}")
        _reapply_low_brightness()
    except Exception as exc:
        print(f"  ⚠️ 退出游戏失败: {exc}")

def keyguard_locked(policy):
    """Read explicit Android keyguard state; screen-on is not unlock evidence."""
    delegate = re.search(
        r"KeyguardServiceDelegate\s*\r?\n\s*showing=(true|false)\b", policy,
    )
    if delegate:
        return delegate.group(1) == "true"
    states = re.findall(r"\b(?:mShowingLockscreen|mKeyguardShowing)=(true|false)\b", policy)
    return any(value == "true" for value in states) if states else None


def wake_and_unlock():
    """Like the APK, dismiss an ordinary keyguard but never enter a password."""
    print("  唤醒屏幕并检查锁屏...")
    _reapply_low_brightness()
    adb_shell("input keyevent KEYCODE_WAKEUP")
    time.sleep(.3)
    for attempt in range(11):
        locked = keyguard_locked(adb_shell("dumpsys window policy"))
        if locked is None:
            locked = keyguard_locked(adb_shell("dumpsys activity activities"))
        if locked is False:
            print("  屏幕已唤醒，锁屏已解除")
            return True
        if attempt == 0:
            adb_shell("wm dismiss-keyguard")
        if attempt < 10:
            time.sleep(.3)
    raise RuntimeError("锁屏尚未解除或无法确认，请先在设备上手动解锁")

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
    """恢复原始亮度设置"""
    global _original_brightness, _original_auto_brightness, _brightness_mode
    
    if _original_brightness is None:
        return
    
    print("  🔆 恢复亮度设置...")
    
    errors = []
    # The devices have different backlight nodes. A missing fallback node must
    # not prevent restoration of normal Android brightness settings.
    if _brightness_mode in ('root_zero', 'root_one'):
        for node in ("panel0-backlight", "lcd-backlight"):
            try:
                adb_shell_root(f"echo {_original_brightness} > /sys/class/backlight/{node}/brightness")
                break
            except Exception:
                continue
        else:
            errors.append("无法恢复ROOT亮度节点")
    for command in (
        f"settings put system screen_brightness {_original_brightness}",
        f"settings put system screen_brightness_mode {_original_auto_brightness}",
    ):
        try:
            adb_shell(command)
        except Exception as error:
            errors.append(str(error))
    if errors:
        raise RuntimeError("恢复亮度失败：" + "；".join(errors))
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
    print("  Y - 普通模式，亮度降至最低(1)")
    print("  R - ROOT权限，亮度设为0（屏幕全黑）")
    print("  1 - ROOT权限，亮度设为1（极低亮度）")
    print("  N - 保持当前亮度设置")
    print("=" * 60)
    
    while True:
        choice = input("请选择 (Y/R/1/N): ").strip().upper()
        if choice in ['Y', 'YES']:
            return 'low'
        elif choice in ['R', 'ROOT']:
            return 'root_zero'
        elif choice == '1':
            return 'root_one'
        elif choice in ['N', 'NO']:
            print("  ℹ️ 保持当前亮度设置")
            return None
        else:
            print("  ⚠️ 请输入 Y、R、1 或 N")

def read_image(path):
    """Use Unicode-safe image IO on Windows as well as Linux."""
    try:
        return cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
    except (OSError, ValueError, cv2.error):
        return None


def write_image(path, image):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    ok, encoded = cv2.imencode(target.suffix or ".png", image)
    if not ok:
        raise RuntimeError("截图编码失败")
    encoded.tofile(str(target))


def screenshot(path=None):
    """Read a fresh lossless frame directly; never reuse or rotate old pixels."""
    path = path or SCREENSHOT_PATH
    result = subprocess.run(
        [ADB, "-s", DEVICE, "exec-out", "screencap", "-p"],
        capture_output=True, timeout=15,
    )
    if result.returncode != 0 or not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("ADB截图失败，禁止沿用旧截图")
    image = cv2.imdecode(np.frombuffer(result.stdout, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise RuntimeError("ADB截图无法解码")
    write_image(path, image)
    return str(path)


def tap(x, y, label=""):
    if label:
        print(f"点击：{label}（{x}, {y}）")
    adb_shell(f"input tap {int(x)} {int(y)}")


def swipe(x1, y1, x2, y2, duration_ms=1000):
    adb_shell(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(duration_ms)}")


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
    img = read_image(screenshot_path)
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
        
        tmpl = read_image(template_path)
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

def save_diagnostic(step, details=None, screenshot_path=None):
    """保存失败现场，供游戏更新后离线复现。"""
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    output_dir = SCRIPT_DIR / "diagnostics" / f"{stamp}_{step}"
    output_dir.mkdir(parents=True, exist_ok=True)
    screenshot_path = screenshot_path or SCREENSHOT_PATH
    if Path(screenshot_path).exists():
        shutil.copy2(screenshot_path, output_dir / "screenshot.png")
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

_ocr_engine = None

def get_ocr():
    global _ocr_engine
    if _ocr_engine is None:
        from rapidocr_onnxruntime import RapidOCR
        _ocr_engine = RapidOCR()
    return _ocr_engine

def read_harvest_info(screenshot_path):
    """从收获弹窗截图中OCR识别收获信息
    返回: {"exp": int, "crops": {作物名: 数量}} 或 None
    """
    import re
    
    img = read_image(screenshot_path)
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

def main(argv=None):
    from adb_runtime import AdbFarmRuntime
    from farm_runner import FarmRunner
    from farm_state import StateStore

    parser = argparse.ArgumentParser(description="王者荣耀自动务农，流程对齐 APK 0.3.20")
    parser.add_argument("--rounds", type=int, help="本次成功执行轮数，不填写则持续运行")
    parser.add_argument("--brightness", choices=("keep", "low", "root_zero", "root_one"),
                        help="亮度方式，不填写则启动时询问")
    args = parser.parse_args(argv)
    if args.rounds is not None and args.rounds < 1:
        parser.error("--rounds 必须大于0")
    print("王者荣耀自动务农 Python v4（对齐 APK 0.3.20）")
    if not resolve_device() or not check_adb_connection():
        raise RuntimeError("未找到可用设备")
    state_dir = os.environ.get("WZRY_STATE_DIR") or SCRIPT_DIR / "runtime_state"
    store = StateStore(state_dir, DEVICE)
    with store.locked():
        completed_before = store.state.checkpoint.completed_rounds
        try:
            global _brightness_mode
            if args.brightness is None:
                _brightness_mode = prompt_brightness_control()
            else:
                _brightness_mode = None if args.brightness == "keep" else args.brightness
            runtime = AdbFarmRuntime(sys.modules[__name__], _brightness_mode,
                                     screenshot_path=store.path.with_suffix(".png"))
            print(f"设备独立记录：{store.path}")
            # Initialize recognition once before timing the entry preparation.
            get_ocr()
            FarmRunner(runtime, store).run(max_rounds=args.rounds)
        finally:
            # Only the lock owner may clean up the device. Help, bad arguments,
            # and duplicate invocations must never stop another task's game.
            force_stop_game()
            restore_brightness()
            stats.rounds = store.state.checkpoint.completed_rounds - completed_before
    return 0


def run_main():
    try:
        return main()
    except KeyboardInterrupt:
        print("\n已停止，动作记录保留，下次启动先复查已发送动作。")
        return 130
    except Exception as error:
        print(f"\n运行失败：{error}")
        return 1
    finally:
        stats.summary()


if __name__ == "__main__":
    # Keep signal handling out of imports used by offline tests.
    import signal
    def stop_on_signal(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop_on_signal)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(run_main())
