"""Regression cases ported from the tested Android 0.3.20 farming rules."""

from datetime import datetime, timedelta
import unittest

from farm_schedule import calculate_schedule, evaluate_watering, parse_farmland


START = datetime(2026, 9, 22, 10)
CYCLES = (5, 60, 480, 960, 1920)


def at(minutes=0, seconds=0, microseconds=0):
    return START + timedelta(minutes=minutes, seconds=seconds, microseconds=microseconds)


class FarmlandParserTests(unittest.TestCase):
    def test_known_maturity_formats(self):
        for text, hour, minute in (
            ("12:00成熟", 12, 0), ("12：00 成熟", 12, 0),
            ("12点00分成熟", 12, 0), ("明天 00：02成熟", 0, 2),
            ("成熟时间 18:25 级", 18, 25), ("葛笋 14:215成熟", 14, 21),
        ):
            with self.subTest(text=text):
                reading = parse_farmland(text, START)
                self.assertEqual("planted", reading.kind)
                self.assertEqual((hour, minute), (reading.maturity_at.hour, reading.maturity_at.minute))
                self.assertEqual(59, reading.precision_seconds)
                self.assertEqual(text, reading.raw_text)
                self.assertEqual(START, reading.observed_at)

    def test_today_tomorrow_and_day_after_are_anchored_to_observation_date(self):
        observed = datetime(2026, 9, 23, 0, 1)
        for word, days in (("今天", 0), ("明天", 1), ("后天", 2)):
            with self.subTest(word=word):
                reading = parse_farmland(word + "08:30成熟", observed)
                self.assertEqual(datetime(2026, 9, 23 + days, 8, 30), reading.maturity_at)

    def test_relative_times_preserve_seconds_and_cross_midnight(self):
        observed = datetime(2026, 9, 22, 23, 30, 12, 300000)
        for text, minutes in (
            ("2小时15分钟后成熟", 135), ("2时15分后成熟", 135),
            ("1小时后成熟", 60), ("1分钟后成熟", 1),
        ):
            with self.subTest(text=text):
                reading = parse_farmland(text, observed)
                self.assertEqual("planted", reading.kind)
                self.assertEqual((observed + timedelta(minutes=minutes)).replace(microsecond=0), reading.maturity_at)
                self.assertEqual(0, reading.precision_seconds)

    def test_empty_farmland_takes_precedence_over_maturity(self):
        for text in ("农田 2级", "农 田", "农田 2级 12:00成熟", "农田 已成熟"):
            with self.subTest(text=text):
                self.assertEqual("empty", parse_farmland(text, START).kind)

    def test_mature_labels(self):
        for text in ("当前作物 可收获", "作物已成熟"):
            with self.subTest(text=text):
                self.assertEqual("mature", parse_farmland(text, START).kind)

    def test_planted_card_keeps_ocr_evidence(self):
        text = "变异 番茄 3 满级 0/3 1分钟后成熟 幽蓝"
        reading = parse_farmland(text, START)
        self.assertEqual("planted", reading.kind)
        self.assertEqual(text, reading.raw_text)
        self.assertEqual(at(1), reading.maturity_at)

    def test_rejects_illegal_or_unrelated_times(self):
        for text in (
            "25:61成熟", "成熟时间未知", "聊天消息 12:30", "12点00分", "葛笋 14:215",
            "聊天12:30 这是与土地卡无关的一长段文字 当前作物即将成熟", "可升级", "0分钟后成熟",
        ):
            with self.subTest(text=text):
                reading = parse_farmland(text, START)
                self.assertEqual("unknown", reading.kind)
                self.assertIsNone(reading.maturity_at)
                self.assertTrue(reading.reason)

    def test_ocr_normalizes_letter_zero_and_line_breaks(self):
        reading = parse_farmland("今天\n1O：Ｏo 成熟", START)
        self.assertEqual(START, reading.maturity_at)

    def test_implicit_cross_day_maturity(self):
        observed = datetime(2026, 9, 22, 23, 58)
        self.assertEqual(datetime(2026, 9, 23, 0, 2), parse_farmland("00:02成熟", observed).maturity_at)

    def test_current_minute_stays_same_day(self):
        self.assertEqual(START, parse_farmland("10:00成熟", at(seconds=50)).maturity_at)

    def test_exact_one_minute_boundary_stays_same_day(self):
        self.assertEqual(START, parse_farmland("10:00成熟", at(1)).maturity_at)
        self.assertEqual(START + timedelta(days=1), parse_farmland("10:00成熟", at(1, microseconds=1)).maturity_at)

    def test_explicit_today_never_silently_becomes_tomorrow(self):
        self.assertEqual(START, parse_farmland("今天10:00成熟", at(60)).maturity_at)


