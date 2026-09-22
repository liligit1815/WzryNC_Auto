"""Offline adapter checks; all ADB calls are replaced before execution."""
import io
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import cv2
import numpy as np

from adb_runtime import AdbFarmRuntime, maturity_roi
from farm_actions import SwipeGesture
import wzry_auto


class ScreenshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "中文截图.png"

    @patch("wzry_auto.subprocess.run")
    def test_fresh_lossless_portrait_is_preserved_without_rotation(self, run):
        original = np.zeros((120, 60, 3), dtype=np.uint8)
        original[10:20, 30:40] = (3, 155, 219)
        _, png = cv2.imencode(".png", original)
        run.return_value = subprocess.CompletedProcess([], 0, png.tobytes(), b"")
        self.assertEqual(str(self.path), wzry_auto.screenshot(self.path))
        np.testing.assert_array_equal(original, wzry_auto.read_image(self.path))
        self.assertEqual(["exec-out", "screencap", "-p"], run.call_args.args[0][-3:])

    @patch("wzry_auto.subprocess.run")
    def test_failure_and_invalid_png_never_return_old_screenshot(self, run):
        self.path.write_bytes(b"old frame")
        for code, data in ((1, b"error"), (0, b""), (0, b"not a PNG"),
                           (0, b"\x89PNG\r\n\x1a\n")):
            with self.subTest(code=code, data=data):
                run.return_value = subprocess.CompletedProcess([], code, data, b"error")
                with self.assertRaises(RuntimeError):
                    wzry_auto.screenshot(self.path)
                self.assertEqual(b"old frame", self.path.read_bytes())

    @patch("wzry_auto.subprocess.run")
    def test_dynamic_default_path_is_resolved_at_capture_time(self, run):
        _, png = cv2.imencode(".png", np.zeros((3, 4, 3), dtype=np.uint8))
        run.return_value = subprocess.CompletedProcess([], 0, png.tobytes(), b"")
        with patch.object(wzry_auto, "SCREENSHOT_PATH", str(self.path)):
            self.assertEqual(str(self.path), wzry_auto.screenshot())


