"""OCR-gated farm actions, ported from the verified Android 0.3.20 flow.

This module is device-independent. The runtime owns screenshots, OCR and ADB;
no movement or farm tap is allowed without fresh, local screen evidence.
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, replace
from datetime import datetime
import math
import re
from typing import Iterable


class FarmActionError(RuntimeError):
    """Screen evidence was insufficient to safely continue the farm round."""


@dataclass(frozen=True)
class OcrBox:
    text: str
    left: int
    top: int
    right: int
    bottom: int
    confidence: float = 1.0

    @property
    def center_x(self) -> int:
        return self.left + (self.right - self.left) // 2

    @property
    def center_y(self) -> int:
        return self.top + (self.bottom - self.top) // 2


@dataclass(frozen=True)
class UiFrame:
    width: int
    height: int
    boxes: tuple[OcrBox, ...]

    @property
    def raw_text(self) -> str:
        return " ".join(box.text for box in self.boxes)


@dataclass(frozen=True)
class SwipeGesture:
    start_x: int
    start_y: int
    end_x: int
    end_y: int
    duration_ms: int


@dataclass(frozen=True)
class FarmMovementProfile:
    screen_width: int
    screen_height: int
    spawn_to_statue: SwipeGesture
    statue_to_farmland: SwipeGesture


_MOVEMENTS = (
    FarmMovementProfile(2400, 1080, SwipeGesture(430, 755, 305, 538, 1500),
                        SwipeGesture(430, 755, 430, 555, 1200)),
    FarmMovementProfile(2560, 1600, SwipeGesture(385, 1165, 200, 844, 1500),
                        SwipeGesture(385, 1165, 385, 869, 1200)),
    FarmMovementProfile(2560, 1564, SwipeGesture(385, 1138, 200, 825, 1500),
                        SwipeGesture(385, 1138, 385, 849, 1200)),
    FarmMovementProfile(1280, 720, SwipeGesture(160, 486, 60, 313, 1500),
                        SwipeGesture(160, 486, 160, 286, 1200)),
)


def movement_profile(width: int, height: int) -> FarmMovementProfile:
    if width <= height or height <= 0:
        raise FarmActionError(f"农场画面不是有效横屏：{width}×{height}")
    for profile in _MOVEMENTS:
        if (width, height) == (profile.screen_width, profile.screen_height):
            return profile
    reference = min(_MOVEMENTS, key=lambda p: (
        abs(p.screen_width / p.screen_height - width / height),
        abs(math.log(width / p.screen_width)) + abs(math.log(height / p.screen_height)),
    ))
    sx, sy = width / reference.screen_width, height / reference.screen_height

    def scaled(g: SwipeGesture) -> SwipeGesture:
        result = SwipeGesture(int(g.start_x * sx), int(g.start_y * sy),
                              int(g.end_x * sx), int(g.end_y * sy), g.duration_ms)
        if not (0 <= result.start_x < width and 0 <= result.end_x < width
                and 0 <= result.start_y < height and 0 <= result.end_y < height):
            raise FarmActionError("缩放后摇杆坐标超出屏幕，禁止移动")
        return result

    return FarmMovementProfile(width, height, scaled(reference.spawn_to_statue),
                               scaled(reference.statue_to_farmland))


def normalize_ui_text(text: str) -> str:
    for dash in ("-", "－", "—"):
        text = text.replace(dash + "键务农", "一键务农")
    text = re.sub(r"(?<![\u3400-\u9fff])局奖励", "对局奖励", text)
    return re.sub(r"[\s，。！？、:：；;·•]+", "", text).strip().lower()


def _safe_box(frame: UiFrame, box: OcrBox, region: tuple,
              max_width: float, max_height: float) -> bool:
    left, top, right, bottom = region
    return (frame.width > frame.height > 0
            and 0 <= box.left < box.right <= frame.width
            and 0 <= box.top < box.bottom <= frame.height
            and int(frame.width * left) <= box.center_x < int(frame.width * right)
            and int(frame.height * top) <= box.center_y < int(frame.height * bottom)
            and 1 <= box.right - box.left <= int(frame.width * max_width)
            and 1 <= box.bottom - box.top <= int(frame.height * max_height))


def _one_line(frame: UiFrame, boxes: list[OcrBox]) -> bool:
    ordered = sorted(boxes, key=lambda b: b.left)
    return (max(b.center_y for b in boxes) - min(b.center_y for b in boxes)
            <= frame.height * .05
            and max(b.right for b in boxes) - min(b.left for b in boxes) <= frame.width * .35
            and all(b.left - a.right <= frame.width * .05 for a, b in zip(ordered, ordered[1:])))


def find_text_target(frame: UiFrame | None, phrases: str | Iterable[str], *,
                     region=(0.0, 0.0, 1.0, 1.0), max_width=1.0,
                     max_height=1.0, exact=False) -> OcrBox | None:
    """Locate text only inside the supplied normalized rectangle.

    Up to four adjacent OCR elements may form one label; unrelated screen
    words cannot be combined into a button. The returned text is the phrase.
    """
    if frame is None:
        return None
    phrases = (phrases,) if isinstance(phrases, str) else phrases
    parts = [normalize_ui_text(b.text) for b in frame.boxes]
    for phrase in phrases:
        normalized = normalize_ui_text(phrase)
        if not normalized:
            continue
        match = (lambda s: s == normalized) if exact else (lambda s: normalized in s)
        for box, part in zip(frame.boxes, parts):
            if match(part) and _safe_box(frame, box, region, max_width, max_height):
                return replace(box, text=phrase)
        for start in range(len(parts)):
            boxes, joined = [], ""
            for index in range(start, min(len(parts), start + 4)):
                if not parts[index]:
                    continue
                boxes.append(frame.boxes[index])
                joined += parts[index]
                if len(joined) > len(normalized) + 4:
                    break
                if len(boxes) < 2 or not match(joined) or not _one_line(frame, boxes):
                    continue
                combined = OcrBox(phrase, min(b.left for b in boxes), min(b.top for b in boxes),
                                  max(b.right for b in boxes), max(b.bottom for b in boxes),
                                  min(b.confidence for b in boxes))
                if _safe_box(frame, combined, region, max_width, max_height):
                    return combined
    return None


_ANCHOR_SPECS = (
    ("的农场", (.08, .00, .55, .18), .45),
    ("仓库", (.72, .06, 1.00, .38), .24),
    ("社交", (.72, .12, 1.00, .48), .24),
    ("对局奖励", (.55, .00, .88, .20), .24),
    ("百科", (.30, .78, .62, 1.00), .24),
    ("种植", (.70, .62, .98, .99), .24),
    ("一键务农", (.48, .35, .84, .90), .24),
    ("农场升级", (.48, .50, .82, .96), .24),
    ("流光加速", (.48, .30, .86, .92), .24),
)
_POPUP_CONTEXT = dict(region=(.10, .08, .90, .90), max_width=.45, max_height=.16)
_STRONG_POPUPS = ("今日内不再弹出", "活动公告", "更新公告", "系统公告", "登录公告", "温馨提示")
_HARVEST_PHRASES = ("恭喜您获得", "点击继续")


def farm_anchors(frame: UiFrame | None) -> set[str]:
    """Distinct farm labels in independently constrained screen positions."""
    anchors, coordinates = set(), set()
    for phrase, region, max_width in _ANCHOR_SPECS:
        box = find_text_target(frame, phrase, region=region, max_width=max_width, max_height=.14)
        if box is not None:
            coordinate = (box.left, box.top, box.right, box.bottom)
            if coordinate not in coordinates:
                coordinates.add(coordinate)
                anchors.add(phrase)
    return anchors


def _has_text(frame: UiFrame, phrase: str) -> bool:
    # Joined text also blocks when OCR splits a modal label but its coordinates
    # cannot be reconstructed safely. Blocking needs less evidence than clicking.
    return normalize_ui_text(phrase) in "".join(normalize_ui_text(b.text) for b in frame.boxes)


def blocking_popup_reason(frame: UiFrame | None) -> str | None:
    if frame is None:
        return "OCR 未返回可验证结果"
    if _has_text(frame, "请您休息一下"):
        return "检测到防沉迷休息提示"
    if _has_text(frame, "确定"):
        return "检测到确定按钮，弹窗上下文尚未确认"
    if any(_has_text(frame, phrase) for phrase in _HARVEST_PHRASES):
        return "检测到收获弹窗文字"
    popup = find_text_target(frame, _STRONG_POPUPS, **_POPUP_CONTEXT)
    return f"检测到未处理弹窗：{popup.text}" if popup else None


def locate_one_click_target(frame: UiFrame | None) -> OcrBox | None:
    if frame is None or blocking_popup_reason(frame):
        return None
    spec = dict(region=(.50, .42, .80, .86), max_width=.18, max_height=.12)
    target = find_text_target(frame, "一键务农", **spec)
    if target:
        return target
    context = find_text_target(frame, ("流光加速", "农场升级"),
                               region=(.45, .25, .90, .98), max_width=.30, max_height=.18)
    if context:
        target = find_text_target(frame, ("一键务衣", "一健务衣", "二键务衣"), **spec)
        if target:
            return replace(target, text="一键务农")
    return None


def locate_harvest_target(frame: UiFrame | None) -> OcrBox | None:
    if frame is None:
        return None
    if _has_text(frame, "请您休息一下") or _has_text(frame, "确定"):
        return None
    if find_text_target(frame, _STRONG_POPUPS, **_POPUP_CONTEXT):
        return None
    button = find_text_target(frame, "点击继续", region=(.20, .40, .80, .94),
                              max_width=.25, max_height=.15)
    if button is None:
        return None
    header = find_text_target(frame, "恭喜您获得", region=(.20, .12, .80, .64),
                              max_width=.35, max_height=.16)
    if header and header.center_y < button.center_y and abs(header.center_x - button.center_x) <= frame.width * .30:
        return button
    reward = find_text_target(frame, ("农场经验", "经验", "XP"),
                              region=(.18, .18, .82, .84), max_width=.30, max_height=.16)
    if reward and reward.center_y < button.center_y and abs(reward.center_x - button.center_x) <= frame.width * .32:
        return button
    return None


def _stable_target(first: OcrBox, second: OcrBox, frame: UiFrame) -> bool:
    return (abs(first.center_x - second.center_x) <= int(frame.width * .03)
            and abs(first.center_y - second.center_y) <= int(frame.height * .03))


@dataclass(frozen=True)
class FarmActionResult:
    harvested: bool
    harvest_info: dict | None
    farmland_state: object
    first_water_at: datetime
    ready_at: datetime
    recovered_action: bool = False


class FarmActionAutomation:
    def __init__(self, runtime, guard, not_before=None, resume_after_action_at=None):
        self.runtime = runtime
        self.guard = guard
        self.not_before = not_before
        self.resume_after_action_at = resume_after_action_at
        self._expected_size = None

    def _log(self, message):
        self.runtime.log(message)

    def _wait(self, seconds):
        # Short slices let the runtime honour cancellation promptly.
        while seconds > 0:
            duration = min(seconds, .25)
            self.runtime.sleep(duration)
            seconds -= duration

    def _stable(self, label, timeout_seconds=4):
        result = self.runtime.wait_screen_stable(label, timeout_seconds=timeout_seconds)
        if result is False or getattr(result, "stable", True) is False:
            self._wait(1)

    def _read_raw_ui(self):
        try:
            frame = self.runtime.read_ui()
        except Exception as error:
            self._log(f"OCR 暂不可用：{error}")
            return None
        if frame is not None and self._expected_size is not None:
            if (frame.width, frame.height) != self._expected_size:
                raise FarmActionError("画面尺寸已变化，禁止沿用旧的摇杆或点击坐标")
        return frame

    def _read_ui(self):
        dismissed = 0
        while True:
            frame = self._read_raw_ui()
            if frame is None:
                return None
            rest = find_text_target(frame, "请您休息一下", **_POPUP_CONTEXT)
            confirm = find_text_target(frame, "确定", region=(.25, .35, .75, .90),
                                       max_width=.25, max_height=.15)
            strong = find_text_target(frame, _STRONG_POPUPS, **_POPUP_CONTEXT)
            if _has_text(frame, "请您休息一下") or _has_text(frame, "确定"):
                if rest is None or confirm is None:
                    raise FarmActionError("防沉迷提示证据不完整，禁止盲点")
                context, target = rest, confirm
                target_spec = dict(region=(.25, .35, .75, .90), max_width=.25, max_height=.15)
            elif strong:
                # No fixed coordinate fallback for the tiny close icon. A
                # verified text target is the conservative desktop fallback.
                target_spec = dict(region=(.60, .03, .96, .45), max_width=.20, max_height=.15)
                target = find_text_target(frame, "关闭", **target_spec)
                if target is None:
                    raise FarmActionError("已确认公告弹窗，但无法安全定位关闭按钮")
                context = strong
            else:
                return frame
            if dismissed >= 3:
                raise FarmActionError("连续关闭三次后弹窗仍然存在")
            self._wait(1)
            fresh = self._read_raw_ui()
            fresh_context = find_text_target(fresh, context.text, **_POPUP_CONTEXT)
            fresh_target = find_text_target(fresh, target.text, **target_spec)
            if (fresh is None or fresh_context is None or fresh_target is None
                    or not _stable_target(context, fresh_context, fresh)
                    or not _stable_target(target, fresh_target, fresh)):
                raise FarmActionError("弹窗未通过连续两帧确认，禁止盲点")
            self._wait(1)
            self.runtime.tap(fresh_target.center_x, fresh_target.center_y)
            self._wait(2)
            self._stable("关闭弹窗后")
            self._wait(1)
            dismissed += 1

    def _require_farm_ready(self):
        previous = set()
        previous_size = None
        started = self.runtime.monotonic()
        for attempt in range(32):
            frame = self._read_ui()
            if self.runtime.monotonic() - started > 15:
                break
            anchors = farm_anchors(frame) if frame is not None and not blocking_popup_reason(frame) else set()
            size = (frame.width, frame.height) if frame is not None else None
            if len(anchors) >= 2 and previous & anchors and size == previous_size:
                self._log("连续两帧确认农场页面")
                return frame
            previous = anchors if len(anchors) >= 2 else set()
            previous_size = size
            if attempt < 31:
                self._wait(.5)
        raise FarmActionError("未连续两帧确认农场页面，禁止摇杆移动")

    def _require_one_click(self):
        started = self.runtime.monotonic()
        for attempt in range(22):
            frame = self._read_ui()
            target = locate_one_click_target(frame)
            if target:
                return target
            if self.runtime.monotonic() - started >= 20:
                break
            if attempt < 21:
                self._wait(1)
        raise FarmActionError("移动后未找到安全的一键务农文字")

    def _await_action_time(self):
        waited = False
        while self.not_before is not None:
            remaining = (self.not_before - self.runtime.now()).total_seconds()
            if remaining <= 0:
                break
            if not waited:
                self._log(f"已到达雕像，等待计划浇水时刻 {self.not_before}")
            waited = True
            self._wait(min(remaining, 1))
        return waited

    def _phase_deadline(self, started, message):
        if self.runtime.monotonic() - started > 60:
            raise FarmActionError(message)

    @staticmethod
    def _absent_anchors(frame):
        if frame is None or blocking_popup_reason(frame):
            return set()
        anchors = farm_anchors(frame)
        return anchors if len(anchors) >= 2 else set()

    def _handle_harvest_popup(self):
        started = self.runtime.monotonic()
        count, previous = 0, set()
        for attempt in range(5):
            frame = self._read_ui()
            self._phase_deadline(started, "收获弹窗识别超时")
            target = locate_harvest_target(frame)
            if target:
                return self._close_harvest_popup(frame, target)
            anchors = self._absent_anchors(frame)
            count = count + 1 if anchors and previous & anchors else (1 if anchors else 0)
            previous = anchors
            if count >= 3:
                return False, None
            if attempt < 4:
                self._wait(1)
        raise FarmActionError("收获弹窗状态无法确认，禁止继续移动")

    def _read_reward(self):
        try:
            return self.runtime.read_harvest_info()
        except Exception as error:
            self._log(f"收获数量暂未读出：{error}")
            return None

    def _close_harvest_popup(self, initial, initial_target):
        self.guard.on_harvest_observed()
        started = self.runtime.monotonic()
        previous_targets, rewards = [initial_target], []
        reward = self._read_reward()
        if reward:
            rewards.append(reward)
        self._wait(1)
        for attempt in range(2):
            frame = self._read_ui()
            target = locate_harvest_target(frame)
            if target:
                previous_targets.append(target)
                reward = self._read_reward()
                if reward:
                    rewards.append(reward)
            self._phase_deadline(started, "收获弹窗处理超时")
            if attempt == 0:
                self._wait(.5)
        info = merge_harvest_readings(rewards)
        confirmed, absent = None, 0
        for attempt in range(5):
            frame = self._read_ui()
            self._phase_deadline(started, "收获弹窗处理超时")
            target = locate_harvest_target(frame)
            if target and any(_stable_target(prior, target, frame) for prior in previous_targets):
                confirmed = target
                break
            absent = absent + 1 if self._absent_anchors(frame) else 0
            if absent >= 3:
                return True, info
            if target:
                previous_targets.append(target)
            if attempt < 4:
                self._wait(.5)
        if confirmed is None:
            raise FarmActionError("收获弹窗多帧确认后仍缺少安全文字坐标")
        self.runtime.tap(confirmed.center_x, confirmed.center_y)
        self._wait(2)
        self._stable("关闭收获弹窗后")
        absent = 0
        for attempt in range(3):
            frame = self._read_ui()
            self._phase_deadline(started, "收获弹窗处理超时")
            absent = absent + 1 if self._absent_anchors(frame) else 0
            if absent >= 3:
                return True, info
            if attempt < 2:
                self._wait(1)
        raise FarmActionError("点击后未连续三帧确认收获弹窗消失")

    def _perform_one_click(self, target, maturity_harvest=False):
        before = self.guard.before_maturity_harvest if maturity_harvest else self.guard.before_tap
        if not before(target):
            raise FarmActionError("本轮务农已越过发送边界，禁止重复点击")
        self.runtime.tap(target.center_x, target.center_y)
        action_at = self.runtime.now()
        self._log("执行同场收获" if maturity_harvest else "执行一键务农")
        self._wait(2)
        self._stable("一键务农点击后", timeout_seconds=10)
        harvest = self._handle_harvest_popup()
        after = self.guard.after_maturity_harvest_accepted if maturity_harvest else self.guard.after_tap_accepted
        after(action_at)
        return action_at, harvest

    def _read_farmland(self):
        readings = []
        started = self.runtime.monotonic()
        for attempt in range(5):
            frame = self._read_ui()
            reason = blocking_popup_reason(frame)
            if reason:
                raise FarmActionError(f"土地识别前存在未确认画面：{reason}")
            reading = self.runtime.read_farmland()
            readings.append(reading)
            self._phase_deadline(started, "土地 OCR 超时")
            self._log(f"土地识别（{attempt + 1}/5）：{reading.kind} {reading.raw_text}")
            if len(readings) >= 3:
                consensus = resolve_farmland_consensus(readings)
                if consensus is not None:
                    return consensus
            if attempt < 4:
                self._wait(.5)
        from farm_schedule import FarmlandReading
        return FarmlandReading(kind="unknown", raw_text=" | ".join(r.raw_text for r in readings),
                               observed_at=self.runtime.now(), reason="五帧土地状态或成熟时间不一致")

    def _move_and_read(self, profile):
        self._swipe_verified(profile.statue_to_farmland)
        self._stable("移动到土地后")
        return self._read_farmland()

    def _swipe_verified(self, gesture):
        # Screen-stability sampling may itself observe a rotation or a changed
        # navigation bar. Refresh OCR before reusing the movement profile.
        frame = self._read_ui()
        if frame is None or blocking_popup_reason(frame) or len(farm_anchors(frame)) < 2:
            raise FarmActionError("移动前未确认当前农场画面，禁止沿用旧的摇杆坐标")
        self.runtime.swipe(gesture)

    def run(self):
        recovered_harvest = (self._handle_harvest_popup()
                             if self.resume_after_action_at is not None else (False, None))
        spawn = self._require_farm_ready()
        profile = movement_profile(spawn.width, spawn.height)
        self._expected_size = (spawn.width, spawn.height)
        self.runtime.swipe(profile.spawn_to_statue)
        self._stable("移动到雕像后")
        ready_at = self.runtime.now()
        if self.resume_after_action_at is not None:
            self._log("本轮务农已发送，恢复时只复查土地，不重复浇水")
            action_at, harvest = self.resume_after_action_at, recovered_harvest
        else:
            target = self._require_one_click()
            ready_at = self.runtime.now()
            while self._await_action_time():
                target = self._require_one_click()
            action_at, harvest = self._perform_one_click(target)
        farmland = self._move_and_read(profile)
        if farmland.kind == "mature":
            self._log("最新两帧确认已成熟，同场执行一次收获")
            outbound = profile.statue_to_farmland
            reverse = replace(outbound, end_x=outbound.start_x * 2 - outbound.end_x,
                              end_y=outbound.start_y * 2 - outbound.end_y)
            if not (0 <= reverse.end_x < profile.screen_width and 0 <= reverse.end_y < profile.screen_height):
                raise FarmActionError("返回雕像坐标超出屏幕，禁止移动")
            self._swipe_verified(reverse)
            self._stable("返回雕像后")
            target = self._require_one_click()
            action_at, followup = self._perform_one_click(target, maturity_harvest=True)
            harvest = harvest[0] or followup[0], followup[1] or harvest[1]
            farmland = self._move_and_read(profile)
            if farmland.kind == "mature":
                raise FarmActionError("同场收获后仍显示已成熟，禁止继续重复点击")
        return FarmActionResult(harvested=harvest[0], harvest_info=harvest[1],
                                farmland_state=farmland, first_water_at=action_at,
                                ready_at=ready_at, recovered_action=self.resume_after_action_at is not None)


def resolve_farmland_consensus(readings):
    """APK consensus: unknowns abstain, mature needs the latest two frames."""
    if len(readings) < 2:
        return None
    if all(r.kind == "mature" for r in readings[-2:]):
        return readings[-1]
    counts = Counter(r.kind for r in readings if r.kind != "unknown")
    qualified = {kind: count for kind, count in counts.items() if count >= 2}
    if not qualified:
        return None
    maximum = max(qualified.values())
    winners = [kind for kind, count in qualified.items() if count == maximum]
    if len(winners) != 1:
        return None
    kind = winners[0]
    if kind == "mature":
        return None
    candidates = [r for r in readings if r.kind == kind]
    if kind == "empty":
        return candidates[0]
    if kind != "planted" or readings[-1].kind != "planted":
        return None
    groups = {}
    for reading in candidates:
        if reading.maturity_at is not None:
            key = reading.maturity_at.replace(second=0, microsecond=0)
            groups.setdefault(key, []).append(reading)
    groups = [items for items in groups.values() if len(items) >= 2]
    if not groups:
        return None
    maximum = max(map(len, groups))
    winners = [items for items in groups if len(items) == maximum]
    return winners[0][-1] if len(winners) == 1 else None


def merge_harvest_readings(readings: Iterable[dict]) -> dict | None:
    readings = list(readings)
    if not readings:
        return None
    crops = {}
    for reading in readings:
        for name, count in reading.get("crops", {}).items():
            crops[name] = max(crops.get(name, 0), count)
    return {"exp": max(r.get("exp", r.get("experience", 0)) for r in readings),
            "crops": crops}
