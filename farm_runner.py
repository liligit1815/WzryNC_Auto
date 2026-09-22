"""APK 0.3.20 round orchestration, independent of the ADB/OCR transport."""
from datetime import timedelta

from farm_actions import FarmActionAutomation
from farm_navigation import EnterFarmAutomation
from farm_schedule import calculate_schedule, evaluate_watering
from farm_state import FarmState, PersistentActionGuard


def plan_after_action(store, result, now, wake_lead_seconds):
    farmland = result.farmland_state
    if farmland.kind == "empty":
        store.save_farm(None)
        return None
    if farmland.kind != "planted" or farmland.maturity_at is None:
        raise RuntimeError(f"土地状态无法用于排程：{farmland.kind} {farmland.reason}")
    checkpoint = store.state.checkpoint
    fresh = result.harvested or checkpoint.harvest_observed
    prior = None if fresh or checkpoint.maturity_harvest_sent else store.state.farm
    evidence = None
    if prior is not None:
        evidence = evaluate_watering(
            cycle_minutes=prior.cycle_minutes,
            batch_started_at=prior.batch_started_at,
            previous_maturity_at=prior.observed_maturity_at,
            previous_observed_at=prior.updated_at,
            observed_maturity_at=farmland.maturity_at,
            observed_at=farmland.observed_at,
            last_confirmed_watering_at=prior.last_confirmed_watering_at,
            last_attempt_at=result.first_water_at,
        )
    stored = prior if evidence and evidence.batch_compatible else None
    confirmed = bool(stored and evidence.watering_confirmed and (
        stored.last_attempt_at is None or result.first_water_at > stored.last_attempt_at
    ))
    confirmed_at = result.first_water_at if confirmed else (
        stored.last_confirmed_watering_at if stored else None
    )
    schedule = calculate_schedule(
        first_water_at=result.first_water_at,
        observed_maturity_at=farmland.maturity_at,
        now=now,
        stored_cycle_minutes=stored.cycle_minutes if stored else None,
        batch_started_at=stored.batch_started_at if stored else None,
        wake_lead_seconds=wake_lead_seconds,
        last_confirmed_watering_at=confirmed_at or result.first_water_at,
        last_attempt_at=result.first_water_at,
        fresh_batch=fresh,
        stored_cycle_estimated=stored.cycle_estimated if stored else True,
        maturity_precision_seconds=farmland.precision_seconds,
    )
    store.save_farm(FarmState(
        cycle_minutes=schedule.cycle_minutes,
        batch_started_at=schedule.batch_started_at,
        observed_maturity_at=schedule.observed_maturity_at,
        updated_at=farmland.observed_at,
        last_confirmed_watering_at=confirmed_at,
        last_attempt_at=result.first_water_at,
        cycle_estimated=schedule.cycle_estimated,
        confirmed_watering_count=(stored.confirmed_watering_count if stored else 0) + int(confirmed),
    ))
    return schedule


def action_not_before(checkpoint, farm):
    targets = [checkpoint.target_at] if checkpoint.target_at else []
    if checkpoint.reason != "MATURITY" and farm is not None and farm.last_attempt_at:
        targets.append(farm.last_attempt_at + timedelta(seconds=farm.cycle_minutes * 2))
    return max(targets) if targets else None


class FarmRunner:
    def __init__(self, runtime, store, navigation_factory=EnterFarmAutomation,
                 actions_factory=FarmActionAutomation):
        self.runtime = runtime
        self.store = store
        self.navigation_factory = navigation_factory
        self.actions_factory = actions_factory

    def wait_until(self, target):
        while target is not None:
            remaining = (target - self.runtime.now()).total_seconds()
            if remaining <= 0:
                return
            self.runtime.sleep(min(remaining, 1))

    def execute_round(self):
        checkpoint = self.store.state.checkpoint
        not_before = action_not_before(checkpoint, self.store.state.farm)
        recovery_at = checkpoint.action_sent_at if checkpoint.already_sent else None
        self.store.begin_round()
        started_at = self.runtime.now()
        primary_error = None
        try:
            self.runtime.prepare_device()
            self.navigation_factory(
                self.runtime, allow_harvest_recovery=recovery_at is not None,
            ).run()
            result = self.actions_factory(
                self.runtime, PersistentActionGuard(self.store, self.runtime.now),
                not_before=not_before, resume_after_action_at=recovery_at,
            ).run()
            preparation_seconds = 120 if result.recovered_action else max(
                1, int((result.ready_at - started_at).total_seconds()),
            )
            schedule = plan_after_action(
                self.store, result, self.runtime.now(), preparation_seconds + 15,
            )
            if result.harvested:
                self.runtime.record_harvest(result.harvest_info)
            if schedule is not None:
                purpose = "浇水" if schedule.reason == "WATERING" else "成熟收获"
                self.runtime.log(
                    f"周期：{schedule.cycle_minutes}分钟"
                    f"{'（估算）' if schedule.cycle_estimated else ''}；"
                    f"成熟：{schedule.observed_maturity_at:%Y-%m-%d %H:%M:%S}"
                )
                self.runtime.log(
                    f"准备启动：{schedule.wake_at:%Y-%m-%d %H:%M:%S}；"
                    f"到点点击：{schedule.target_at:%Y-%m-%d %H:%M:%S}（{purpose}）"
                )
        except BaseException as error:
            primary_error = error
            raise
        finally:
            try:
                self.runtime.cleanup_round()
            except Exception as cleanup_error:
                if primary_error is None:
                    raise
                self.runtime.log(f"本轮清理也失败：{cleanup_error}")
        # A cleanup failure leaves the sent boundary intact for observation recovery.
        self.store.finish_round(schedule)
        return schedule

    def run(self, max_rounds=None):
        completed_this_run = 0
        if self.store.state.checkpoint.phase == "ERROR":
            # An explicit restart permits another observation attempt, not replay.
            self.store.update_checkpoint(phase="RUNNING", consecutive_failures=0)
        while max_rounds is None or completed_this_run < max_rounds:
            checkpoint = self.store.state.checkpoint
            self.wait_until(checkpoint.wake_at)
            self.runtime.log(f"第 {checkpoint.completed_rounds + 1} 轮开始")
            try:
                schedule = self.execute_round()
            except Exception as error:
                self.runtime.log(f"自动化失败：{error}")
                try:
                    self.runtime.save_diagnostic("round_failure", {"error": str(error)})
                except Exception as diagnostic_error:
                    self.runtime.log(f"保存失败现场失败：{diagnostic_error}")
                if not self.store.retry(error, self.runtime.now()):
                    raise RuntimeError("连续失败5次，已停止；发送记录保留，重启时仍先复查") from error
                self.runtime.log("60秒后重试；已发送的务农动作不会重复执行")
                continue
            completed_this_run += 1
            if schedule is None:
                self.runtime.log("土地为空，本次任务结束")
                return