class WakeTests(unittest.TestCase):
    def test_explicit_keyguard_states_ignore_screen_and_occlusion(self):
        self.assertTrue(wzry_auto.keyguard_locked(
            "mAwake=true\nKeyguardServiceDelegate\n  showing=true\n  occluded=true"))
        self.assertFalse(wzry_auto.keyguard_locked(
            "KeyguardServiceDelegate\n  showing=false\n  secure=true"))
        self.assertTrue(wzry_auto.keyguard_locked("mKeyguardShowing=true"))
        self.assertFalse(wzry_auto.keyguard_locked("mShowingLockscreen=false"))
        self.assertIsNone(wzry_auto.keyguard_locked("mAwake=true\nshowing=false"))

    def wake(self, shell):
        with patch.object(wzry_auto, "adb_shell", shell), \
                patch.object(wzry_auto, "_reapply_low_brightness"), \
                patch.object(wzry_auto.time, "sleep"):
            return wzry_auto.wake_and_unlock()

    def test_already_lit_device_is_still_checked_for_keyguard(self):
        states = iter((True, False))
        def command(cmd):
            if cmd == "dumpsys window policy":
                return "KeyguardServiceDelegate\n  showing=" + str(next(states)).lower()
            return ""
        shell = Mock(side_effect=command)
        self.assertTrue(self.wake(shell))
        self.assertIn(("wm dismiss-keyguard",), [call.args for call in shell.call_args_list])
        self.assertFalse(any("grep" in call.args[0] or "input swipe" in call.args[0]
                             for call in shell.call_args_list))

    def test_secure_or_unknown_lock_never_types_password_or_sends_swipe(self):
        for text in ("KeyguardServiceDelegate\n showing=true\n secure=true", "unknown"):
            shell = Mock(return_value=text)
            with self.assertRaisesRegex(RuntimeError, "手动解锁"):
                self.wake(shell)
            self.assertFalse(any("input text" in call.args[0] or "input swipe" in call.args[0]
                                 for call in shell.call_args_list))

    def test_activity_state_is_fallback_when_vendor_policy_has_no_known_fields(self):
        shell = Mock(side_effect=lambda cmd: "mKeyguardShowing=false"
                     if cmd == "dumpsys activity activities" else "")
        self.assertTrue(self.wake(shell))


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.backend = SimpleNamespace(
            DEVICE="first-phone", SCRIPT_DIR=Path("workspace"), GAME_PKG="test.game",
            GAME_ACT="test.game/Main", _original_brightness=None,
            screenshot=Mock(side_effect=lambda path: path),
            read_image=Mock(return_value=np.zeros((720, 1280, 3), dtype=np.uint8)),
            get_ocr=Mock(return_value=Mock(return_value=([], None))),
            adb_shell=Mock(return_value="mResumedActivity: ActivityRecord test.game/Main"),
            adb_command=Mock(return_value=subprocess.CompletedProcess([], 1, "", "")),
            tap=Mock(), swipe=Mock(), restore_brightness=Mock(),
            read_harvest_info=Mock(return_value={"exp": 12}),
            find_any_template=Mock(return_value=None), save_diagnostic=Mock(),
            check_adb_connection=Mock(return_value=True), wake_and_unlock=Mock(),
        )
        self.runtime = AdbFarmRuntime(self.backend)

    def test_screenshot_rewards_templates_and_diagnostics_use_same_device_path(self):
        self.runtime.read_ui()
        self.runtime.read_harvest_info()
        self.runtime.find_popup_close()
        self.runtime.save_diagnostic("example", {})
        for call in self.backend.screenshot.call_args_list:
            self.assertEqual((self.runtime.screenshot_path,), call.args)
        self.backend.read_harvest_info.assert_called_once_with(self.runtime.screenshot_path)
        self.assertEqual(self.runtime.screenshot_path, self.backend.find_any_template.call_args.args[1])
        self.assertEqual(self.runtime.screenshot_path,
                         self.backend.save_diagnostic.call_args.kwargs["screenshot_path"])
        self.backend.DEVICE = "second-phone"
        self.assertNotEqual(self.runtime.screenshot_path, AdbFarmRuntime(self.backend).screenshot_path)

    def test_capture_error_clears_previous_coordinate_evidence(self):
        self.runtime.read_ui()
        self.backend.screenshot.side_effect = RuntimeError("offline")
        self.assertIsNone(self.runtime.read_ui())
        with self.assertRaisesRegex(RuntimeError, "截图"):
            self.runtime.tap(400, 500)
        self.backend.tap.assert_not_called()

    def test_portrait_never_produces_clickable_frame(self):
        self.backend.read_image.return_value = np.zeros((1280, 720, 3), dtype=np.uint8)
        self.assertIsNone(self.runtime.read_ui())
        self.assertEqual("unknown", self.runtime.read_farmland().kind)
        with self.assertRaisesRegex(RuntimeError, "横屏"):
            self.runtime.tap(400, 500)

    def test_background_game_refuses_tap_swipe_and_back(self):
        self.runtime.read_ui()
        self.backend.adb_shell.return_value = "mResumedActivity: other.app/Main"
        for operation in (lambda: self.runtime.tap(400, 500), self.runtime.press_back,
                          lambda: self.runtime.swipe(SwipeGesture(10, 20, 30, 40, 100))):
            with self.assertRaisesRegex(RuntimeError, "前台"):
                operation()
        self.backend.tap.assert_not_called()
        self.backend.swipe.assert_not_called()

    def test_original_ocr_coordinates_are_not_scaled_or_rotated(self):
        self.backend.get_ocr.return_value.return_value = (
            [([[100, 200], [300, 200], [300, 260], [100, 260]], "测试", .95)], None)
        frame = self.runtime.read_ui()
        self.assertEqual((1280, 720), (frame.width, frame.height))
        self.assertEqual((200, 230), (frame.boxes[0].center_x, frame.boxes[0].center_y))

    def test_out_of_bounds_movement_is_refused(self):
        self.runtime.read_ui()
        with self.assertRaisesRegex(RuntimeError, "坐标"):
            self.runtime.swipe(SwipeGesture(100, 100, 1300, 100, 500))
        self.backend.swipe.assert_not_called()

    def test_cleanup_attempts_brightness_restore_even_if_stop_fails(self):
        self.backend.adb_shell.side_effect = RuntimeError("disconnect")
        with self.assertRaisesRegex(RuntimeError, "disconnect"):
            self.runtime.cleanup_round()
        self.backend.restore_brightness.assert_called_once()

    def test_roi_follows_apk_tablet_and_wide_screen_regions(self):
        for height, width, expected in ((1600, 2560, (1280, 1792)),
                                         (1080, 2400, (720, 1600))):
            result = maturity_roi(np.zeros((height, width, 3), dtype=np.uint8))
            self.assertEqual(expected, result.shape)

    def test_prepare_wakes_without_password(self):
        self.runtime.prepare_device()
        self.backend.wake_and_unlock.assert_called_once_with()


