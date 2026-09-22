"""Offline acceptance against the repository's real screenshots; never uses ADB.

All real OCR results are shared across assertions, with one engine per run.
The fixture suite requires the project's declared OCR/image dependencies.
"""
from datetime import datetime, timedelta
from contextlib import redirect_stdout
from importlib.util import find_spec
import io
from pathlib import Path
import unittest


DEPENDENCIES_AVAILABLE = all(find_spec(name) is not None for name in
                             ("rapidocr_onnxruntime", "cv2", "numpy"))


@unittest.skipUnless(DEPENDENCIES_AVAILABLE, "需要项目OCR依赖；不会连接设备")
class RealScreenshotOcrTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import cv2
        import numpy as np
        from rapidocr_onnxruntime import RapidOCR
        from adb_runtime import maturity_roi, ocr_ui_frame
        from farm_schedule import parse_farmland

        ocr = RapidOCR()
        cls.frames = {}
        cls.raw_calls = {}
        directory = Path(__file__).resolve().parents[1] / "assets" / "screenshots"
        for name in ("nongchangzhuye", "chufayijianwunongnongchangzhuye", "juesezhanzaitudishang",
                     "wutanchuangdating", "wutanchuangzhuye", "youtanchuangdating"):
            image = cv2.imdecode(np.fromfile(directory / (name + ".png"), dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None:
                raise AssertionError(f"fixture cannot be decoded: {name}")
            calls = []

            def recorded_ocr(pixels):
                output = ocr(pixels)
                calls.append(output[0])
                return output

            cls.frames[name] = ocr_ui_frame(image, recorded_ocr)
            cls.raw_calls[name] = calls
            if name == "juesezhanzaitudishang":
                lines, _ = ocr(maturity_roi(image))
                cls.maturity_text = " ".join(item[1] for item in lines or [])
                cls.observed_at = datetime(2026, 9, 22, 12, 0)
                cls.maturity = parse_farmland(cls.maturity_text, cls.observed_at)
        # This calls only the image matcher, never the ADB runtime or launcher.
        import wzry_auto
        from farm_actions import OcrBox
        with redirect_stdout(io.StringIO()):
            match = wzry_auto.find_any_template(
                ["close_popup.png", "close_popup_event.png"],
                str(directory / "youtanchuangdating.png"),
            )
        if match is None:
            raise AssertionError("Known popup fixture must produce its real close-template match")
        width, height = match["scale_size"]
        cls.popup_close = OcrBox(match["template"], match["x"] - width // 2,
                                 match["y"] - height // 2, match["x"] + width // 2,
                                 match["y"] + height // 2, match["score"])

    def test_spawn_screenshot_has_multiple_independent_farm_anchors(self):
        from farm_actions import blocking_popup_reason, farm_anchors, locate_one_click_target
        frame = self.frames["nongchangzhuye"]
        self.assertGreaterEqual(len(farm_anchors(frame)), 2)
        self.assertIn("的农场", farm_anchors(frame))
        self.assertIsNone(blocking_popup_reason(frame))
        self.assertIsNone(locate_one_click_target(frame))

    def test_statue_screenshot_produces_safe_full_action_label(self):
        from farm_actions import farm_anchors, locate_one_click_target
        frame = self.frames["chufayijianwunongnongchangzhuye"]
        target = locate_one_click_target(frame)
        self.assertGreaterEqual(len(farm_anchors(frame)), 2)
        self.assertIsNotNone(target, frame.raw_text)
        self.assertEqual(target.text, "一键务农")
        self.assertTrue(800 <= target.center_x <= 900, target)
        self.assertTrue(420 <= target.center_y <= 480, target)
        self.assertEqual((frame.width, frame.height), (1280, 720))

    def test_action_crop_does_not_trigger_without_action_context(self):
        self.assertEqual(len(self.raw_calls["nongchangzhuye"]), 1)
        self.assertEqual(len(self.raw_calls["juesezhanzaitudishang"]), 1)
        self.assertLessEqual(len(self.raw_calls["chufayijianwunongnongchangzhuye"]), 2)

    def test_farmland_screenshot_has_farm_evidence_without_farm_action(self):
        from farm_actions import blocking_popup_reason, farm_anchors, locate_one_click_target
        frame = self.frames["juesezhanzaitudishang"]
        self.assertGreaterEqual(len(farm_anchors(frame)), 2)
        self.assertIsNone(blocking_popup_reason(frame))
        self.assertIsNone(locate_one_click_target(frame))

    def test_production_land_roi_reads_and_parses_one_minute_remaining(self):
        self.assertIn("1分钟后成熟", self.maturity_text)
        self.assertEqual(self.maturity.kind, "planted")
        self.assertEqual(self.maturity.maturity_at, self.observed_at + timedelta(minutes=1))
        self.assertEqual(self.maturity.precision_seconds, 0)

    def test_real_start_page_confirms_safe_start_target(self):
        from farm_navigation import EnterFarmAutomation
        runtime = FixtureNavigationRuntime(self.frames["wutanchuangzhuye"])
        navigation = EnterFarmAutomation(runtime)
        target = navigation._require_navigation_target("开始游戏", 2)
        self.assertEqual(target.phrase, "开始游戏")
        self.assertTrue(560 <= target.box.center_x <= 710)
        self.assertTrue(530 <= target.box.center_y <= 590)
        self.assertEqual(runtime.read_count, 2)
        self.assertIsNone(navigation._entry_target(runtime.frame))

    def test_real_lobby_confirms_safe_farm_entry_without_confusing_rank_tile(self):
        from farm_navigation import EnterFarmAutomation
        runtime = FixtureNavigationRuntime(self.frames["wutanchuangdating"])
        navigation = EnterFarmAutomation(runtime)
        target = navigation._require_navigation_target("王者农场入口", 2)
        self.assertEqual(target.phrase, "来农场")
        self.assertTrue(330 <= target.box.center_x <= 480)
        self.assertTrue(450 <= target.box.center_y <= 490)
        self.assertEqual(runtime.read_count, 2)
        self.assertIsNone(navigation._start_target(runtime.frame))
        self.assertIsNone(navigation._popup_context(runtime.frame))
        self.assertFalse(navigation._is_rank_page(runtime.frame))

    def test_real_known_popup_closes_after_context_and_template_confirmation(self):
        from farm_navigation import EnterFarmAutomation
        runtime = FixtureNavigationRuntime(self.frames["youtanchuangdating"], self.popup_close)
        navigation = EnterFarmAutomation(runtime)
        context = navigation._popup_context(runtime.frame)
        self.assertIsNotNone(context)
        self.assertEqual(context.phrase, "今日内不再弹出")
        self.assertGreaterEqual(self.popup_close.confidence, .90)
        self.assertEqual((self.popup_close.center_x, self.popup_close.center_y), (1188, 99))
        # The original template uses an integer centre 1.8px above the 14%
        # boundary; the fixed 2px compatibility margin admits this real cross.
        self.assertIsNotNone(navigation._safe_close(runtime.frame, context.phrase))
        self.assertTrue(navigation._handle_popup(runtime.frame, "王者农场入口"))
        self.assertEqual(runtime.read_count, 1)  # fresh confirmation after the initial frame
        self.assertEqual(runtime.taps, [(1188, 99)])
        self.assertIsNone(navigation._entry_target(runtime.frame))

    def test_known_popup_close_beyond_two_pixel_margin_is_still_rejected(self):
        from dataclasses import replace
        from farm_navigation import EnterFarmAutomation, NavigationFailure
        too_high = replace(self.popup_close, top=self.popup_close.top - 1,
                           bottom=self.popup_close.bottom - 1)
        runtime = FixtureNavigationRuntime(self.frames["youtanchuangdating"], too_high)
        navigation = EnterFarmAutomation(runtime)
        context = navigation._popup_context(runtime.frame)
        self.assertEqual(too_high.center_y, 98)
        self.assertIsNone(navigation._safe_close(runtime.frame, context.phrase))
        with self.assertRaises(NavigationFailure):
            navigation._handle_popup(runtime.frame, "王者农场入口")
        self.assertEqual(runtime.taps, [])


class FixtureNavigationRuntime:
    """Replays already-read evidence; has no connection to any device."""
    def __init__(self, frame, close=None):
        self.frame = frame
        self.close = close
        self.seconds = 0
        self.read_count = 0
        self.taps = []

    def read_ui(self):
        self.read_count += 1
        return self.frame

    def find_popup_close(self):
        return self.close

    def sleep(self, seconds):
        self.seconds += seconds

    def monotonic(self):
        return self.seconds

    def log(self, message):
        pass

    def tap(self, x, y):
        self.taps.append((x, y))

    def wait_screen_stable(self, label, timeout_seconds=4):
        return True


@unittest.skipUnless(DEPENDENCIES_AVAILABLE, "需要项目OCR依赖；不会连接设备")
class ActionCropMappingTests(unittest.TestCase):
    @staticmethod
    def line(text, left, top, right, bottom):
        return ([[left, top], [right, top], [right, bottom], [left, bottom]], text, .99)

    def test_fallback_coordinates_return_to_original_frame(self):
        import numpy as np
        from adb_runtime import ocr_ui_frame
        from farm_actions import locate_one_click_target
        calls = []

        def fake_ocr(image):
            calls.append(image.shape)
            if len(calls) == 1:
                return [self.line("小王的农场", 200, 10, 350, 50),
                        self.line("农场升级", 770, 540, 860, 580),
                        self.line("键务农", 815, 435, 888, 465)], None
            # Crop origin (576, 180), then 2x magnification.
            return [self.line("一键务农", 448, 500, 648, 560)], None

        frame = ocr_ui_frame(np.zeros((720, 1280, 3), dtype=np.uint8), fake_ocr)
        target = locate_one_click_target(frame)
        self.assertEqual(len(calls), 2)
        self.assertEqual((target.left, target.top, target.right, target.bottom), (800, 430, 900, 460))

    def test_unproven_context_never_starts_crop_fallback(self):
        import numpy as np
        from adb_runtime import ocr_ui_frame
        from farm_actions import locate_one_click_target
        calls = []

        def fake_ocr(image):
            calls.append(image.shape)
            return [self.line("键务农", 815, 435, 888, 465)], None

        frame = ocr_ui_frame(np.zeros((720, 1280, 3), dtype=np.uint8), fake_ocr)
        self.assertEqual(len(calls), 1)
        self.assertIsNone(locate_one_click_target(frame))


if __name__ == "__main__":
    unittest.main()
