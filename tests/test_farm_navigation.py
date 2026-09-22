import unittest
from collections import deque

from farm_actions import OcrBox, UiFrame
from farm_navigation import EnterFarmAutomation, NavigationFailure


def box(text, x, y, width=120, height=30, confidence=1.0):
    return OcrBox(text, x - width // 2, y - height // 2,
                  x + width // 2, y + height // 2, confidence)


def frame(*boxes, width=1280, height=720):
    return UiFrame(width, height, tuple(boxes))


START = frame(box("开始游戏", 640, 540))
LOBBY = frame(box("王者农场", 380, 500))
FARM = frame(box("玩家的农场", 300, 60), box("仓库", 1120, 160))
RANK = frame(box("5v5排位赛", 600, 200), box("分路段位", 900, 400))
SHOP = frame(box("商城", 100, 100), box("推荐", 400, 200))
WELFARE = frame(box("回归福利", 260, 60))
NOTICE = frame(box("系统公告", 600, 140))
CLOSE = box("close-template", 1180, 150, 40, 40)
REWARD = frame(box("恭喜您获得", 640, 230, 220), box("农场经验+20", 640, 350, 140),
               box("点击继续", 640, 590, 140))


class FakeRuntime:
    def __init__(self, frames, *, running=False, close_targets=(), stable=True):
        self.frames = deque(frames)
        self.last_frame = None
        self.close_targets = deque(close_targets)
        self.last_close = None
        self.running = running
        self.stable = stable
        self.now = 0.0
        self.events = []
        self.logs = []

    def is_game_running(self):
        return self.running

    def stop_game(self):
        self.events.append(("stop", self.now))

    def launch_game(self):
        self.events.append(("launch", self.now))

    def press_back(self):
        self.events.append(("back", self.now))

    def read_ui(self):
        self.events.append(("read", self.now))
        if self.frames:
            self.last_frame = self.frames.popleft()
        if isinstance(self.last_frame, Exception):
            raise self.last_frame
        return self.last_frame

    def tap(self, x, y):
        self.events.append(("tap", self.now, x, y))

    def wait_screen_stable(self, label, timeout_seconds=4):
        self.events.append(("stable", self.now, label, timeout_seconds))
        return self.stable

    def sleep(self, seconds):
        self.now += seconds

    def monotonic(self):
        return self.now

    def log(self, text):
        self.logs.append(text)

    def find_popup_close(self):
        self.events.append(("close-template", self.now))
        if self.close_targets:
            self.last_close = self.close_targets.popleft()
        return self.last_close

    def find_start_target(self):
        raise AssertionError("A start-template match alone must not authorize a tap")

    @property
    def taps(self):
        return [event for event in self.events if event[0] == "tap"]


class FarmNavigationTests(unittest.TestCase):
    def happy_frames(self):
        return [START, START, LOBBY, LOBBY, LOBBY, LOBBY, FARM, FARM]

    def test_clean_launch_waits_for_start_and_returns_confirmed_farm(self):
        runtime = FakeRuntime(self.happy_frames(), running=True)
        result = EnterFarmAutomation(runtime).run()
        self.assertEqual(FARM, result)
        self.assertEqual(["stop", "launch"], [event[0] for event in runtime.events[:2]])
        self.assertGreaterEqual(runtime.taps[0][1], 15.5)
        self.assertEqual([(640, 540), (380, 500)], [event[2:] for event in runtime.taps])

    def test_does_not_force_stop_when_game_is_not_running(self):
        runtime = FakeRuntime(self.happy_frames())
        EnterFarmAutomation(runtime).run()
        self.assertNotIn("stop", [event[0] for event in runtime.events])

    def test_one_swallowed_start_click_allows_only_one_controlled_retry(self):
        runtime = FakeRuntime([START] * 4 + [LOBBY] * 4 + [FARM] * 2)
        EnterFarmAutomation(runtime).run()
        self.assertEqual(3, len(runtime.taps))
        self.assertEqual((640, 540), runtime.taps[1][2:])
        self.assertGreaterEqual(runtime.taps[1][1] - runtime.taps[0][1], 8)

    def test_surviving_second_start_click_fails_without_third_click(self):
        runtime = FakeRuntime([START] * 6)
        with self.assertRaisesRegex(NavigationFailure, "受控重试后"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(2, len(runtime.taps))

    def test_start_loading_artwork_outside_interactive_band_is_never_clicked(self):
        fake_start = frame(box("开始游戏", 640, 615))
        runtime = FakeRuntime([fake_start])
        with self.assertRaisesRegex(NavigationFailure, "开始游戏.*超时"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)
        self.assertLess(runtime.now, 75)

    def test_start_coordinates_must_agree_in_consecutive_frames(self):
        moved_start = frame(box("开始游戏", 800, 540))
        runtime = FakeRuntime([START, moved_start, START, START] + [LOBBY] * 4 + [FARM] * 2)
        EnterFarmAutomation(runtime).run()
        reads_before_tap = next(index for index, event in enumerate(runtime.events) if event[0] == "tap")
        self.assertEqual(4, sum(event[0] == "read" for event in runtime.events[:reads_before_tap]))

    def test_missing_or_empty_ocr_after_start_does_not_prove_exit_or_trigger_retry(self):
        for unreadable in (None, frame(), RuntimeError("temporary OCR failure")):
            with self.subTest(unreadable=unreadable):
                runtime = FakeRuntime([START, START] + [unreadable] * 8)
                with self.assertRaisesRegex(NavigationFailure, "状态无法连续确认"):
                    EnterFarmAutomation(runtime).run()
                self.assertEqual(1, len(runtime.taps))

    def test_start_exit_requires_consecutive_frames(self):
        runtime = FakeRuntime([START, START, LOBBY, None, LOBBY, LOBBY, LOBBY, LOBBY, FARM, FARM])
        EnterFarmAutomation(runtime).run()
        self.assertEqual(2, len(runtime.taps))

    def test_rejects_legal_agreement_even_if_start_button_is_visible(self):
        agreement = frame(*START.boxes, box("游戏许可及服务协议", 640, 300, 320))
        runtime = FakeRuntime([agreement])
        with self.assertRaisesRegex(NavigationFailure, "手动选择"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)

    def test_rejects_rank_page_after_start_without_any_more_clicks(self):
        runtime = FakeRuntime([START, START, RANK, RANK])
        with self.assertRaisesRegex(NavigationFailure, "排位赛"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(1, len(runtime.taps))

    def test_rank_evidence_with_a_start_label_never_authorizes_start_click(self):
        rank_with_start = frame(*RANK.boxes, *START.boxes)
        runtime = FakeRuntime([rank_with_start, rank_with_start])
        with self.assertRaisesRegex(NavigationFailure, "排位赛"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)

    def test_rejects_shop_page_while_waiting_for_farm_entry(self):
        runtime = FakeRuntime([START, START, LOBBY, LOBBY, SHOP, SHOP])
        with self.assertRaisesRegex(NavigationFailure, "商城"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(1, len(runtime.taps))

    def test_real_lobby_entry_has_priority_over_chat_and_lobby_shop_words(self):
        lobby = frame(*LOBBY.boxes, box("召集5v5排位赛", 650, 650, 250),
                      box("商城", 900, 300), box("推荐", 1050, 350), box("赛季", 1050, 500))
        runtime = FakeRuntime([START, START] + [lobby] * 4 + [FARM] * 2)
        EnterFarmAutomation(runtime).run()
        self.assertEqual(2, len(runtime.taps))

    def test_entry_outside_safe_area_is_never_clicked(self):
        wrong_entry = frame(box("王者农场", 1100, 650))
        runtime = FakeRuntime([START, START, LOBBY, LOBBY, wrong_entry])
        with self.assertRaisesRegex(NavigationFailure, "入口.*超时"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(1, len(runtime.taps))

    def test_portrait_frame_cannot_authorize_landscape_coordinates(self):
        portrait = frame(box("开始游戏", 400, 700), width=720, height=1280)
        runtime = FakeRuntime([portrait, START, START] + [LOBBY] * 4 + [FARM] * 2)
        EnterFarmAutomation(runtime).run()
        self.assertTrue(any("切换横屏" in text for text in runtime.logs))

    def test_known_popup_requires_safe_template_and_two_context_frames(self):
        runtime = FakeRuntime([NOTICE, NOTICE] + self.happy_frames(), close_targets=[CLOSE, CLOSE])
        EnterFarmAutomation(runtime).run()
        self.assertEqual((1180, 150), runtime.taps[0][2:])
        self.assertEqual(3, len(runtime.taps))

    def test_compatible_popup_footer_and_title_can_corroborate_each_other(self):
        footer = frame(box("今日内不再弹出", 600, 550, 220))
        runtime = FakeRuntime([NOTICE, footer] + self.happy_frames(), close_targets=[CLOSE, CLOSE])
        EnterFarmAutomation(runtime).run()
        self.assertEqual((1180, 150), runtime.taps[0][2:])

    def test_close_hit_without_strong_popup_text_cannot_override_lobby_entry(self):
        runtime = FakeRuntime(self.happy_frames(), close_targets=[CLOSE, CLOSE])
        EnterFarmAutomation(runtime).run()
        self.assertNotIn("close-template", [event[0] for event in runtime.events])

    def test_unsafe_close_coordinates_are_never_used(self):
        for bad_close in (box("x", 640, 120, 30, 30), box("x", 1180, 600, 30, 30),
                          box("x", 1180, 150, 40, 40, .5)):
            with self.subTest(close=bad_close):
                runtime = FakeRuntime([NOTICE] * 9, close_targets=[bad_close] * 9)
                with self.assertRaisesRegex(NavigationFailure, "安全关闭按钮"):
                    EnterFarmAutomation(runtime).run()
                self.assertEqual([], runtime.taps)

    def test_update_notice_uses_its_narrower_close_band(self):
        update = frame(box("更新公告", 600, 140))
        update_close = box("x", 1100, 150, 40, 40)
        runtime = FakeRuntime([update, update] + self.happy_frames(), close_targets=[update_close] * 2)
        EnterFarmAutomation(runtime).run()
        self.assertEqual((1100, 150), runtime.taps[0][2:])

    def test_ultrawide_close_is_checked_against_centered_game_viewport(self):
        notice = frame(box("系统公告", 1000, 220), width=2400, height=1080)
        correct = box("x", 2016, 180, 60, 60)
        runtime = FakeRuntime([notice, notice] + self.happy_frames(), close_targets=[correct] * 2)
        EnterFarmAutomation(runtime).run()
        self.assertEqual((2016, 180), runtime.taps[0][2:])

    def test_disappearing_popup_context_does_not_leave_stale_close_tap(self):
        runtime = FakeRuntime([NOTICE] + [START] * 8, close_targets=[CLOSE] * 9)
        with self.assertRaisesRegex(NavigationFailure, "连续确认"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)

    def test_legal_consent_during_popup_confirmation_prevents_close_click(self):
        legal = frame(box("王者荣耀隐私保护指引", 600, 300, 400))
        runtime = FakeRuntime([NOTICE, legal], close_targets=[CLOSE])
        with self.assertRaisesRegex(NavigationFailure, "手动选择"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)

    def test_unknown_modal_cannot_be_clicked_through_to_start(self):
        obstruction = frame(*START.boxes, box("取消", 640, 380))
        runtime = FakeRuntime([obstruction])
        with self.assertRaisesRegex(NavigationFailure, "遮挡"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)

    def test_rest_reminder_needs_specific_context_and_two_confirm_button_frames(self):
        reminder = frame(box("请您休息一下", 640, 280, 250), box("我知道了", 640, 440))
        runtime = FakeRuntime([reminder, reminder] + self.happy_frames())
        EnterFarmAutomation(runtime).run()
        self.assertEqual((640, 440), runtime.taps[0][2:])

    def test_rest_reminder_without_safe_button_fails(self):
        reminder = frame(box("请您休息一下", 640, 280, 250), box("确定", 1100, 440))
        runtime = FakeRuntime([reminder])
        with self.assertRaisesRegex(NavigationFailure, "安全的确认按钮"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual([], runtime.taps)

    def test_return_welfare_uses_one_back_after_two_frames(self):
        runtime = FakeRuntime([START, START, LOBBY, LOBBY, WELFARE, WELFARE,
                               LOBBY, LOBBY, FARM, FARM])
        EnterFarmAutomation(runtime).run()
        self.assertEqual(1, sum(event[0] == "back" for event in runtime.events))
        self.assertEqual(2, len(runtime.taps))

    def test_persistent_welfare_stops_without_second_back(self):
        runtime = FakeRuntime([START, START, LOBBY, LOBBY] + [WELFARE] * 4)
        with self.assertRaisesRegex(NavigationFailure, "禁止重复返回"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(1, sum(event[0] == "back" for event in runtime.events))

    def test_single_welfare_frame_does_not_trigger_back(self):
        runtime = FakeRuntime([START, START, LOBBY, LOBBY, WELFARE, LOBBY, LOBBY, FARM, FARM])
        EnterFarmAutomation(runtime).run()
        self.assertNotIn("back", [event[0] for event in runtime.events])

    def test_farm_requires_two_independent_anchors_and_two_frames(self):
        one_anchor = frame(box("玩家的农场", 300, 60))
        runtime = FakeRuntime([START] * 2 + [LOBBY] * 4 + [FARM, one_anchor, FARM, FARM])
        EnterFarmAutomation(runtime).run()
        self.assertEqual(0, len(runtime.frames))

    def test_farm_labels_inside_one_chat_box_cannot_prove_farm(self):
        chat = frame(box("玩家的农场 仓库 社交 种植", 600, 650, 500))
        runtime = FakeRuntime([START] * 2 + [LOBBY] * 4 + [chat])
        with self.assertRaisesRegex(NavigationFailure, "农场页面文字锚点"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(2, len(runtime.taps))

    def test_unstable_animation_still_requires_ocr_farm_confirmation(self):
        runtime = FakeRuntime(self.happy_frames(), stable=False)
        self.assertEqual(FARM, EnterFarmAutomation(runtime).run())
        self.assertTrue(any("仍有动画" in text for text in runtime.logs))

    def test_sent_action_recovery_hands_stable_reward_popup_to_actions(self):
        runtime = FakeRuntime([START] * 2 + [LOBBY] * 4 + [REWARD] * 2)
        result = EnterFarmAutomation(runtime, allow_harvest_recovery=True).run()
        self.assertEqual(REWARD, result)
        self.assertEqual(2, len(runtime.taps))
        self.assertTrue(any("交由收获流程" in text for text in runtime.logs))

    def test_reward_popup_is_not_a_normal_new_round_entry(self):
        runtime = FakeRuntime([START] * 2 + [LOBBY] * 4 + [REWARD] * 2)
        with self.assertRaisesRegex(NavigationFailure, "收获弹窗"):
            EnterFarmAutomation(runtime).run()
        self.assertEqual(2, len(runtime.taps))

    def test_recovery_reward_confirmation_requires_consecutive_frames(self):
        runtime = FakeRuntime([START] * 2 + [LOBBY] * 4 + [REWARD, None, REWARD, REWARD])
        result = EnterFarmAutomation(runtime, allow_harvest_recovery=True).run()
        self.assertEqual(REWARD, result)
        self.assertEqual(0, len(runtime.frames))

    def test_recovery_mode_does_not_accept_partial_or_unknown_modal(self):
        for partial in (frame(box("点击继续", 640, 590)), frame(box("确认", 640, 500))):
            with self.subTest(partial=partial):
                runtime = FakeRuntime([START] * 2 + [LOBBY] * 4 + [partial] * 2)
                with self.assertRaises(NavigationFailure):
                    EnterFarmAutomation(runtime, allow_harvest_recovery=True).run()
                self.assertEqual(2, len(runtime.taps))


if __name__ == "__main__":
    unittest.main()
