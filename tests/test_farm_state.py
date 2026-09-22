"""Durable checkpoint tests; all files live in isolated temporary folders."""

from dataclasses import replace
from datetime import datetime, timedelta
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from farm_schedule import calculate_schedule
from farm_state import Checkpoint, FarmState, PersistentActionGuard, SavedState, StateStore


START = datetime(2026, 9, 22, 10)


class StateStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.store = StateStore(self.directory, "test-phone")

    def reload(self, device="test-phone"):
        return StateStore(self.directory, device)

    def test_device_is_required_before_loading_state(self):
        with self.assertRaises(ValueError):
            StateStore(self.directory, "")

    def test_device_records_and_locks_are_isolated(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        other = self.reload("different-phone")
        self.assertNotEqual(self.store.path, other.path)
        self.assertNotEqual(self.store.lock_path, other.lock_path)
        self.assertFalse(other.state.checkpoint.already_sent)
        other.update_checkpoint(completed_rounds=7)
        self.assertEqual(0, self.reload().state.checkpoint.completed_rounds)
        self.assertTrue(self.reload().state.checkpoint.already_sent)
        self.assertEqual(7, self.reload("different-phone").state.checkpoint.completed_rounds)

    def test_state_survives_real_file_round_trip(self):
        farm = FarmState(480, START, START + timedelta(hours=7), START,
                         START, START, False, 2)
        checkpoint = Checkpoint(
            phase="WAITING", completed_rounds=3, target_at=START + timedelta(hours=2),
            wake_at=START + timedelta(hours=2, minutes=-2), reason="WATERING",
            action="ONE_CLICK_CONFIRMED", action_sent_at=START,
            harvest_observed=True, consecutive_failures=2, last_error="识别失败",
        )
        self.store.save(SavedState(checkpoint, farm))
        restored = self.reload()
        self.assertEqual(self.store.state, restored.state)
        self.assertTrue(restored.state.checkpoint.already_sent)
        self.assertEqual(START, restored.state.farm.last_confirmed_watering_at)

    def test_begin_round_preserves_pending_target_and_sent_boundary(self):
        self.store.update_checkpoint(
            phase="WAITING", target_at=START + timedelta(minutes=20),
            wake_at=START + timedelta(minutes=18), reason="WATERING",
            action="ONE_CLICK_SENT", action_sent_at=START,
        )
        self.reload().begin_round()
        checkpoint = self.reload().state.checkpoint
        self.assertEqual("RUNNING", checkpoint.phase)
        self.assertIsNone(checkpoint.wake_at)
        self.assertEqual(START + timedelta(minutes=20), checkpoint.target_at)
        self.assertEqual("WATERING", checkpoint.reason)
        self.assertEqual("ONE_CLICK_SENT", checkpoint.action)
        self.assertEqual(START, checkpoint.action_sent_at)

    def test_corrupt_json_blocks_resume_and_keeps_original_file(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        self.store.path.write_text('{"checkpoint":{"action":"ONE_CLICK_SENT"', encoding="utf-8")
        before = self.store.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "无法安全读取"):
            self.reload()
        self.assertEqual(before, self.store.path.read_bytes())

    def test_invalid_saved_fields_fail_closed_without_resetting_sent_input(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        original = json.loads(self.store.path.read_text(encoding="utf-8"))
        corruptions = (
            lambda data: data.update(schema=999),
            lambda data: data.update(device="other-device"),
            lambda data: data["checkpoint"].update(action="UNKNOWN_INPUT"),
            lambda data: data["checkpoint"].update(action_sent_at=None),
            lambda data: data["checkpoint"].update(phase="UNKNOWN_PHASE"),
            lambda data: data["checkpoint"].update(target_at="not a datetime"),
        )
        for mutate in corruptions:
            with self.subTest(mutate=mutate):
                data = json.loads(json.dumps(original))
                mutate(data)
                raw = json.dumps(data)
                self.store.path.write_text(raw, encoding="utf-8")
                with self.assertRaises(ValueError):
                    self.reload()
                self.assertEqual(raw, self.store.path.read_text(encoding="utf-8"))

    def test_failed_atomic_replace_keeps_last_durable_and_in_memory_state(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        before = self.store.state
        durable = self.store.path.read_bytes()
        with patch("farm_state.os.replace", side_effect=OSError("disk failure")):
            with self.assertRaises(OSError):
                self.store.update_checkpoint(action="ONE_CLICK_CONFIRMED")
        self.assertEqual(before, self.store.state)
        self.assertEqual(durable, self.store.path.read_bytes())
        self.assertEqual(before, self.reload().state)
        self.assertEqual([], list(self.directory.glob("*.tmp")))

    def test_finish_round_persists_completion_and_next_schedule_in_one_write(self):
        self.store.update_checkpoint(
            completed_rounds=4, action="MATURITY_HARVEST_CONFIRMED",
            action_sent_at=START, harvest_observed=True,
            consecutive_failures=2, last_error="old failure",
        )
        schedule = calculate_schedule(START, START + timedelta(minutes=55), START,
                                      stored_cycle_minutes=60)
        with patch.object(self.store, "save", wraps=self.store.save) as save:
            self.store.finish_round(schedule)
        self.assertEqual(1, save.call_count)
        checkpoint = self.reload().state.checkpoint
        self.assertEqual("WAITING", checkpoint.phase)
        self.assertEqual(5, checkpoint.completed_rounds)
        self.assertEqual(schedule.target_at, checkpoint.target_at)
        self.assertEqual(schedule.wake_at, checkpoint.wake_at)
        self.assertEqual(schedule.reason, checkpoint.reason)
        self.assertFalse(checkpoint.already_sent)
        self.assertFalse(checkpoint.harvest_observed)
        self.assertEqual(0, checkpoint.consecutive_failures)
        self.assertIsNone(checkpoint.last_error)

    def test_empty_farmland_completion_clears_schedule(self):
        self.store.update_checkpoint(target_at=START, wake_at=START, reason="MATURITY")
        self.store.finish_round(None)
        checkpoint = self.reload().state.checkpoint
        self.assertEqual("COMPLETED", checkpoint.phase)
        self.assertEqual(1, checkpoint.completed_rounds)
        self.assertIsNone(checkpoint.target_at)
        self.assertIsNone(checkpoint.wake_at)
        self.assertIsNone(checkpoint.reason)

    def test_five_failed_attempts_retain_sent_action_harvest_and_target(self):
        target = START + timedelta(minutes=20)
        self.store.update_checkpoint(
            action="ONE_CLICK_CONFIRMED", action_sent_at=START,
            harvest_observed=True, target_at=target, reason="WATERING",
        )
        for number in range(1, 6):
            with self.subTest(number=number):
                retry = self.store.retry(RuntimeError("OCR failed"), START)
                checkpoint = self.reload().state.checkpoint
                self.assertEqual(number < 5, retry)
                self.assertEqual(number, checkpoint.consecutive_failures)
                self.assertEqual("WAITING" if number < 5 else "ERROR", checkpoint.phase)
                self.assertEqual(START + timedelta(seconds=60) if number < 5 else None, checkpoint.wake_at)
                self.assertEqual("ONE_CLICK_CONFIRMED", checkpoint.action)
                self.assertEqual(START, checkpoint.action_sent_at)
                self.assertTrue(checkpoint.harvest_observed)
                self.assertEqual(target, checkpoint.target_at)
                self.assertEqual("WATERING", checkpoint.reason)

    def test_lock_refreshes_state_and_rejects_another_runner(self):
        other = self.reload()
        self.store.update_checkpoint(completed_rounds=8)
        with self.store.locked():
            with self.assertRaisesRegex(RuntimeError, "已有"):
                with other.locked():
                    pass
        with other.locked():
            self.assertEqual(8, other.state.checkpoint.completed_rounds)


class PersistentActionGuardTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.store = StateStore(self.directory, "test-phone")
        self.guard = PersistentActionGuard(self.store, lambda: START)

    def restored_guard(self):
        return PersistentActionGuard(StateStore(self.directory, "test-phone"), lambda: START)

    def test_main_action_is_durable_before_dispatch_and_cannot_repeat_after_restart(self):
        self.assertTrue(self.guard.before_tap(object()))
        restored = self.restored_guard()
        self.assertEqual("ONE_CLICK_SENT", restored.store.state.checkpoint.action)
        self.assertEqual(START, restored.store.state.checkpoint.action_sent_at)
        self.assertFalse(restored.before_tap(object()))
        restored.after_tap_accepted(START + timedelta(seconds=1))
        restarted = self.restored_guard()
        self.assertEqual("ONE_CLICK_CONFIRMED", restarted.store.state.checkpoint.action)
        self.assertFalse(restarted.before_tap(object()))

    def test_supplementary_harvest_requires_primary_and_can_only_send_once(self):
        self.assertFalse(self.guard.before_maturity_harvest(object()))
        self.assertTrue(self.guard.before_tap(object()))
        self.guard.after_tap_accepted(START)
        self.assertTrue(self.guard.before_maturity_harvest(object()))
        restored = self.restored_guard()
        self.assertTrue(restored.store.state.checkpoint.maturity_harvest_sent)
        self.assertFalse(restored.before_maturity_harvest(object()))
        self.assertFalse(restored.before_tap(object()))
        restored.after_maturity_harvest_accepted(START + timedelta(seconds=2))
        restarted = self.restored_guard()
        self.assertEqual("MATURITY_HARVEST_CONFIRMED", restarted.store.state.checkpoint.action)
        self.assertFalse(restarted.before_maturity_harvest(object()))

    def test_observed_harvest_is_durable_before_popup_disappears(self):
        with self.assertRaises(RuntimeError):
            self.guard.on_harvest_observed()
        self.guard.before_tap(object())
        self.guard.on_harvest_observed()
        restored = self.restored_guard()
        self.assertTrue(restored.store.state.checkpoint.harvest_observed)
        restored.on_harvest_observed()
        self.assertTrue(self.restored_guard().store.state.checkpoint.harvest_observed)

    def test_acceptance_requires_matching_sent_state(self):
        with self.assertRaises(RuntimeError):
            self.guard.after_tap_accepted(START)
        with self.assertRaises(RuntimeError):
            self.guard.after_maturity_harvest_accepted(START)
        self.guard.before_tap(object())
        with self.assertRaises(RuntimeError):
            self.guard.after_maturity_harvest_accepted(START)


if __name__ == "__main__":
    unittest.main()
