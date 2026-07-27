import subprocess
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
