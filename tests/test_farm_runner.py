"""Round orchestration with fake navigation/actions; never connects to ADB."""

from datetime import datetime, timedelta
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from farm_actions import FarmActionResult
from farm_runner import FarmRunner, action_not_before, plan_after_action
from farm_schedule import FarmlandReading
from farm_state import Checkpoint, FarmState, PersistentActionGuard, StateStore


START = datetime(2026, 9, 22, 10)


def at(minutes=0, seconds=0):
    return START + timedelta(minutes=minutes, seconds=seconds)


def result_for(maturity=at(55), water=START, observed=at(seconds=10),
               ready=at(seconds=5), harvested=False, recovered=False, kind="planted"):
    reading = FarmlandReading(kind, "测试土地卡", observed, maturity if kind == "planted" else None, 0)
    return FarmActionResult(harvested, {"exp": 12, "crops": {"番茄": 2}} if harvested else None,
                            reading, water, ready, recovered)


class FakeRuntime:
    def __init__(self):
        self.time = START
        self.sleeps = []
        self.messages = []
        self.harvests = []
        self.diagnostics = []
        self.prepared = 0
        self.cleaned = 0
        self.cleanup_error = None

    def now(self):
        return self.time

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.time += timedelta(seconds=seconds)

    def prepare_device(self):
        self.prepared += 1

    def cleanup_round(self):
        self.cleaned += 1
        if self.cleanup_error is not None:
            raise self.cleanup_error

    def log(self, message):
        self.messages.append(message)

    def record_harvest(self, info):
        self.harvests.append(info)

    def save_diagnostic(self, kind, data):
        self.diagnostics.append((kind, data))


class ActionFactory:
    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def __call__(self, runtime, guard, **kwargs):
        self.calls.append(dict(kwargs))
        return SimpleNamespace(run=lambda: self.handler(runtime, guard, **kwargs))


class RunnerTestCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.store = StateStore(self.directory, "fake-phone")
        self.runtime = FakeRuntime()
        self.navigation = Mock(return_value=SimpleNamespace(run=Mock()))

    def reload(self):
        return StateStore(self.directory, "fake-phone")

    def runner(self, actions, store=None):
        return FarmRunner(self.runtime, store or self.store,
                          navigation_factory=self.navigation, actions_factory=actions)

    def seed_farm(self, cycle=60, **changes):
        values = dict(cycle_minutes=cycle, batch_started_at=START,
                      observed_maturity_at=at(minutes=cycle * 11 / 12), updated_at=START,
                      last_confirmed_watering_at=START, last_attempt_at=START,
                      cycle_estimated=False, confirmed_watering_count=1)
        values.update(changes)
        farm = FarmState(**values)
        self.store.save_farm(farm)
        return farm


