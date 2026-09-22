"""Pure farming rules shared in behavior with the tested Android 0.3.20 app.

Observations and confirmed waterings are deliberately separate: a button tap
does not prove that the crop received water. This module has no device or OCR
engine dependency, so its timing decisions can be checked without a phone.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
import re
from typing import Optional


CROP_CYCLES_MINUTES = (5, 60, 480, 960, 1920)
OCR_TOLERANCE_SECONDS = 60
_ABSOLUTE_TIME = re.compile(
    r"(?:(今天|明天|后天)\s*)?(\d{1,2})\s*(?:[:：点])\s*(\d{1,2})(?:\s*分)?"
)
_RELATIVE_TIME = re.compile(
    r"(?:(\d+)\s*(?:小时|时))?\s*(?:(\d+)\s*(?:分钟|分))?\s*后\s*成熟"
)
_EMPTY_FARMLAND = re.compile(r"农\s*田(?:\s*(\d+)\s*级)?")


@dataclass(frozen=True)
class FarmlandReading:
    kind: str
    raw_text: str
    observed_at: datetime
    maturity_at: Optional[datetime] = None
    precision_seconds: int = 59
    reason: str = ""


@dataclass(frozen=True)
class FarmSchedule:
    cycle_minutes: int
    observed_maturity_at: datetime
    batch_started_at: datetime
    watering2_at: datetime
    watering3_at: datetime
    watering4_at: datetime
    next_watering_at: Optional[datetime]
    target_at: datetime
    wake_at: datetime
    reason: str
    cycle_estimated: bool = False


@dataclass(frozen=True)
class WateringEvidence:
    batch_compatible: bool
    watering_confirmed: bool
    reason: str


def _normalize(raw_text: str) -> str:
    return re.sub(r"\s+", " ", raw_text.replace("Ｏ", "0").replace("O", "0").replace("o", "0")).strip()


def parse_farmland(raw_text: str, observed_at: datetime) -> FarmlandReading:
    """Read a farmland card using the Android parser and day-resolution rules.

    HH:mm is kept at the beginning of the displayed minute, with a separate
    59-second uncertainty. Relative times retain the observation's seconds.
    """
    normalized = _normalize(raw_text)
    # Empty-card evidence wins, as in FarmlandStateParser.
    if _EMPTY_FARMLAND.search(normalized):
        return FarmlandReading("empty", raw_text, observed_at)
    if "可收获" in normalized or "已成熟" in normalized:
        return FarmlandReading("mature", raw_text, observed_at)

    relative = _RELATIVE_TIME.search(normalized)
    if relative:
        hours = int(relative.group(1) or 0)
        minutes = int(relative.group(2) or 0)
        total_minutes = hours * 60 + minutes
        if total_minutes > 0:
            maturity = (observed_at + timedelta(minutes=total_minutes)).replace(microsecond=0)
            return FarmlandReading("planted", raw_text, observed_at, maturity, 0)

    match = next(
        (
            candidate
            for candidate in _ABSOLUTE_TIME.finditer(normalized)
            if "成熟" in normalized[max(0, candidate.start() - 8):candidate.end() + 8]
        ),
        None,
    )
    if match is None:
        return FarmlandReading("unknown", raw_text, observed_at, reason="未找到成熟时间")
    day_text, hour_text, minute_text = match.groups()
    hour, minute = int(hour_text), int(minute_text)
    if not 0 <= hour <= 23 or not 0 <= minute <= 59:
        return FarmlandReading("unknown", raw_text, observed_at, reason="时间超出有效范围")
    day_offset = {"明天": 1, "后天": 2}.get(day_text, 0)
    maturity = (observed_at + timedelta(days=day_offset)).replace(
        hour=hour, minute=minute, second=0, microsecond=0
    )
    within_current_minute = abs(observed_at - maturity) <= timedelta(minutes=1)
    if day_offset == 0 and "今天" not in raw_text and maturity < observed_at and not within_current_minute:
        maturity += timedelta(days=1)
    return FarmlandReading("planted", raw_text, observed_at, maturity)


def calculate_schedule(
    first_water_at: datetime,
    observed_maturity_at: datetime,
    now: datetime,
    stored_cycle_minutes: Optional[int] = None,
    batch_started_at: Optional[datetime] = None,
    wake_lead_seconds: int = 120,
    last_confirmed_watering_at: Optional[datetime] = None,
    last_attempt_at: Optional[datetime] = None,
    fresh_batch: bool = False,
    stored_cycle_estimated: bool = False,
    maturity_precision_seconds: int = 0,
) -> FarmSchedule:
    """Compute the next actual watering, or natural maturity when it is sooner.

    Stored crop identity must first be validated by ``evaluate_watering``.
    Ideal second/third/fourth times describe the original batch; actual targets
    use the latest confirmed watering and any attempt's minimum interval.
    """
    if wake_lead_seconds < 0 or maturity_precision_seconds < 0:
        raise ValueError("Wake lead and maturity precision must be non-negative")
    if stored_cycle_minutes is not None and stored_cycle_minutes not in CROP_CYCLES_MINUTES:
        raise ValueError("Unsupported stored crop cycle: {}".format(stored_cycle_minutes))
    last_confirmed_watering_at = last_confirmed_watering_at or first_water_at
    last_attempt_at = last_attempt_at or first_water_at
    batch_start = first_water_at if fresh_batch else (batch_started_at or first_water_at)
    remaining_seconds = (observed_maturity_at - first_water_at) // timedelta(seconds=1)
    fresh_cycle = None
    if fresh_batch:
        closest = min(
            CROP_CYCLES_MINUTES,
            key=lambda cycle: abs(remaining_seconds - cycle * 60 * 11 // 12),
        )
        if abs(remaining_seconds - closest * 60 * 11 // 12) <= OCR_TOLERANCE_SECONDS:
            fresh_cycle = closest
    if fresh_cycle is not None:
        cycle, estimated = fresh_cycle, False
    elif not fresh_batch and stored_cycle_minutes is not None:
        cycle, estimated = stored_cycle_minutes, stored_cycle_estimated
    else:
        cycle = next(
            (candidate for candidate in CROP_CYCLES_MINUTES
             if remaining_seconds <= candidate * 60 + OCR_TOLERANCE_SECONDS),
            CROP_CYCLES_MINUTES[-1],
        )
        estimated = True

    total_seconds = cycle * 60
    maturity_upper_bound = observed_maturity_at + timedelta(seconds=maturity_precision_seconds)
    full_water_at = last_confirmed_watering_at + timedelta(seconds=total_seconds // 3)
    remaining_from_water = maturity_upper_bound - last_confirmed_watering_at
    # ceil(4 * remaining / 5), including fractional seconds, without floats.
    remaining_microseconds = max(0, remaining_from_water // timedelta(microseconds=1))
    finish_seconds = (remaining_microseconds * 4 + 5_000_000 - 1) // 5_000_000
    finish_water_at = last_confirmed_watering_at + timedelta(seconds=finish_seconds)
    effective_after = max(last_confirmed_watering_at, last_attempt_at) + timedelta(seconds=total_seconds // 30)
    watering_target = max(min(full_water_at, finish_water_at), effective_after)
    next_watering = (
        watering_target
        if observed_maturity_at > now and watering_target < maturity_upper_bound
        else None
    )
    target = next_watering if next_watering is not None else maturity_upper_bound
    assert target <= maturity_upper_bound, "Schedule target exceeded OCR maturity safety limit"
    return FarmSchedule(
        cycle_minutes=cycle,
        observed_maturity_at=observed_maturity_at,
        batch_started_at=batch_start,
        watering2_at=batch_start + timedelta(seconds=total_seconds // 3),
        watering3_at=batch_start + timedelta(seconds=total_seconds * 2 // 3),
        watering4_at=batch_start + timedelta(seconds=total_seconds * 11 // 15),
        next_watering_at=next_watering,
        target_at=target,
        wake_at=max(target - timedelta(seconds=wake_lead_seconds), now),
        reason="WATERING" if next_watering is not None else "MATURITY",
        cycle_estimated=estimated,
    )


def evaluate_watering(
    cycle_minutes: int,
    batch_started_at: datetime,
    previous_maturity_at: Optional[datetime],
    previous_observed_at: datetime,
    observed_maturity_at: datetime,
    observed_at: datetime,
    last_confirmed_watering_at: Optional[datetime] = None,
    last_attempt_at: Optional[datetime] = None,
) -> WateringEvidence:
    """Validate crop continuity and confirm only a plausible visible reduction."""
    def incompatible(reason: str) -> WateringEvidence:
        return WateringEvidence(False, False, reason)

    if cycle_minutes not in CROP_CYCLES_MINUTES:
        return incompatible("作物周期不受支持")
    if (
        batch_started_at > previous_observed_at
        or previous_observed_at > observed_at
        or (last_confirmed_watering_at is not None and not batch_started_at <= last_confirmed_watering_at <= observed_at)
        or (last_attempt_at is not None and not batch_started_at <= last_attempt_at <= observed_at)
        or (last_confirmed_watering_at is not None and last_attempt_at is not None and last_confirmed_watering_at > last_attempt_at)
    ):
        return incompatible("作物记录时间顺序异常")
    cycle_seconds = cycle_minutes * 60
    if observed_at - batch_started_at > timedelta(seconds=cycle_seconds * 2):
        return incompatible("作物记录已超过两个原始周期")
    tolerance = timedelta(seconds=OCR_TOLERANCE_SECONDS)
    earliest = batch_started_at - tolerance
    latest = batch_started_at + timedelta(seconds=cycle_seconds) + tolerance
    if not earliest <= observed_maturity_at <= latest:
        return incompatible("成熟时间超出当前作物周期")
    if previous_maturity_at is None:
        return WateringEvidence(True, False, "缺少上次成熟时间，尚不能确认浇水生效")
    if not earliest <= previous_maturity_at <= latest:
        return incompatible("上次成熟时间超出当前作物周期")
    advancement = previous_maturity_at - observed_maturity_at
    if advancement < -tolerance:
        return incompatible("成熟时间明显变晚，需重新确认作物")
    if advancement > timedelta(seconds=cycle_seconds // 12) + tolerance:
        return incompatible("成熟时间变化超过单次浇水上限")
    if last_confirmed_watering_at is not None and last_attempt_at is not None:
        since_confirmed = last_attempt_at - last_confirmed_watering_at
        if since_confirmed < timedelta(seconds=cycle_seconds // 30):
            return WateringEvidence(True, False, "未达到最短有效浇水间隔，保留上次有效浇水时间")
        possible_microseconds = min(since_confirmed, timedelta(seconds=cycle_seconds // 3)) // timedelta(microseconds=1)
        # Compare the exact quarter-interval without timedelta's rounding.
        if (advancement - tolerance) // timedelta(microseconds=1) * 4 > possible_microseconds:
            return WateringEvidence(True, False, "提前幅度超过本次间隔可产生的减时，尚不能归因于本次浇水")
    confirmed = advancement > tolerance
    return WateringEvidence(
        True,
        confirmed,
        "成熟时间已明显提前，确认浇水生效" if confirmed else "变化未超过分钟误差，保留上次有效浇水时间",
    )
