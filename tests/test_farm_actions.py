import unittest
from collections import deque
from datetime import datetime, timedelta
from types import SimpleNamespace

from farm_actions import (
    FarmActionAutomation, FarmActionError, OcrBox, UiFrame, blocking_popup_reason,
    farm_anchors, find_text_target, locate_harvest_target, locate_one_click_target,
    merge_harvest_readings, movement_profile, resolve_farmland_consensus,
)


START = datetime(2026, 9, 22, 12, 0)


def box(text, x, y, width=100, height=30):
    return OcrBox(text, x - width // 2, y - height // 2,
                  x + width // 2, y + height // 2)


def farm(button_x=820):
    return UiFrame(1280, 720, (box("张三的农场", 280, 60, 150),
                               box("仓库", 1160, 150, 60),
                               box("流光加速", 820, 400),
                               box("一键务农", button_x, 470)))


def modal(x=640):
    return UiFrame(1280, 720, (box("恭喜您获得", x, 230, 220),
                               box("农场经验+20", x, 350, 140),
                               box("点击继续", x, 590, 140)))


def larger_farm():
    return UiFrame(2560, 1440, tuple(
        OcrBox(item.text, item.left * 2, item.top * 2, item.right * 2, item.bottom * 2)
        for item in farm().boxes
    ))


def reading(kind="planted", maturity=None):
    return SimpleNamespace(kind=kind, raw_text=kind, observed_at=START,
                           maturity_at=maturity or (START + timedelta(hours=3)
                                                    if kind == "planted" else None))


class FakeRuntime:
    def __init__(self, frames=None, readings=None, rewards=None):
        self.frames = deque(frames or [farm()])
        self.readings = deque(readings or [reading()])
        self.rewards = deque(rewards or [{"exp": 20, "crops": {"白菜": 3}}])
        self.seconds = 0.0
        self.events = []
        self.fail_tap = False

    @staticmethod
    def _next(values):
        return values.popleft() if len(values) > 1 else values[0]

    def read_ui(self):
        frame = self._next(self.frames)
        self.events.append(("read_ui", self.now(), frame))
        return frame

    def read_farmland(self):
        self.events.append(("read_farmland", self.now()))
        return self._next(self.readings)

    def read_harvest_info(self):
        return self._next(self.rewards)

    def tap(self, x, y):
        self.events.append(("tap", self.now(), x, y))
        if self.fail_tap:
            raise RuntimeError("ADB disconnected after send")

    def swipe(self, gesture):
        self.events.append(("swipe", self.now(), gesture))

    def wait_screen_stable(self, label, timeout_seconds=4):
        self.events.append(("stable", self.now(), label))
        return True

    def sleep(self, seconds):
        self.seconds += seconds

    def now(self):
        return START + timedelta(seconds=self.seconds)

    def monotonic(self):
        return self.seconds

    def log(self, message):
        self.events.append(("log", message))

    def actions(self, name):
        return [event for event in self.events if event[0] == name]


class FakeGuard:
    def __init__(self):
        self.sent = False
        self.harvest_sent = False
        self.accepted = []
        self.harvest_accepted = []
        self.harvest_observed = 0

    def before_tap(self, target):
        if self.sent:
            return False
        self.sent = True
        return True

    def after_tap_accepted(self, at):
        self.accepted.append(at)

    def before_maturity_harvest(self, target):
        if self.harvest_sent:
            return False
        self.harvest_sent = True
        return True

    def after_maturity_harvest_accepted(self, at):
        self.harvest_accepted.append(at)

    def on_harvest_observed(self):
        self.harvest_observed += 1


class OcrSafetyTests(unittest.TestCase):
    def test_farm_anchors_need_independent_boxes(self):
        frame = UiFrame(1280, 720, (box("一键务农 农场升级 流光加速", 820, 470, 250),))
        self.assertEqual(len(farm_anchors(frame)), 1)
        self.assertGreaterEqual(len(farm_anchors(farm())), 2)

    def test_chat_line_cannot_prove_farm(self):
        frame = UiFrame(1280, 720, (box("仓库 社交 百科 一键务农", 250, 610, 350),))
        self.assertFalse(farm_anchors(frame))

    def test_portrait_and_out_of_bounds_are_rejected(self):
        self.assertFalse(farm_anchors(UiFrame(720, 1280, farm().boxes)))
        self.assertIsNone(find_text_target(UiFrame(1280, 720, (OcrBox("确定", -20, 30, 200, 90),)), "确定"))

    def test_adjacent_fragments_can_form_button(self):
        frame = UiFrame(1280, 720, (box("一键", 785, 470, 55), box("务农", 845, 470, 55)))
        self.assertIsNotNone(locate_one_click_target(frame))

    def test_separated_fragments_cannot_form_button(self):
        frame = UiFrame(1280, 720, (box("一键", 690, 380, 55), box("务农", 940, 550, 55)))
        self.assertIsNone(locate_one_click_target(frame))

    def test_similar_button_requires_action_context(self):
        partial = UiFrame(1280, 720, (box("一健务衣", 820, 470),))
        self.assertIsNone(locate_one_click_target(partial))
        with_context = UiFrame(1280, 720, partial.boxes + (box("流光加速", 820, 400),))
        self.assertEqual(locate_one_click_target(with_context).text, "一键务农")

    def test_dash_button_alias_is_phrase_specific(self):
        frame = UiFrame(1280, 720, (box("—键务农", 820, 470),))
        self.assertIsNotNone(locate_one_click_target(frame))

    def test_one_click_outside_safe_rectangle_is_rejected(self):
        self.assertIsNone(locate_one_click_target(farm(button_x=1100)))

    def test_continue_alone_never_proves_harvest(self):
        frame = UiFrame(1280, 720, (box("点击继续", 640, 590),))
        self.assertIsNone(locate_harvest_target(frame))
        self.assertIsNotNone(blocking_popup_reason(frame))

    def test_legacy_popup_requires_reward_above_continue(self):
        frame = UiFrame(1280, 720, (box("经验 +20", 640, 400), box("点击继续", 640, 590)))
        self.assertIsNotNone(locate_harvest_target(frame))
        invalid = UiFrame(1280, 720, (box("经验 +20", 640, 590), box("点击继续", 640, 400)))
        self.assertIsNone(locate_harvest_target(invalid))

    def test_split_modal_text_blocks_farm_even_without_target(self):
        frame = UiFrame(1280, 720, farm().boxes + (box("恭喜您", 100, 300), box("获得", 1100, 300)))
        self.assertIsNotNone(blocking_popup_reason(frame))
        self.assertIsNone(locate_one_click_target(frame))


class MovementTests(unittest.TestCase):
    def test_verified_profiles_match_apk(self):
        expected = {
            (1280, 720): (160, 486, 60, 313, 286),
            (2400, 1080): (430, 755, 305, 538, 555),
            (2560, 1600): (385, 1165, 200, 844, 869),
            (2560, 1564): (385, 1138, 200, 825, 849),
        }
        for size, coords in expected.items():
            with self.subTest(size=size):
                profile = movement_profile(*size)
                first = profile.spawn_to_statue
                self.assertEqual((first.start_x, first.start_y, first.end_x, first.end_y,
                                  profile.statue_to_farmland.end_y), coords)
                self.assertEqual(first.duration_ms, 1500)
                self.assertEqual(profile.statue_to_farmland.duration_ms, 1200)

    def test_nearest_aspect_profile_scales_both_axes(self):
        profile = movement_profile(1920, 1200)
        self.assertEqual(profile.spawn_to_statue.start_x, int(385 * .75))
        self.assertEqual(profile.spawn_to_statue.start_y, int(1165 * .75))
        self.assertEqual(profile.statue_to_farmland.end_y, int(869 * .75))

    def test_invalid_dimensions_never_move(self):
        for width, height in [(0, 0), (720, 1280), (-1, 720), (100, 0)]:
            with self.assertRaises(FarmActionError):
                movement_profile(width, height)


class ConsensusTests(unittest.TestCase):
    def test_unknown_frames_abstain(self):
        planted = reading()
        self.assertIs(resolve_farmland_consensus([planted, reading("unknown"), planted]), planted)

    def test_planted_requires_agreeing_calendar_minute(self):
        self.assertIsNone(resolve_farmland_consensus([
            reading(maturity=START), reading(maturity=START + timedelta(days=1)), reading("unknown")]))
        self.assertIsNotNone(resolve_farmland_consensus([
            reading(maturity=START), reading(maturity=START + timedelta(seconds=30))]))

    def test_latest_two_mature_supersede_previous_planted_tie(self):
        mature = reading("mature")
        self.assertIs(resolve_farmland_consensus([reading(), reading(), mature, mature]), mature)

    def test_old_mature_majority_cannot_trigger_followup(self):
        self.assertIsNone(resolve_farmland_consensus([reading("mature"), reading("mature"), reading()]))

    def test_planted_cannot_override_fresh_unknown_or_mature(self):
        for latest in [reading("unknown"), reading("mature")]:
            self.assertIsNone(resolve_farmland_consensus([reading(), reading(), latest]))

    def test_equal_conflicting_times_do_not_form_consensus(self):
        one = reading(maturity=START)
        other = reading(maturity=START + timedelta(minutes=1))
        self.assertIsNone(resolve_farmland_consensus([one, other, one, other]))

    def test_rewards_are_maxima_not_sum_of_repeated_ocr(self):
        self.assertEqual(merge_harvest_readings([
            {"exp": 20, "crops": {"白菜": 3}},
            {"exp": 20, "crops": {"白菜": 3, "萝卜": 2}},
            {"exp": 10, "crops": {"白菜": 1}},
        ]), {"exp": 20, "crops": {"白菜": 3, "萝卜": 2}})


class FarmFlowTests(unittest.TestCase):
    def test_normal_watering_accepts_only_after_three_absent_frames(self):
        runtime, guard = FakeRuntime(), FakeGuard()
        result = FarmActionAutomation(runtime, guard).run()
        self.assertFalse(result.harvested)
        self.assertEqual(result.farmland_state.kind, "planted")
        self.assertEqual(len(runtime.actions("tap")), 1)
        self.assertEqual(len(runtime.actions("swipe")), 2)
        self.assertEqual(guard.accepted, [result.first_water_at])
        events = [event[0] for event in runtime.events]
        first_tap = events.index("tap")
        next_swipe = events.index("swipe", first_tap)
        # Three frames prove modal absence, then one fresh frame validates
        # the movement profile immediately before the next swipe.
        self.assertEqual(events[first_tap:next_swipe].count("read_ui"), 4)

    def test_farm_confirmation_failure_never_moves_or_taps(self):
        runtime = FakeRuntime([UiFrame(1280, 720, (box("仓库", 1160, 150),))])
        with self.assertRaises(FarmActionError):
            FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertFalse(runtime.actions("tap"))
        self.assertFalse(runtime.actions("swipe"))

    def test_farm_confirmation_requires_consecutive_frames(self):
        runtime = FakeRuntime([farm(), None, farm(), farm()])
        FarmActionAutomation(runtime, FakeGuard()).run()
        events = [event[0] for event in runtime.events]
        self.assertEqual(events[:events.index("swipe")].count("read_ui"), 4)

    def test_farm_confirmation_needs_two_frames_with_the_same_dimensions(self):
        runtime = FakeRuntime([farm(), larger_farm(), larger_farm()])
        FarmActionAutomation(runtime, FakeGuard()).run()
        events = [event[0] for event in runtime.events]
        self.assertEqual(events[:events.index("swipe")].count("read_ui"), 3)
        self.assertEqual(runtime.actions("swipe")[0][2].start_x, 320)

    def test_size_change_before_primary_action_rejects_old_profile(self):
        runtime, guard = FakeRuntime([farm(), farm(), larger_farm()]), FakeGuard()
        with self.assertRaisesRegex(FarmActionError, "尺寸已变化"):
            FarmActionAutomation(runtime, guard).run()
        self.assertFalse(runtime.actions("tap"))
        self.assertFalse(guard.sent)
        self.assertEqual(1, len(runtime.actions("swipe")))

    def test_size_change_after_modal_absence_prevents_land_swipe(self):
        runtime, guard = FakeRuntime([farm()] * 6 + [larger_farm()]), FakeGuard()
        with self.assertRaisesRegex(FarmActionError, "尺寸已变化"):
            FarmActionAutomation(runtime, guard).run()
        self.assertTrue(guard.sent)
        self.assertTrue(guard.accepted)
        self.assertEqual(1, len(runtime.actions("swipe")))

    def test_recovery_size_change_after_stability_sampling_prevents_land_swipe(self):
        runtime, guard = FakeRuntime([farm()] * 5 + [larger_farm()]), FakeGuard()
        guard.sent = True
        with self.assertRaisesRegex(FarmActionError, "尺寸已变化"):
            FarmActionAutomation(runtime, guard, resume_after_action_at=START).run()
        self.assertFalse(runtime.actions("tap"))
        self.assertEqual(1, len(runtime.actions("swipe")))

    def test_wait_to_target_and_relocate_button(self):
        runtime = FakeRuntime([farm(), farm(), farm(820), farm(900)])
        target_at = START + timedelta(seconds=20)
        result = FarmActionAutomation(runtime, FakeGuard(), not_before=target_at).run()
        tap = runtime.actions("tap")[0]
        self.assertEqual(tap[1], target_at)
        self.assertEqual(tap[2], 900)
        self.assertLess(result.ready_at, target_at)
        reads_before_tap = [event for event in runtime.events[:runtime.events.index(tap)] if event[0] == "read_ui"]
        self.assertEqual(reads_before_tap[-1][1], target_at)

    def test_button_disappearing_during_wait_never_taps_stale_position(self):
        no_button = UiFrame(1280, 720, farm().boxes[:2])
        runtime = FakeRuntime([farm(), farm(), farm(), no_button])
        with self.assertRaises(FarmActionError):
            FarmActionAutomation(runtime, FakeGuard(), not_before=START + timedelta(seconds=2)).run()
        self.assertFalse(runtime.actions("tap"))

    def test_unknown_popup_cannot_be_treated_as_absent(self):
        runtime = FakeRuntime([farm(), farm(), farm(), UiFrame(1280, 720, ())])
        guard = FakeGuard()
        with self.assertRaises(FarmActionError):
            FarmActionAutomation(runtime, guard).run()
        self.assertEqual(len(runtime.actions("swipe")), 1)
        self.assertTrue(guard.sent)
        self.assertFalse(guard.accepted)

    def test_partial_popup_cannot_be_blindly_dismissed(self):
        partial = UiFrame(1280, 720, (box("点击继续", 640, 590),))
        runtime = FakeRuntime([farm(), farm(), farm(), partial])
        with self.assertRaises(FarmActionError):
            FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertEqual(len(runtime.actions("tap")), 1)
        self.assertEqual(len(runtime.actions("swipe")), 1)

    def test_harvest_modal_is_recorded_then_closed_with_fresh_confirmation(self):
        runtime = FakeRuntime([farm(), farm(), farm(), modal(), modal(), modal(), modal(),
                               farm(), farm(), farm(), farm()])
        guard = FakeGuard()
        result = FarmActionAutomation(runtime, guard).run()
        self.assertTrue(result.harvested)
        self.assertEqual(result.harvest_info, {"exp": 20, "crops": {"白菜": 3}})
        self.assertEqual(guard.harvest_observed, 1)
        self.assertEqual(len(runtime.actions("tap")), 2)
        self.assertEqual(runtime.actions("tap")[1][2:], (640, 590))

    def test_harvest_modal_that_self_dismisses_is_not_clicked(self):
        runtime = FakeRuntime([farm(), farm(), farm(), modal(), farm(), farm(),
                               farm(), farm(), farm(), farm()])
        result = FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertTrue(result.harvested)
        self.assertEqual(len(runtime.actions("tap")), 1)

    def test_mature_after_watering_triggers_one_guarded_same_round_harvest(self):
        runtime = FakeRuntime(readings=[reading("mature")] * 3 + [reading()] * 3)
        guard = FakeGuard()
        result = FarmActionAutomation(runtime, guard).run()
        self.assertEqual(result.farmland_state.kind, "planted")
        self.assertEqual(len(runtime.actions("tap")), 2)
        self.assertEqual(len(runtime.actions("swipe")), 4)
        self.assertTrue(guard.harvest_sent)
        self.assertEqual(len(guard.harvest_accepted), 1)
        reverse = runtime.actions("swipe")[2][2]
        self.assertEqual((reverse.start_y, reverse.end_y), (486, 686))

    def test_still_mature_after_one_followup_never_loops(self):
        runtime = FakeRuntime(readings=[reading("mature")])
        with self.assertRaisesRegex(FarmActionError, "仍显示已成熟"):
            FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertEqual(len(runtime.actions("tap")), 2)

    def test_five_unknown_land_frames_return_unknown_without_extra_action(self):
        runtime = FakeRuntime(readings=[reading("unknown")])
        result = FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertEqual(result.farmland_state.kind, "unknown")
        self.assertEqual(len(runtime.actions("read_farmland")), 5)
        self.assertEqual(len(runtime.actions("tap")), 1)

    def test_old_mature_frames_followed_by_unknown_cannot_cause_harvest(self):
        runtime = FakeRuntime(readings=[reading("mature"), reading("mature"),
                                        reading("unknown"), reading("unknown"), reading("unknown")])
        result = FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertEqual(result.farmland_state.kind, "unknown")
        self.assertEqual(len(runtime.actions("tap")), 1)

    def test_sent_action_recovery_observes_without_watering_again(self):
        runtime, guard = FakeRuntime(), FakeGuard()
        guard.sent = True
        sent_at = START - timedelta(seconds=10)
        result = FarmActionAutomation(runtime, guard, resume_after_action_at=sent_at).run()
        self.assertTrue(result.recovered_action)
        self.assertEqual(result.first_water_at, sent_at)
        self.assertFalse(runtime.actions("tap"))
        self.assertFalse(guard.accepted)

    def test_recovery_retains_direct_harvest_evidence(self):
        runtime = FakeRuntime([modal(), modal(), modal(), modal(), farm(), farm(), farm(), farm()])
        result = FarmActionAutomation(runtime, FakeGuard(), resume_after_action_at=START).run()
        self.assertTrue(result.harvested)
        self.assertEqual(len(runtime.actions("tap")), 1)
        self.assertEqual(runtime.actions("tap")[0][2:], (640, 590))

    def test_guard_blocks_second_send_even_after_uncertain_adb_error(self):
        guard = FakeGuard()
        runtime = FakeRuntime()
        runtime.fail_tap = True
        with self.assertRaises(RuntimeError):
            FarmActionAutomation(runtime, guard).run()
        self.assertTrue(guard.sent)
        self.assertFalse(guard.accepted)
        later = FakeRuntime()
        with self.assertRaises(FarmActionError):
            FarmActionAutomation(later, guard).run()
        self.assertFalse(later.actions("tap"))

    def test_rest_popup_requires_two_stable_frames(self):
        rest = UiFrame(1280, 720, (box("请您休息一下", 640, 300, 220), box("确定", 640, 450)))
        runtime = FakeRuntime([rest, rest, farm(), farm(), farm()])
        FarmActionAutomation(runtime, FakeGuard()).run()
        self.assertEqual(runtime.actions("tap")[0][2:], (640, 450))
        self.assertEqual(len(runtime.actions("tap")), 2)

    def test_incomplete_rest_popup_and_unlocated_close_fail_safely(self):
        frames = [UiFrame(1280, 720, (box("确定", 640, 450),)),
                  UiFrame(1280, 720, (box("活动公告", 640, 200),))]
        for frame in frames:
            with self.subTest(frame=frame):
                runtime = FakeRuntime([frame])
                with self.assertRaises(FarmActionError):
                    FarmActionAutomation(runtime, FakeGuard()).run()
                self.assertFalse(runtime.actions("tap"))
                self.assertFalse(runtime.actions("swipe"))


if __name__ == "__main__":
    unittest.main()
