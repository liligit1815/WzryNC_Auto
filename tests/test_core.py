import subprocess
import tempfile
import unittest
import json
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
import wzry_auto


ROOT = Path(__file__).resolve().parents[1]


class TemplateMatchingTests(unittest.TestCase):
    def test_event_popup_close_matches_in_safe_region(self):
        template = cv2.imread(str(
            ROOT / "assets" / "templates" / "2400x1080"
            / "close_popup_event.png"
        ))
        canvas = np.zeros((1080, 2400, 3), dtype=np.uint8)
        canvas[78:160, 2060:2142] = template
        with tempfile.TemporaryDirectory() as directory:
            screenshot = Path(directory) / "event.png"
            cv2.imwrite(str(screenshot), canvas)
            result = wzry_auto.find_template(
                "close_popup_event.png", str(screenshot)
            )
        self.assertIsNotNone(result)
        self.assertGreaterEqual(result["score"], 0.78)
        self.assertEqual((result["x"], result["y"]), (2101, 119))

    def test_popup_roi_excludes_top_center_navigation(self):
        x1, _, _, _ = wzry_auto.TEMPLATE_ROIS["close_popup.png"]
        self.assertGreaterEqual(x1, 0.75)

    def test_dedicated_template_scales_are_bounded(self):
        scales = wzry_auto._template_scales(
            ROOT / "assets" / "templates" / "2400x1080",
            2400,
            1080,
        )
        self.assertEqual(scales, [0.9, 0.95, 1.0, 1.05, 1.1])


class AdbTests(unittest.TestCase):
    @patch("wzry_auto.subprocess.run")
    def test_adb_command_does_not_use_host_shell(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "device\n", "")
        previous = wzry_auto.DEVICE
        try:
            wzry_auto.DEVICE = "example:5555"
            wzry_auto.adb_command("get-state")
        finally:
            wzry_auto.DEVICE = previous

        args, kwargs = run.call_args
        self.assertEqual(
            args[0][-3:],
            ["-s", "example:5555", "get-state"],
        )
        self.assertNotIn("shell", kwargs)

    @patch("wzry_auto._run_adb")
    def test_root_shell_quotes_redirection_inside_su(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "", "")
        wzry_auto.adb_shell_root(
            "echo 1 > /sys/class/backlight/panel0-backlight/brightness"
        )
        args, kwargs = run.call_args
        self.assertEqual(args[0][0], "shell")
        self.assertEqual(
            args[0][1],
            "su -c 'echo 1 > "
            "/sys/class/backlight/panel0-backlight/brightness'",
        )
        self.assertEqual(kwargs["timeout"], 15)

    @patch("wzry_auto.set_brightness_low")
    @patch("wzry_auto.set_brightness_one_root")
    @patch("wzry_auto.get_brightness_settings")
    @patch("builtins.input", return_value="1")
    def test_root_brightness_failure_falls_back_to_normal_low(
        self, user_input, get_settings, set_root, set_low
    ):
        set_root.side_effect = RuntimeError("Permission denied")
        mode = wzry_auto.prompt_brightness_control()
        self.assertEqual(mode, "low")
        set_low.assert_called_once_with()

    @patch("wzry_auto.adb_command")
    @patch("wzry_auto.adb_shell")
    def test_failed_pull_does_not_replace_previous_screenshot(
        self, shell, command
    ):
        command.return_value = subprocess.CompletedProcess(
            [], 1, "", "device offline"
        )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "current.png"
            target.write_bytes(b"old screenshot")
            with self.assertRaises(RuntimeError):
                wzry_auto.screenshot(str(target))
            self.assertEqual(target.read_bytes(), b"old screenshot")
        shell.assert_called_once_with(
            "screencap -p /sdcard/screen.png", timeout=30, retries=2
        )


class BrightnessTests(unittest.TestCase):
    def tearDown(self):
        wzry_auto._original_brightness = None
        wzry_auto._original_auto_brightness = None
        wzry_auto._brightness_mode = None

    @patch("wzry_auto.adb_shell")
    @patch("wzry_auto.adb_shell_root")
    def test_restore_uses_only_existing_backlight_nodes(self, root, shell):
        wzry_auto._original_brightness = 264
        wzry_auto._original_auto_brightness = 0
        wzry_auto._brightness_mode = "root_one"

        wzry_auto.restore_brightness()

        root_command = root.call_args.args[0]
        self.assertIn("/sys/class/backlight/*/brightness", root_command)
        self.assertIn('[ -e "$node" ]', root_command)
        self.assertEqual(shell.call_count, 2)

    @patch("wzry_auto.adb_shell")
    @patch("wzry_auto.adb_shell_root")
    def test_restore_failure_does_not_escape_or_skip_settings(
        self, root, shell
    ):
        wzry_auto._original_brightness = 264
        wzry_auto._original_auto_brightness = 0
        wzry_auto._brightness_mode = "root_one"
        root.side_effect = RuntimeError("temporary adb failure")

        wzry_auto.restore_brightness()

        self.assertEqual(shell.call_count, 2)
        self.assertIsNone(wzry_auto._original_brightness)
        self.assertIsNone(wzry_auto._brightness_mode)


class SchedulingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.state_file = Path(self.directory.name) / "farm_state.json"
        self.cycle_file = Path(self.directory.name) / "crop_cycle.json"
        self.state_patch = patch.object(
            wzry_auto, "FARM_STATE_FILE", self.state_file
        )
        self.cycle_patch = patch.object(
            wzry_auto, "CYCLE_FILE", str(self.cycle_file)
        )
        self.state_patch.start()
        self.cycle_patch.start()

    def tearDown(self):
        self.state_patch.stop()
        self.cycle_patch.stop()
        self.directory.cleanup()

    @patch("wzry_auto.datetime")
    def test_later_round_keeps_original_batch_start(self, clock):
        first = datetime(2026, 7, 27, 11, 14, 0)
        clock.now.return_value = first
        clock.fromisoformat.side_effect = datetime.fromisoformat
        wzry_auto.calculate_plant_cycle_and_water_time(
            first, datetime(2026, 7, 27, 12, 10), save_if_fresh=True
        )

        second = datetime(2026, 7, 27, 11, 34, 0)
        clock.now.return_value = second
        result = wzry_auto.calculate_plant_cycle_and_water_time(
            second, datetime(2026, 7, 27, 12, 5), save_if_fresh=False
        )
        self.assertEqual(
            result["water3"], datetime(2026, 7, 27, 11, 54, 0)
        )
        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertEqual(state["batch_started_at"], "2026-07-27T11:14:00")

    @patch("wzry_auto.datetime")
    def test_next_watering_never_exceeds_observed_maturity(self, clock):
        first = datetime(2026, 7, 27, 11, 14, 0)
        clock.now.return_value = datetime(2026, 7, 27, 11, 50, 0)
        clock.fromisoformat.side_effect = datetime.fromisoformat
        self.state_file.write_text(
            json.dumps({
                "batch_started_at": first.isoformat(),
                "cycle_min": 60,
            }),
            encoding="utf-8",
        )
        self.cycle_file.write_text(
            json.dumps({"crop_name": "作物", "cycle_min": 60}),
            encoding="utf-8",
        )
        result = wzry_auto.calculate_plant_cycle_and_water_time(
            datetime(2026, 7, 27, 11, 50),
            datetime(2026, 7, 27, 11, 53),
        )
        self.assertIsNone(result["next_watering"])


if __name__ == "__main__":
    unittest.main()