class ScheduleTests(unittest.TestCase):
    def schedule(self, water=START, attempt=None, maturity=None, now=None, **kwargs):
        attempt = water if attempt is None else attempt
        return calculate_schedule(
            first_water_at=attempt,
            observed_maturity_at=at(55) if maturity is None else maturity,
            now=water if now is None else now,
            stored_cycle_minutes=60,
            batch_started_at=START,
            last_confirmed_watering_at=water,
            last_attempt_at=attempt,
            **kwargs,
        )

    def test_all_crop_cycles_follow_four_ideal_waterings_without_reclassification(self):
        for cycle in CYCLES:
            with self.subTest(cycle=cycle):
                total = cycle * 60
                last_water = START
                maturity = at(seconds=total * 11 // 12)
                for offset in (total // 3, total * 2 // 3, total * 11 // 15):
                    result = calculate_schedule(
                        last_water, maturity, last_water + timedelta(seconds=1),
                        stored_cycle_minutes=cycle, batch_started_at=START,
                        last_confirmed_watering_at=last_water,
                    )
                    next_water = at(seconds=offset)
                    self.assertEqual(next_water, result.target_at)
                    self.assertEqual(next_water, result.next_watering_at)
                    self.assertEqual("WATERING", result.reason)
                    self.assertEqual(cycle, result.cycle_minutes)
                    self.assertFalse(result.cycle_estimated)
                    self.assertEqual(START, result.batch_started_at)
                    elapsed = min((next_water - last_water).total_seconds(), total // 3)
                    maturity -= timedelta(seconds=int(elapsed) // 4)
                    last_water = next_water
                self.assertEqual(last_water, maturity)

    def test_fresh_batch_recognizes_every_cycle_after_first_water(self):
        for cycle in CYCLES:
            with self.subTest(cycle=cycle):
                result = calculate_schedule(START, at(seconds=cycle * 60 * 11 // 12 - 37), at(seconds=15), fresh_batch=True)
                self.assertEqual(cycle, result.cycle_minutes)
                self.assertFalse(result.cycle_estimated)

    def test_fresh_batch_replaces_previous_crop_and_start(self):
        result = calculate_schedule(START, at(55), START, stored_cycle_minutes=480, batch_started_at=at(-300), fresh_batch=True)
        self.assertEqual(60, result.cycle_minutes)
        self.assertEqual(START, result.batch_started_at)
        self.assertFalse(result.cycle_estimated)

    def test_unrecognized_fresh_remainder_is_estimated(self):
        result = calculate_schedule(START, at(30), START, fresh_batch=True)
        self.assertEqual(60, result.cycle_minutes)
        self.assertTrue(result.cycle_estimated)

    def test_unknown_mid_batch_uses_containing_cycle_and_is_estimated(self):
        result = calculate_schedule(START, at(25), START)
        self.assertEqual(60, result.cycle_minutes)
        self.assertTrue(result.cycle_estimated)

    def test_keeps_stored_long_cycle_with_short_remainder(self):
        result = calculate_schedule(START, at(25), START, stored_cycle_minutes=480, batch_started_at=at(-420))
        self.assertEqual(480, result.cycle_minutes)
        self.assertEqual(at(-420), result.batch_started_at)
        self.assertFalse(result.cycle_estimated)

    def test_stored_estimate_does_not_become_confirmed(self):
        self.assertTrue(self.schedule(stored_cycle_estimated=True).cycle_estimated)

    def test_early_arrival_retains_target_after_wake_window_passes(self):
        result = self.schedule(now=at(19))
        self.assertEqual(at(20), result.target_at)
        self.assertEqual(at(19), result.wake_at)

    def test_late_arrival_retains_overdue_water_for_immediate_wake(self):
        result = self.schedule(now=at(22))
        self.assertEqual(at(20), result.target_at)
        self.assertEqual(at(22), result.wake_at)
        self.assertEqual("WATERING", result.reason)

    def test_delayed_water_shifts_next_full_water_from_confirmed_time(self):
        result = self.schedule(water=at(22), maturity=at(50), now=at(23))
        self.assertEqual(at(42), result.target_at)
        self.assertEqual(at(40), result.watering3_at)

    def test_final_water_accounts_for_earlier_delay(self):
        result = self.schedule(water=at(42), maturity=at(45), now=at(42, 10))
        self.assertEqual(at(44, 24), result.target_at)
        self.assertEqual("WATERING", result.reason)

    def test_extra_effective_water_restarts_full_water_interval(self):
        result = self.schedule(water=at(10), maturity=at(52, 30), now=at(11))
        self.assertEqual(at(30), result.target_at)

    def test_ineffective_attempt_only_applies_minimum_interval(self):
        result = self.schedule(water=at(10), attempt=at(29), maturity=at(52, 30), now=at(29, 10))
        self.assertEqual(at(31), result.target_at)

    def test_natural_maturity_when_minimum_interval_cannot_be_met(self):
        result = self.schedule(maturity=at(seconds=90))
        self.assertEqual(at(seconds=90), result.target_at)
        self.assertEqual("MATURITY", result.reason)
        self.assertIsNone(result.next_watering_at)

    def test_minute_reading_already_due_does_not_schedule_water(self):
        result = self.schedule(maturity=START, now=at(seconds=20))
        self.assertEqual(START, result.target_at)
        self.assertEqual(at(seconds=20), result.wake_at)
        self.assertEqual("MATURITY", result.reason)

    def test_due_minute_with_precision_waits_only_for_upper_bound(self):
        result = self.schedule(maturity=START, now=at(seconds=20), maturity_precision_seconds=59)
        self.assertEqual(at(seconds=59), result.target_at)
        self.assertEqual("MATURITY", result.reason)

    def test_final_water_rounds_up_including_fractional_seconds(self):
        result = self.schedule(maturity=at(seconds=181, microseconds=900000))
        self.assertEqual(at(seconds=146), result.target_at)
        self.assertLessEqual(result.target_at, result.observed_maturity_at)

    def test_minute_precision_protects_final_water_without_changing_observation(self):
        result = self.schedule(water=at(40), maturity=at(45), now=at(41), maturity_precision_seconds=59)
        self.assertEqual(at(45), result.observed_maturity_at)
        self.assertEqual(at(44, 48), result.target_at)

    def test_minute_precision_does_not_change_fresh_classification(self):
        for cycle in CYCLES:
            with self.subTest(cycle=cycle):
                result = calculate_schedule(START, at(seconds=cycle * 60 * 11 // 12 - 50), START, fresh_batch=True, maturity_precision_seconds=59)
                self.assertEqual(cycle, result.cycle_minutes)
                self.assertFalse(result.cycle_estimated)

    def test_wake_lead_changes_startup_without_changing_water_target(self):
        early = self.schedule(wake_lead_seconds=120)
        later = self.schedule(wake_lead_seconds=61)
        self.assertEqual(early.target_at, later.target_at)
        self.assertEqual(at(18), early.wake_at)
        self.assertEqual(at(18, 59), later.wake_at)

    def test_invalid_configuration_is_rejected(self):
        for kwargs in ({"wake_lead_seconds": -1}, {"maturity_precision_seconds": -1}, {"stored_cycle_minutes": 10}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                calculate_schedule(START, at(55), START, **kwargs)

    def test_final_target_never_exceeds_maturity_upper_bound(self):
        for cycle in CYCLES:
            for seconds in (-1, 0, 1, 59, 90, cycle * 60):
                with self.subTest(cycle=cycle, seconds=seconds):
                    result = calculate_schedule(START, at(seconds=seconds), START, stored_cycle_minutes=cycle, maturity_precision_seconds=59)
                    self.assertLessEqual(result.target_at, at(seconds=seconds + 59))
                    self.assertGreaterEqual(result.wake_at, START)


class WateringEvidenceTests(unittest.TestCase):
    def evidence(self, **kwargs):
        defaults = dict(
            cycle_minutes=60, batch_started_at=START,
            previous_maturity_at=at(55), previous_observed_at=START,
            observed_maturity_at=at(55), observed_at=at(20),
            last_confirmed_watering_at=START, last_attempt_at=at(20),
        )
        defaults.update(kwargs)
        return evaluate_watering(**defaults)

    def test_all_cycles_accept_full_reduction_without_fixed_ten_minute_limit(self):
        for cycle in CYCLES:
            with self.subTest(cycle=cycle):
                before = at(seconds=cycle * 60 * 11 // 12)
                observed = at(seconds=cycle * 60 // 3 + 10)
                result = self.evidence(
                    cycle_minutes=cycle, previous_maturity_at=before,
                    observed_maturity_at=before - timedelta(seconds=cycle * 60 // 12),
                    observed_at=observed, last_attempt_at=observed,
                )
                self.assertTrue(result.batch_compatible)
                self.assertEqual(cycle >= 60, result.watering_confirmed)

    def test_unchanged_maturity_does_not_confirm_water(self):
        result = self.evidence()
        self.assertTrue(result.batch_compatible)
        self.assertFalse(result.watering_confirmed)

    def test_minute_precision_cannot_confirm_one_minute_or_short_crop_reduction(self):
        result = self.evidence(observed_maturity_at=at(54))
        self.assertTrue(result.batch_compatible)
        self.assertFalse(result.watering_confirmed)
        short = self.evidence(
            cycle_minutes=5, previous_maturity_at=at(seconds=275),
            observed_maturity_at=at(seconds=250), observed_at=at(seconds=110),
            last_attempt_at=at(seconds=110),
        )
        self.assertTrue(short.batch_compatible)
        self.assertFalse(short.watering_confirmed)

    def test_clear_advancement_confirms_water(self):
        result = self.evidence(observed_maturity_at=at(52))
        self.assertTrue(result.batch_compatible)
        self.assertTrue(result.watering_confirmed)

    def test_later_maturity_beyond_one_minute_invalidates_batch(self):
        self.assertFalse(self.evidence(observed_maturity_at=at(56, 1)).batch_compatible)
        self.assertTrue(self.evidence(observed_maturity_at=at(56)).batch_compatible)

    def test_reduction_over_single_water_cap_and_tolerance_invalidates_batch(self):
        self.assertFalse(self.evidence(observed_maturity_at=at(48)).batch_compatible)
        self.assertTrue(self.evidence(observed_maturity_at=at(49)).batch_compatible)

    def test_state_older_than_two_cycles_is_incompatible(self):
        self.assertFalse(self.evidence(observed_at=at(120, 1)).batch_compatible)
        self.assertTrue(self.evidence(observed_at=at(120)).batch_compatible)

    def test_time_reversal_is_incompatible(self):
        for kwargs in (
            {"previous_observed_at": at(30)}, {"previous_observed_at": at(seconds=-1)},
            {"last_confirmed_watering_at": at(21)}, {"last_attempt_at": at(seconds=-1)},
            {"last_confirmed_watering_at": at(10), "last_attempt_at": at(9)},
        ):
            with self.subTest(kwargs=kwargs):
                self.assertFalse(self.evidence(**kwargs).batch_compatible)

    def test_absent_previous_maturity_cannot_confirm_water(self):
        result = self.evidence(previous_maturity_at=None)
        self.assertTrue(result.batch_compatible)
        self.assertFalse(result.watering_confirmed)

    def test_attempt_before_effective_minimum_interval_does_not_confirm(self):
        result = self.evidence(observed_maturity_at=at(52), last_attempt_at=at(seconds=119))
        self.assertTrue(result.batch_compatible)
        self.assertFalse(result.watering_confirmed)

    def test_unexplained_reduction_not_attributed_to_current_attempt(self):
        result = self.evidence(observed_maturity_at=at(52), last_attempt_at=at(5))
        self.assertTrue(result.batch_compatible)
        self.assertFalse(result.watering_confirmed)

    def test_extra_water_with_plausible_partial_reduction_is_confirmed(self):
        result = self.evidence(observed_maturity_at=at(52, 30), last_attempt_at=at(10))
        self.assertTrue(result.batch_compatible)
        self.assertTrue(result.watering_confirmed)

    def test_stored_cycle_cannot_contain_maturity_of_longer_crop(self):
        self.assertFalse(self.evidence(cycle_minutes=5, previous_maturity_at=None).batch_compatible)

    def test_unsupported_cycle_is_incompatible(self):
        self.assertFalse(self.evidence(cycle_minutes=10).batch_compatible)

    def test_previous_maturity_outside_cycle_is_incompatible(self):
        for previous in (at(seconds=-61), at(61, 1)):
            with self.subTest(previous=previous):
                self.assertFalse(self.evidence(previous_maturity_at=previous).batch_compatible)

    def test_observed_maturity_outside_cycle_is_incompatible(self):
        for maturity in (at(seconds=-61), at(61, 1)):
            with self.subTest(maturity=maturity):
                self.assertFalse(self.evidence(observed_maturity_at=maturity).batch_compatible)


if __name__ == "__main__":
    unittest.main()
