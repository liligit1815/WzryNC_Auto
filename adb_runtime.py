"""ADB and RapidOCR adapter for the APK-equivalent automation policies."""
from datetime import datetime
import hashlib
import os
from pathlib import Path
import time

import cv2
import numpy as np

from farm_actions import (
    OcrBox, UiFrame, blocking_popup_reason, farm_anchors, find_text_target,
    locate_one_click_target,
)
from farm_schedule import FarmlandReading, parse_farmland


def maturity_roi(image):
    height, width = image.shape[:2]
    if height / width >= 0.55:
        crop = image[int(height * .08):int(height * .48), :int(width * .35)]
    else:
        crop = image[height // 3:height * 2 // 3, :width // 3]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    gray = np.clip(gray.astype(np.float32) * 1.35 - .175 * 255, 0, 255).astype(np.uint8)
    return cv2.resize(gray, None, fx=2, fy=2, interpolation=cv2.INTER_LINEAR)


def _mapped_ocr_boxes(lines, offset_x=0, offset_y=0, scale=1):
    boxes = []
    for points, text, score in lines or []:
        points = np.asarray(points)
        left, top = points.min(axis=0)
        right, bottom = points.max(axis=0)
        if np.isfinite([left, top, right, bottom, score]).all():
            boxes.append(OcrBox(str(text), int(left / scale + offset_x),
                                int(top / scale + offset_y), int(right / scale + offset_x),
                                int(bottom / scale + offset_y), float(score)))
    return tuple(boxes)


def ocr_ui_frame(image, ocr):
    """Read one immutable screenshot, with a gated high-resolution action crop.

    RapidOCR sometimes drops the leading 一 in the small farm action label.
    Re-reading the known action area at 2x recovers the complete label in the
    repository's real screenshot; acceptance still uses the original safety
    checks and coordinates are mapped back to the original screenshot.
    """
    height, width = image.shape[:2]
    if width <= height:
        return None
    lines, _ = ocr(image)
    frame = UiFrame(width, height, _mapped_ocr_boxes(lines))
    context = find_text_target(frame, ("流光加速", "农场升级"),
                               region=(.45, .25, .90, .98), max_width=.30, max_height=.18)
    if (context is not None and len(farm_anchors(frame)) >= 2
            and blocking_popup_reason(frame) is None
            and locate_one_click_target(frame) is None):
        left, top = int(width * .45), int(height * .25)
        crop = image[top:int(height * .98), left:int(width * .90)]
        enlarged = cv2.resize(crop, None, fx=2, fy=2, interpolation=cv2.INTER_LINEAR)
        extra, _ = ocr(enlarged)
        frame = UiFrame(width, height, frame.boxes + _mapped_ocr_boxes(extra, left, top, 2))
    return frame


class AdbFarmRuntime:
    def __init__(self, backend, brightness_mode=None, screenshot_path=None):
        self.backend = backend
        self.brightness_mode = brightness_mode
        identity = hashlib.sha256(backend.DEVICE.encode("utf-8")).hexdigest()[:24]
        directory = Path(os.environ.get("WZRY_STATE_DIR") or backend.SCRIPT_DIR / "runtime_state")
        self.screenshot_path = str(screenshot_path or directory / f"farm-{identity}.png")
        self._last_shape = None

    @staticmethod
    def now():
        return datetime.now()

    @staticmethod
    def monotonic():
        return time.monotonic()

    @staticmethod
    def sleep(seconds):
        time.sleep(seconds)

    @staticmethod
    def log(message):
        print(f"[{datetime.now():%H:%M:%S}] {message}", flush=True)

    def prepare_device(self):
        if not self.backend.check_adb_connection():
            raise RuntimeError("ADB连接不可用")
        self.backend.wake_and_unlock()
        if self.brightness_mode and self.backend._original_brightness is None:
            self.backend.get_brightness_settings()
            self.backend._brightness_mode = self.brightness_mode
            {
                "low": self.backend.set_brightness_low,
                "root_zero": self.backend.set_brightness_zero_root,
                "root_one": self.backend.set_brightness_one_root,
            }[self.brightness_mode]()

    def is_game_running(self):
        result = self.backend.adb_command("shell", "pidof", self.backend.GAME_PKG)
        if result.returncode not in (0, 1) or (result.returncode and result.stderr.strip()):
            raise RuntimeError(f"无法确认游戏进程：{result.stderr.strip()}")
        return bool(result.stdout.strip())

    def stop_game(self):
        self.backend.adb_shell(f"am force-stop {self.backend.GAME_PKG}")
        if self.is_game_running():
            raise RuntimeError("游戏进程未退出")

    def launch_game(self):
        output = self.backend.adb_shell(f"am start -n {self.backend.GAME_ACT}")
        if "Error:" in output or "Exception" in output:
            raise RuntimeError(f"启动游戏失败：{output.strip()}")

    def _require_foreground(self):
        output = self.backend.adb_shell("dumpsys activity activities")
        if not any(
            ("ResumedActivity" in line or "topResumedActivity" in line)
            and self.backend.GAME_PKG in line for line in output.splitlines()
        ):
            raise RuntimeError("游戏不在前台，禁止触控")

    def tap(self, x, y):
        self._require_foreground()
        if self._last_shape is None:
            raise RuntimeError("尚无可验证截图，禁止触控")
        height, width = self._last_shape
        if width <= height or not (0 <= x < width and 0 <= y < height):
            raise RuntimeError("点击坐标不在当前横屏画面内")
        self.backend.tap(int(x), int(y))

    def swipe(self, gesture):
        self._require_foreground()
        if self._last_shape is None:
            raise RuntimeError("尚无可验证截图，禁止移动")
        height, width = self._last_shape
        for x, y in ((gesture.start_x, gesture.start_y), (gesture.end_x, gesture.end_y)):
            if width <= height or not (0 <= x < width and 0 <= y < height):
                raise RuntimeError("摇杆坐标不在当前横屏画面内")
        self.backend.swipe(gesture.start_x, gesture.start_y, gesture.end_x,
                           gesture.end_y, gesture.duration_ms)

    def press_back(self):
        self._require_foreground()
        self.backend.adb_shell("input keyevent 4")

    def _capture(self):
        # Clear stale coordinate evidence even if acquisition raises.
        self._last_shape = None
        path = self.backend.screenshot(self.screenshot_path)
        image = self.backend.read_image(path)
        if image is None:
            raise RuntimeError("无法解码当前截图")
        self._last_shape = image.shape[:2]
        return image

    def read_ui(self):
        try:
            image = self._capture()
            return ocr_ui_frame(image, self.backend.get_ocr())
        except Exception as error:
            self.log(f"页面文字识别失败：{error}")
            return None

    def read_farmland(self):
        observed_at = self.now()
        try:
            image = self._capture()
            observed_at = self.now()
            height, width = image.shape[:2]
            if width <= height:
                raise RuntimeError("土地截图不是横屏")
            lines, _ = self.backend.get_ocr()(maturity_roi(image))
            raw = " ".join(str(line[1]) for line in lines or [])
            self.log(f"土地文字：{raw or '<空>'}")
            return parse_farmland(raw, observed_at)
        except Exception as error:
            return FarmlandReading("unknown", "", observed_at, reason=str(error))

    def read_harvest_info(self):
        self._capture()
        return self.backend.read_harvest_info(self.screenshot_path)

    def _template_target(self, names):
        self._capture()
        match = self.backend.find_any_template(names, self.screenshot_path)
        if match is None:
            return None
        width, height = match["scale_size"]
        return OcrBox(match["template"], match["x"] - width // 2,
                      match["y"] - height // 2, match["x"] + width // 2,
                      match["y"] + height // 2, match["score"])

    def find_popup_close(self):
        return self._template_target(["close_popup.png", "close_popup_event.png"])

    def find_start_target(self):
        return self._template_target(["start_game.png"])

    def wait_screen_stable(self, label, timeout_seconds=8):
        deadline = self.monotonic() + timeout_seconds
        previous = None
        stable_count = 0
        while self.monotonic() < deadline:
            image = self._capture()
            height, width = image.shape[:2]
            if width <= height:
                previous = None
                stable_count = 0
            else:
                xs = (width * (.04 + .92 * (np.arange(32) + .5) / 32)).astype(int)
                ys = (height * (.08 + .84 * (np.arange(18) + .5) / 18)).astype(int)
                grid = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)[np.ix_(ys, xs)].astype(np.int16)
                if previous is not None and previous[0] == (height, width):
                    difference = np.abs(grid - previous[1])
                    stable = np.mean(difference > 18) <= .12 and np.mean(difference) <= 9
                    stable_count = stable_count + 1 if stable else 0
                    if stable_count >= 2:
                        return True
                previous = ((height, width), grid)
            self.sleep(.35)
        self.log(f"{label}画面稳定等待超时，仍需后续文字证据确认")
        return False

    def record_harvest(self, info):
        info = info or {}
        self.backend.stats.add_harvest(info.get("exp", 0), info.get("crops"))

    def cleanup_round(self):
        errors = []
        for operation in (self.stop_game, self.backend.restore_brightness):
            try:
                operation()
            except Exception as error:
                errors.append(str(error))
        if errors:
            raise RuntimeError("本轮清理失败：" + "；".join(errors))

    def save_diagnostic(self, step, details):
        self.backend.save_diagnostic(step, details, screenshot_path=self.screenshot_path)