class PlanningTests(RunnerTestCase):
    def test_continuous_rounds_preserve_every_long_cycle_and_original_batch(self):
        for cycle in (60, 480, 960, 1920):
            with self.subTest(cycle=cycle):
                store = StateStore(self.directory, "cycle-{}".format(cycle))
                total = cycle * 60
                maturity = at(seconds=total * 11 // 12)
                initial = result_for(maturity=maturity, harvested=True)
                schedule = plan_after_action(store, initial, at(seconds=10), 120)
                self.assertEqual(cycle, schedule.cycle_minutes)
                self.assertFalse(schedule.cycle_estimated)
                last_water = START
                for offset in (total // 3, total * 2 // 3, total * 11 // 15):
                    self.assertEqual(at(seconds=offset), schedule.target_at)
                    water = at(seconds=offset)
                    elapsed = min(int((water - last_water).total_seconds()), total // 3)
                    maturity -= timedelta(seconds=elapsed // 4)
                    observation = result_for(maturity=maturity, water=water,
                                             observed=water + timedelta(seconds=1), ready=water)
                    schedule = plan_after_action(store, observation, water + timedelta(seconds=1), 120)
                    restored = StateStore(self.directory, "cycle-{}".format(cycle))
                    self.assertEqual(cycle, restored.state.farm.cycle_minutes)
                    self.assertEqual(START, restored.state.farm.batch_started_at)
                    self.assertFalse(restored.state.farm.cycle_estimated)
                    last_water = water
                self.assertEqual(last_water, maturity)
                self.assertEqual("MATURITY", schedule.reason)

    def test_repeated_same_observation_cannot_confirm_same_watering_twice(self):
        self.seed_farm()
        observation = result_for(maturity=at(50), water=at(20), observed=at(20, 10), ready=at(19))
        first = plan_after_action(self.store, observation, at(20, 10), 120)
        saved = self.reload().state.farm
        second = plan_after_action(self.store, observation, at(20, 10), 120)
        self.assertEqual(2, saved.confirmed_watering_count)
        self.assertEqual(saved.confirmed_watering_count, self.reload().state.farm.confirmed_watering_count)
        self.assertEqual(at(20), self.reload().state.farm.last_confirmed_watering_at)
        self.assertEqual(first.target_at, second.target_at)

    def test_closed_reward_popup_uses_persisted_harvest_for_new_batch(self):
        self.seed_farm(cycle=480, batch_started_at=at(-300), updated_at=at(-1))
        guard = PersistentActionGuard(self.store, lambda: START)
        guard.before_tap(object())
        guard.on_harvest_observed()
        restored = self.reload()
        observation = result_for(maturity=at(55), harvested=False, recovered=True)
        schedule = plan_after_action(restored, observation, at(seconds=10), 135)
        self.assertEqual(60, schedule.cycle_minutes)
        self.assertFalse(schedule.cycle_estimated)
        self.assertEqual(START, schedule.batch_started_at)
        self.assertEqual(60, self.reload().state.farm.cycle_minutes)

    def test_supplementary_harvest_does_not_reuse_old_crop_identity(self):
        self.seed_farm(cycle=480, batch_started_at=at(-300), updated_at=at(-1))
        guard = PersistentActionGuard(self.store, lambda: START)
        guard.before_tap(object())
        guard.before_maturity_harvest(object())
        schedule = plan_after_action(self.store, result_for(recovered=True), at(seconds=10), 135)
        self.assertEqual(60, schedule.cycle_minutes)
        self.assertTrue(schedule.cycle_estimated)
        self.assertEqual(START, schedule.batch_started_at)

    def test_incompatible_crop_record_is_replaced_by_estimated_current_cycle(self):
        self.seed_farm(cycle=60, batch_started_at=at(-180), updated_at=at(-1))
        schedule = plan_after_action(self.store, result_for(maturity=at(25)), at(seconds=10), 120)
        self.assertEqual(60, schedule.cycle_minutes)
        self.assertTrue(schedule.cycle_estimated)
        self.assertEqual(START, schedule.batch_started_at)

    def test_empty_card_clears_crop_and_has_no_further_schedule(self):
        self.seed_farm()
        self.assertIsNone(plan_after_action(self.store, result_for(kind="empty"), START, 120))
        self.assertIsNone(self.reload().state.farm)

    def test_unrecognized_or_unharvested_mature_card_preserves_record_and_fails(self):
        farm = self.seed_farm()
        for kind in ("unknown", "mature"):
            with self.subTest(kind=kind), self.assertRaises(RuntimeError):
                plan_after_action(self.store, result_for(kind=kind), START, 120)
            self.assertEqual(farm, self.reload().state.farm)

    def test_watering_minimum_interval_uses_latest_attempt(self):
        farm = self.seed_farm(last_attempt_at=at(19))
        checkpoint = Checkpoint(target_at=at(20), reason="WATERING")
        self.assertEqual(at(21), action_not_before(checkpoint, farm))

    def test_maturity_retry_keeps_maturity_target_without_watering_cooldown(self):
        farm = self.seed_farm(last_attempt_at=at(19))
        checkpoint = Checkpoint(target_at=at(20), reason="MATURITY")
        self.assertEqual(at(20), action_not_before(checkpoint, farm))

    def test_no_pending_target_has_no_unnecessary_delay(self):
        self.assertIsNone(action_not_before(Checkpoint(), None))


class FarmRunnerTests(RunnerTestCase):
    def test_runner_passes_pending_target_and_sent_time_to_observation_recovery(self):
        self.store.update_checkpoint(
            phase="WAITING", action="ONE_CLICK_SENT", action_sent_at=START,
            target_at=at(20), wake_at=at(18), reason="MATURITY",
        )
        actions = ActionFactory(lambda runtime, guard, **kwargs: result_for(kind="empty", recovered=True))
        self.runner(actions).execute_round()
        self.assertEqual(at(20), actions.calls[0]["not_before"])
        self.assertEqual(START, actions.calls[0]["resume_after_action_at"])
        self.assertEqual(1, self.runtime.cleaned)
        self.assertEqual("COMPLETED", self.reload().state.checkpoint.phase)

    def test_failed_cleanup_keeps_sent_boundary_and_recovery_does_not_resend(self):
        dispatches = []

        def handle(runtime, guard, not_before, resume_after_action_at):
            if resume_after_action_at is None and guard.before_tap(object()):
                dispatches.append(runtime.now())
                guard.after_tap_accepted(runtime.now())
            runtime.time = at(seconds=10)
            return result_for(recovered=resume_after_action_at is not None)

        actions = ActionFactory(handle)
        self.runtime.cleanup_error = OSError("screen cleanup failed")
        with self.assertRaises(OSError):
            self.runner(actions).execute_round()
        failed = self.reload()
        self.assertEqual("ONE_CLICK_CONFIRMED", failed.state.checkpoint.action)
        self.assertEqual(0, failed.state.checkpoint.completed_rounds)
        self.runtime.cleanup_error = None
        self.runner(actions, store=failed).execute_round()
        self.assertEqual([START], dispatches)
        self.assertEqual(START, actions.calls[1]["resume_after_action_at"])
        self.assertEqual(1, self.reload().state.checkpoint.completed_rounds)
        self.assertFalse(self.reload().state.checkpoint.already_sent)

    def test_navigation_failure_still_cleans_up_and_keeps_existing_action(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        self.navigation.return_value.run.side_effect = RuntimeError("cannot confirm farm")
        actions = Mock()
        with self.assertRaisesRegex(RuntimeError, "cannot confirm farm"):
            self.runner(actions).execute_round()
        actions.assert_not_called()
        self.assertEqual(1, self.runtime.cleaned)
        self.assertEqual("ONE_CLICK_SENT", self.reload().state.checkpoint.action)
        self.assertEqual(0, self.reload().state.checkpoint.completed_rounds)

    def test_primary_error_is_preserved_when_cleanup_also_fails(self):
        self.navigation.return_value.run.side_effect = RuntimeError("navigation failed")
        self.runtime.cleanup_error = OSError("cleanup failed")
        with self.assertRaisesRegex(RuntimeError, "navigation failed"):
            self.runner(Mock()).execute_round()
        self.assertTrue(any("清理也失败" in message for message in self.runtime.messages))

    def test_lead_uses_ready_time_and_excludes_wait_for_target(self):
        def handle(runtime, guard, **kwargs):
            runtime.time = at(seconds=210)
            guard.before_tap(object())
            guard.after_tap_accepted(at(seconds=200))
            return result_for(
                maturity=at(seconds=3500), water=at(seconds=200),
                observed=at(seconds=210), ready=at(seconds=10), harvested=True,
            )

        schedule = self.runner(ActionFactory(handle)).execute_round()
        self.assertEqual(25, (schedule.target_at - schedule.wake_at).total_seconds())
        self.assertEqual(1, len(self.runtime.harvests))

    def test_recovery_uses_default_preparation_lead(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        actions = ActionFactory(lambda runtime, guard, **kwargs: result_for(recovered=True))
        schedule = self.runner(actions).execute_round()
        self.assertEqual(135, (schedule.target_at - schedule.wake_at).total_seconds())

    def test_wait_until_sleeps_in_interruptible_slices(self):
        self.runner(Mock()).wait_until(at(seconds=2.5))
        self.assertEqual([1, 1, .5], self.runtime.sleeps)
        self.assertEqual(at(seconds=2.5), self.runtime.now())

    def test_five_failures_keep_one_sent_action_for_restart_recovery(self):
        dispatches = []

        def fail_after_tap(runtime, guard, resume_after_action_at, **kwargs):
            if resume_after_action_at is None and guard.before_tap(object()):
                dispatches.append(runtime.now())
            raise RuntimeError("OCR unavailable")

        actions = ActionFactory(fail_after_tap)
        with self.assertRaisesRegex(RuntimeError, "连续失败5次"):
            self.runner(actions).run(max_rounds=1)
        failed = self.reload()
        self.assertEqual([START], dispatches)
        self.assertEqual(5, len(actions.calls))
        self.assertEqual(5, self.runtime.cleaned)
        self.assertEqual(5, len(self.runtime.diagnostics))
        self.assertEqual("ERROR", failed.state.checkpoint.phase)
        self.assertEqual(5, failed.state.checkpoint.consecutive_failures)
        self.assertEqual("ONE_CLICK_SENT", failed.state.checkpoint.action)
        self.assertEqual(START, failed.state.checkpoint.action_sent_at)
        self.assertEqual(0, failed.state.checkpoint.completed_rounds)
        self.assertEqual(240, sum(self.runtime.sleeps))
        self.assertEqual([None, START, START, START, START],
                         [call["resume_after_action_at"] for call in actions.calls])
        recovery = ActionFactory(lambda runtime, guard, **kwargs: result_for(kind="empty", recovered=True))
        self.runner(recovery, store=failed).run(max_rounds=1)
        self.assertEqual(START, recovery.calls[0]["resume_after_action_at"])
        self.assertEqual("COMPLETED", self.reload().state.checkpoint.phase)
        self.assertEqual(1, self.reload().state.checkpoint.completed_rounds)

    def test_ctrl_c_keeps_pending_action_and_target_for_next_start(self):
        self.store.update_checkpoint(target_at=at(20), wake_at=at(18), reason="WATERING")

        def interrupted(runtime, guard, **kwargs):
            guard.before_tap(object())
            raise KeyboardInterrupt()

        with self.assertRaises(KeyboardInterrupt):
            self.runner(ActionFactory(interrupted)).run(max_rounds=1)
        checkpoint = self.reload().state.checkpoint
        self.assertEqual("ONE_CLICK_SENT", checkpoint.action)
        self.assertEqual(at(20), checkpoint.target_at)
        self.assertEqual("WATERING", checkpoint.reason)
        self.assertEqual(0, checkpoint.completed_rounds)
        self.assertEqual(1, self.runtime.cleaned)

    def test_diagnostic_disk_failure_does_not_bypass_retry_or_erase_sent_record(self):
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=START)
        self.runtime.save_diagnostic = Mock(side_effect=OSError("disk full"))
        self.navigation.return_value.run.side_effect = RuntimeError("OCR unavailable")
        with self.assertRaisesRegex(RuntimeError, "连续失败5次"):
            self.runner(Mock()).run(max_rounds=1)
        checkpoint = self.reload().state.checkpoint
        self.assertEqual("ERROR", checkpoint.phase)
        self.assertEqual(5, checkpoint.consecutive_failures)
        self.assertEqual("ONE_CLICK_SENT", checkpoint.action)


if __name__ == "__main__":
    unittest.main()
