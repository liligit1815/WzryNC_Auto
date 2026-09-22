"""ADB navigation following the verified Android app's page-evidence rules.

The transport does not require root. Every coordinate comes from fresh OCR or
a fresh close-template match; the Android Back action is permitted once for a
two-frame-confirmed return-welfare page. Image-only popup dismissal is disabled:
the Python runtime does not expose the APK's dimmed-backdrop evidence score.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from farm_actions import (
    OcrBox, UiFrame, blocking_popup_reason, farm_anchors, find_text_target,
    locate_harvest_target,
)


class NavigationFailure(RuntimeError):
    """The current page could not be proven safe to navigate."""


class NavigationRuntime(Protocol):
    def is_game_running(self) -> bool: ...
    def stop_game(self) -> None: ...
    def launch_game(self) -> None: ...
    def press_back(self) -> None: ...
    def read_ui(self) -> UiFrame | None: ...
    def tap(self, x: int, y: int) -> None: ...
    def wait_screen_stable(self, label: str, timeout_seconds: float = 4) -> object: ...
    def sleep(self, seconds: float) -> None: ...
    def monotonic(self) -> float: ...
    def log(self, message: str) -> None: ...
    def find_popup_close(self) -> OcrBox | None: ...
    def find_start_target(self) -> OcrBox | None: ...


@dataclass(frozen=True)
class _Target:
    box: OcrBox
    width: int
    height: int
    phrase: str


LEGAL_CONTEXTS = (
    "游戏许可及服务协议", "王者荣耀隐私保护指引", "儿童隐私保护指引",
    "用户协议", "隐私政策", "隐私保护协议",
)
POPUP_CONTEXTS = (
    "今日内不再弹出", "每日充值送好礼", "充值送好礼", "活动公告",
    "更新公告", "系统公告", "登录公告", "温馨提示",
)
CROSS_FRAME_POPUP_CONTEXTS = frozenset(POPUP_CONTEXTS) - {"温馨提示"}
RANK_STRONG = ("5v5排位赛", "多人排位", "单/多人排位")
RANK_SECONDARY = (
    "房间号组队", "巅峰赛", "分路段位", "英雄战力", "赛季", "段位",
    "勇者积分", "排位保护卡",
)


class EnterFarmAutomation:
    """Clean launch -> confirmed start -> confirmed lobby -> confirmed farm."""

    def __init__(self, runtime: NavigationRuntime, *, allow_harvest_recovery: bool = False):
        self.runtime = runtime
        self.allow_harvest_recovery = allow_harvest_recovery
        self._welfare_returned = False
        self._popup_dismissals = 0

    def run(self) -> UiFrame:
        self._welfare_returned = False
        self._popup_dismissals = 0
        if self.runtime.is_game_running():
            self.runtime.log("检测到游戏进程，重新启动以确保初始状态")
            self.runtime.stop_game()
            self.runtime.sleep(5)
        self.runtime.log("启动王者荣耀，等待10秒后确认开始页面")
        self.runtime.launch_game()
        self.runtime.sleep(10)
        self._click_start(self._require_navigation_target("开始游戏", 60))
        self.runtime.log("开始游戏点击后等待8秒，再处理广告和农场入口")
        self.runtime.sleep(8)
        entry = self._require_navigation_target("王者农场入口", 90)
        self._tap(entry, "进入农场")
        self.runtime.sleep(7)
        self._wait_stable("进入农场点击后", 15)
        return self._require_farm_page(60)

    def _read(self, label: str) -> UiFrame | None:
        try:
            frame = self.runtime.read_ui()
        except Exception as error:
            self.runtime.log(f"{label} OCR失败：{error}")
            return None
        if frame is None:
            return None
        if frame.width <= frame.height or frame.height <= 0:
            self.runtime.log(f"等待游戏切换横屏（{frame.width}×{frame.height}）")
            return None
        for phrase in LEGAL_CONTEXTS:
            if find_text_target(
                frame, phrase, region=(.08, .05, .92, .90),
                max_width=.70, max_height=.18,
            ):
                raise NavigationFailure(f"检测到“{phrase}”，请在手机上阅读并手动选择后重试")
        return frame

    @staticmethod
    def _target(
        frame: UiFrame,
        phrase: str | tuple[str, ...],
        region: tuple[float, float, float, float],
        max_width: float = .35,
        max_height: float = .15,
        *,
        exact: bool = False,
    ) -> _Target | None:
        phrases = (phrase,) if isinstance(phrase, str) else phrase
        for word in phrases:
            box = find_text_target(
                frame, word, region=region, max_width=max_width,
                max_height=max_height, exact=exact,
            )
            if box is not None:
                return _Target(box, frame.width, frame.height, word)
        return None

    def _start_target(self, frame: UiFrame) -> _Target | None:
        # The look-alike loading-screen text around 85% height is not a button.
        return self._target(frame, "开始游戏", (.35, .60, .65, .82))

    def _entry_target(self, frame: UiFrame) -> _Target | None:
        return self._target(frame, ("王者农场", "HOMESTEAD", "来农场"), (.15, .55, .50, .86))

    def _welfare_target(self, frame: UiFrame) -> _Target | None:
        return self._target(frame, "回归福利", (.08, .01, .40, .18))

    @staticmethod
    def _stable(first: _Target | None, second: _Target | None, tolerance: float = .12) -> bool:
        return bool(
            first is not None and second is not None
            and (first.width, first.height) == (second.width, second.height)
            and abs(first.box.center_x - second.box.center_x) <= second.width * tolerance
            and abs(first.box.center_y - second.box.center_y) <= second.height * tolerance
        )

    def _tap(self, target: _Target, label: str) -> None:
        self.runtime.log(f"{label}（{target.box.center_x}, {target.box.center_y}）")
        self.runtime.tap(target.box.center_x, target.box.center_y)

    def _wait_stable(self, label: str, timeout_seconds: float = 4) -> None:
        result = self.runtime.wait_screen_stable(label, timeout_seconds=timeout_seconds)
        if result is False or getattr(result, "stable", True) is False:
            self.runtime.log(f"{label}仍有动画，继续通过OCR连续确认")
            self.runtime.sleep(1)

    def _click_start(self, target: _Target) -> None:
        for attempt in range(2):
            self._tap(target, "开始游戏" if attempt == 0 else "开始游戏受控重试（1/1）")
            self.runtime.sleep(8)
            target = self._start_target_or_exit()
            if target is None:
                self.runtime.log("开始游戏页面已连续两帧确认离开")
                return
        raise NavigationFailure("开始游戏受控重试后页面仍未离开，本轮已终止")

    def _start_target_or_exit(self) -> _Target | None:
        previous = None
        absent_frames = rank_frames = 0
        for index in range(8):
            frame = self._read("开始游戏点击后")
            if frame is None or not frame.boxes:
                previous = None
                absent_frames = rank_frames = 0
            else:
                rank_frames = rank_frames + 1 if self._is_rank_page(frame) else 0
                if rank_frames >= 2:
                    raise NavigationFailure("检测到排位赛页面，禁止继续点击")
                current = self._start_target(frame)
                # A popup is an obstruction, not proof that a second start tap
                # is appropriate. Leave it for the bounded popup handler only
                # when the start label is genuinely absent in both frames.
                if current is None:
                    previous = None
                    absent_frames += 1
                    if absent_frames >= 2:
                        return None
                else:
                    absent_frames = 0
                    if self._popup_context(frame) or self._rest_context(frame):
                        previous = None
                    else:
                        self._reject_unknown_popup(frame)
                        if self._stable(previous, current):
                            return current
                        previous = current
            if index < 7:
                self.runtime.sleep(.5)
        raise NavigationFailure("开始游戏点击后状态无法连续确认，禁止重试或继续")

    def _require_navigation_target(self, label: str, timeout_seconds: float) -> _Target:
        started = self.runtime.monotonic()
        previous = previous_welfare = None
        rank_frames = shop_frames = 0
        for _ in range(int(timeout_seconds * 2) + 2):
            if self.runtime.monotonic() - started > timeout_seconds:
                break
            frame = self._read(label)
            if self.runtime.monotonic() - started > timeout_seconds:
                break
            if frame is None:
                previous = previous_welfare = None
                rank_frames = shop_frames = 0
                self.runtime.sleep(1)
                continue
            welfare = self._welfare_target(frame) if label != "开始游戏" else None
            if welfare is not None:
                previous = None
                rank_frames = shop_frames = 0
                if self._stable(previous_welfare, welfare):
                    if self._welfare_returned:
                        raise NavigationFailure("回归福利返回后仍连续存在，禁止重复返回")
                    self._reject_unknown_popup(frame)
                    self.runtime.log("连续两帧确认回归福利，执行一次系统返回")
                    self.runtime.press_back()
                    self._welfare_returned = True
                    previous_welfare = None
                    self.runtime.sleep(2)
                    self._wait_stable("退出回归福利后")
                    self.runtime.sleep(1)
                else:
                    previous_welfare = welfare
                    self.runtime.sleep(.5)
                continue
            previous_welfare = None
            if self._handle_popup(frame, label):
                previous = None
                rank_frames = shop_frames = 0
                continue
            self._reject_unknown_popup(frame)
            current = self._start_target(frame) if label == "开始游戏" else self._entry_target(frame)
            if label == "开始游戏" and self._is_rank_page(frame):
                current = None
            # A verified lobby target outranks unrelated lobby/chat labels.
            if current is not None:
                if self._stable(previous, current):
                    return current
                rank_frames = shop_frames = 0
            else:
                rank_frames = rank_frames + 1 if self._is_rank_page(frame) else 0
                shop_frames = shop_frames + 1 if self._is_shop_page(frame) else 0
                if rank_frames >= 2:
                    raise NavigationFailure("检测到排位赛页面，禁止继续点击")
                if shop_frames >= 2:
                    raise NavigationFailure("检测到商城页面，禁止继续点击")
            previous = current
            self.runtime.sleep(.5 if current else 1)
        raise NavigationFailure(f"等待{label}文字连续确认超时")

    def _require_farm_page(self, timeout_seconds: float) -> UiFrame:
        started = self.runtime.monotonic()
        previous_anchors = set()
        previous_size = None
        previous_harvest = None
        rank_frames = shop_frames = 0
        for _ in range(int(timeout_seconds / .3) + 2):
            if self.runtime.monotonic() - started > timeout_seconds:
                break
            frame = self._read("农场页面")
            if self.runtime.monotonic() - started > timeout_seconds:
                break
            if frame is None:
                previous_anchors = set()
                previous_size = None
                previous_harvest = None
                rank_frames = shop_frames = 0
                self.runtime.sleep(1)
                continue
            if self._handle_popup(frame, "农场页面"):
                previous_anchors = set()
                previous_size = None
                previous_harvest = None
                continue
            # A previously-sent action can leave a reward modal on re-entry.
            # Hand only that explicit, stable modal to the action recovery
            # handler; do not treat arbitrary obstruction as farm readiness.
            harvest_box = locate_harvest_target(frame) if self.allow_harvest_recovery else None
            if harvest_box is not None:
                harvest = _Target(harvest_box, frame.width, frame.height, "点击继续")
                if self._stable(previous_harvest, harvest, .03):
                    self.runtime.log("已发送动作恢复：连续两帧确认收获弹窗，交由收获流程复查")
                    return frame
                previous_harvest = harvest
                previous_anchors = set()
                previous_size = None
                self.runtime.sleep(.3)
                continue
            previous_harvest = None
            self._reject_unknown_popup(frame)
            anchors = farm_anchors(frame)
            size = (frame.width, frame.height)
            if len(anchors) >= 2 and anchors & previous_anchors and previous_size == size:
                self.runtime.log("农场页面文字已连续确认：" + "、".join(sorted(anchors)))
                return frame
            rank_frames = rank_frames + 1 if self._is_rank_page(frame) else 0
            shop_frames = shop_frames + 1 if self._is_shop_page(frame) else 0
            if rank_frames >= 2 or shop_frames >= 2:
                raise NavigationFailure("进入农场后检测到排位赛或商城页面，禁止继续点击")
            previous_anchors = anchors if len(anchors) >= 2 else set()
            previous_size = size
            self.runtime.sleep(.3 if len(anchors) >= 2 else 1)
        raise NavigationFailure("等待农场页面文字锚点连续确认超时")

    def _popup_context(self, frame: UiFrame) -> _Target | None:
        return self._target(frame, POPUP_CONTEXTS, (.10, .08, .90, .90), .70)

    def _rest_context(self, frame: UiFrame) -> _Target | None:
        return self._target(frame, "请您休息一下", (.10, .08, .90, .90), .70)

    def _rest_confirm(self, frame: UiFrame) -> _Target | None:
        if self._rest_context(frame) is None:
            return None
        return self._target(frame, ("确定", "我知道了"), (.30, .45, .70, .90), exact=True)

    def _handle_popup(self, frame: UiFrame, label: str) -> bool:
        if self._rest_context(frame):
            target = self._rest_confirm(frame)
            if target is None:
                raise NavigationFailure("休息提示缺少安全的确认按钮，禁止点击")
            self.runtime.sleep(1)
            fresh = self._read("休息提示二次确认")
            confirmed = self._rest_confirm(fresh) if fresh else None
            if not self._stable(target, confirmed):
                raise NavigationFailure("休息提示未通过连续两帧确认，禁止点击")
            self._count_popup()
            self._tap(confirmed, "关闭休息提示")
        else:
            context = self._popup_context(frame)
            if context is None:
                return False
            first_close = self._safe_close(frame, context.phrase)
            confirmed_close = None
            for _ in range(8):
                self.runtime.sleep(.5)
                fresh = self._read(f"{label}弹窗二次确认")
                confirmed = self._popup_context(fresh) if fresh else None
                if not self._compatible_context(context, confirmed):
                    first_close = None
                    continue
                current_close = self._safe_close(fresh, confirmed.phrase)
                if self._stable(first_close, current_close, .04):
                    confirmed_close = current_close
                    break
                first_close = current_close
            if confirmed_close is None:
                raise NavigationFailure("已识别弹窗，但上下文或安全关闭按钮未通过连续确认")
            self._count_popup()
            self._tap(confirmed_close, "关闭已确认弹窗")
        self.runtime.sleep(2)
        self._wait_stable("关闭弹窗后")
        self.runtime.sleep(1)
        return True

    def _count_popup(self) -> None:
        self._popup_dismissals += 1
        if self._popup_dismissals > 10:
            raise NavigationFailure("连续处理弹窗次数过多，本轮已停止")

    @staticmethod
    def _compatible_context(first: _Target, second: _Target | None) -> bool:
        if second is None or (first.width, first.height) != (second.width, second.height):
            return False
        if first.phrase == second.phrase:
            return EnterFarmAutomation._stable(first, second, .20)
        return first.phrase in CROSS_FRAME_POPUP_CONTEXTS and second.phrase in CROSS_FRAME_POPUP_CONTEXTS

    def _safe_close(self, frame: UiFrame, context: str) -> _Target | None:
        box = self.runtime.find_popup_close()
        if box is None or box.confidence < .74:
            return None
        viewport_width = min(frame.width, round(frame.height * 16 / 9))
        inset = (frame.width - viewport_width) / 2
        x = (box.center_x - inset) / viewport_width
        y = box.center_y / frame.height
        left, right = (.83, .89) if context == "更新公告" else (.90, .95)
        y_min = .10 if inset > 0 else .14
        # Template centres use whole screenshot pixels. The repository's
        # verified 1280x720 popup closes at y=99, 1.8px above the fractional
        # 14% boundary. Allow only this fixed two-pixel quantization margin;
        # context, confidence, repeated evidence and all other limits remain.
        y_min -= 2 / frame.height
        if not (
            left <= x <= right and y_min <= y <= .34
            and 0 <= box.left < box.right <= frame.width
            and 0 <= box.top < box.bottom <= frame.height
            and box.right - box.left <= viewport_width * .12
            and box.bottom - box.top <= frame.height * .16
        ):
            self.runtime.log("忽略安全区域外的关闭图片命中")
            return None
        return _Target(box, frame.width, frame.height, "关闭")

    def _reject_unknown_popup(self, frame: UiFrame) -> None:
        reason = blocking_popup_reason(frame)
        if reason:
            raise NavigationFailure(f"检测到遮挡页面：{reason}；未确认安全操作，停止本轮")
        # Unrecognized modal choices must not be answered, even when the lobby
        # or start button remains visible through their backdrop.
        choice = self._target(
            frame, ("确定", "确认", "取消", "同意", "立即购买", "立即领取"),
            (.20, .25, .85, .90), max_width=.30, exact=True,
        )
        if choice:
            raise NavigationFailure(f"检测到未知遮挡按钮“{choice.phrase}”，禁止点击或穿透页面")

    def _is_rank_page(self, frame: UiFrame) -> bool:
        strong = self._target(frame, RANK_STRONG, (.02, .05, .98, .86), .55, .20)
        secondary = self._target(frame, RANK_SECONDARY, (.02, .02, .98, .98), .55, .20)
        return bool(strong and secondary and strong.box != secondary.box)

    def _is_shop_page(self, frame: UiFrame) -> bool:
        title = self._target(frame, "商城", (.03, .04, .90, .92), .30, .18)
        secondary = self._target(frame, ("推荐", "新品", "促销", "商品", "夺宝"), (.03, .06, .92, .94), .45, .18)
        return bool(title and secondary and title.box != secondary.box)