class CliTests(unittest.TestCase):
    def test_help_and_invalid_arguments_never_touch_device(self):
        with patch.object(wzry_auto, "adb_shell") as shell, \
                patch.object(wzry_auto, "resolve_device") as resolve, \
                patch("sys.stdout", io.StringIO()), patch("sys.stderr", io.StringIO()):
            for arguments, code in ((["--help"], 0), (["--rounds", "0"], 2)):
                with self.assertRaises(SystemExit) as raised:
                    wzry_auto.main(arguments)
                self.assertEqual(code, raised.exception.code)
            shell.assert_not_called()
            resolve.assert_not_called()

    def test_duplicate_invocation_does_not_stop_lock_owners_game(self):
        with patch.object(wzry_auto, "resolve_device", return_value=True), \
                patch.object(wzry_auto, "check_adb_connection", return_value=True), \
                patch("farm_state.StateStore") as store, \
                patch.object(wzry_auto, "force_stop_game") as stop, \
                patch.object(wzry_auto, "restore_brightness") as restore:
            store.return_value.locked.return_value.__enter__.side_effect = RuntimeError("already running")
            with self.assertRaisesRegex(RuntimeError, "already running"):
                wzry_auto.main(["--brightness", "keep"])
            stop.assert_not_called()
            restore.assert_not_called()


class BrightnessTests(unittest.TestCase):
    def test_missing_alternate_root_node_does_not_block_restoration(self):
        with patch.object(wzry_auto, "_original_brightness", 128), \
                patch.object(wzry_auto, "_original_auto_brightness", 1), \
                patch.object(wzry_auto, "_brightness_mode", "root_zero"), \
                patch.object(wzry_auto, "adb_shell_root") as root, \
                patch.object(wzry_auto, "adb_shell") as shell:
            wzry_auto.restore_brightness()
            root.assert_called_once()
            self.assertEqual(2, shell.call_count)
            self.assertIsNone(wzry_auto._original_brightness)

    def test_failed_root_restore_still_attempts_both_system_settings(self):
        with patch.object(wzry_auto, "_original_brightness", 128), \
                patch.object(wzry_auto, "_original_auto_brightness", 1), \
                patch.object(wzry_auto, "_brightness_mode", "root_zero"), \
                patch.object(wzry_auto, "adb_shell_root", side_effect=OSError("no node")), \
                patch.object(wzry_auto, "adb_shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "ROOT亮度"):
                wzry_auto.restore_brightness()
            self.assertEqual(2, shell.call_count)
            self.assertEqual(128, wzry_auto._original_brightness)


if __name__ == "__main__":
    unittest.main()
