"""Per-device durable checkpoints. Persist intent before irreversible ADB input."""
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field, replace
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import tempfile


@dataclass(frozen=True)
class FarmState:
    cycle_minutes: int
    batch_started_at: datetime
    observed_maturity_at: datetime
    updated_at: datetime
    last_confirmed_watering_at: datetime | None = None
    last_attempt_at: datetime | None = None
    cycle_estimated: bool = True
    confirmed_watering_count: int = 0


@dataclass(frozen=True)
class Checkpoint:
    phase: str = "IDLE"
    completed_rounds: int = 0
    target_at: datetime | None = None
    wake_at: datetime | None = None
    reason: str | None = None
    action: str = "NONE"
    action_sent_at: datetime | None = None
    harvest_observed: bool = False
    consecutive_failures: int = 0
    last_error: str | None = None

    @property
    def already_sent(self):
        return self.action != "NONE"

    @property
    def maturity_harvest_sent(self):
        return self.action in ("MATURITY_HARVEST_SENT", "MATURITY_HARVEST_CONFIRMED")


@dataclass(frozen=True)
class SavedState:
    checkpoint: Checkpoint = field(default_factory=Checkpoint)
    farm: FarmState | None = None


def _encode(value):
    if isinstance(value, datetime):
        return {"epoch_seconds": value.timestamp()}
    if isinstance(value, dict):
        return {key: _encode(item) for key, item in value.items()}
    return value


def _decode(value):
    if isinstance(value, dict):
        if set(value) == {"epoch_seconds"}:
            return datetime.fromtimestamp(value["epoch_seconds"])
        return {key: _decode(item) for key, item in value.items()}
    return value


class StateStore:
    def __init__(self, directory, device):
        if not device:
            raise ValueError("必须先选择设备，再加载自动务农记录")
        self.directory = Path(directory)
        self.device = device
        identity = hashlib.sha256(device.encode("utf-8")).hexdigest()[:24]
        self.path = self.directory / f"farm-{identity}.json"
        self.lock_path = self.directory / f"farm-{identity}.lock"
        self.state = self._load()

    def _load(self):
        if not self.path.exists():
            return SavedState()
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if data["schema"] != 1 or data["device"] != self.device:
                raise ValueError("状态版本或设备不一致")
            checkpoint = Checkpoint(**_decode(data["checkpoint"]))
            if checkpoint.phase not in {"IDLE", "RUNNING", "WAITING", "COMPLETED", "ERROR"}:
                raise ValueError("未知运行状态")
            if checkpoint.action not in {
                "NONE", "ONE_CLICK_SENT", "ONE_CLICK_CONFIRMED",
                "MATURITY_HARVEST_SENT", "MATURITY_HARVEST_CONFIRMED",
            }:
                raise ValueError("未知动作状态")
            if checkpoint.already_sent and not isinstance(checkpoint.action_sent_at, datetime):
                raise ValueError("缺少已发送动作的时间")
            for value in (checkpoint.target_at, checkpoint.wake_at):
                if value is not None and not isinstance(value, datetime):
                    raise ValueError("无效目标时间")
            farm = FarmState(**_decode(data["farm"])) if data.get("farm") else None
            if farm is not None:
                if farm.cycle_minutes not in (5, 60, 480, 960, 1920):
                    raise ValueError("无效作物周期")
                for value in (farm.batch_started_at, farm.observed_maturity_at, farm.updated_at):
                    if not isinstance(value, datetime):
                        raise ValueError("无效作物时间")
                for value in (farm.last_confirmed_watering_at, farm.last_attempt_at):
                    if value is not None and not isinstance(value, datetime):
                        raise ValueError("无效浇水时间")
            return SavedState(checkpoint, farm)
        except (ValueError, TypeError, KeyError, OverflowError, OSError) as error:
            # Never silently discard a possibly-sent input and reissue it.
            raise ValueError(f"自动务农记录无法安全读取，请保留文件检查：{self.path}") from error

    def save(self, state):
        self.directory.mkdir(parents=True, exist_ok=True)
        data = {"schema": 1, "device": self.device, **_encode(asdict(state))}
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=self.directory, suffix=".tmp", delete=False,
            ) as stream:
                temporary = Path(stream.name)
                json.dump(data, stream, ensure_ascii=False, indent=2, allow_nan=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()
        self.state = state

    def update_checkpoint(self, **changes):
        self.save(replace(self.state, checkpoint=replace(self.state.checkpoint, **changes)))

    def begin_round(self):
        self.update_checkpoint(phase="RUNNING", wake_at=None)

    def save_farm(self, farm):
        self.save(replace(self.state, farm=farm))

    def finish_round(self, schedule):
        previous = self.state.checkpoint
        # Completion and the next target share one durable write.
        self.save(replace(self.state, checkpoint=Checkpoint(
            phase="WAITING" if schedule is not None else "COMPLETED",
            completed_rounds=previous.completed_rounds + 1,
            target_at=schedule.target_at if schedule else None,
            wake_at=schedule.wake_at if schedule else None,
            reason=schedule.reason if schedule else None,
        )))

    def retry(self, error, now):
        failures = self.state.checkpoint.consecutive_failures + 1
        self.update_checkpoint(
            phase="WAITING" if failures < 5 else "ERROR",
            consecutive_failures=failures,
            last_error=str(error)[:1000],
            wake_at=now + timedelta(seconds=60) if failures < 5 else None,
        )
        return failures < 5

    @contextmanager
    def locked(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        with self.lock_path.open("a+b") as stream:
            if stream.tell() == 0:
                stream.write(b"0")
                stream.flush()
            stream.seek(0)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as error:
                raise RuntimeError("此设备已有 Python 务农任务运行") from error
            try:
                self.state = self._load()
                yield self
            finally:
                stream.seek(0)
                if os.name == "nt":
                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class PersistentActionGuard:
    def __init__(self, store, now=datetime.now):
        self.store = store
        self.now = now

    def before_tap(self, target):
        if self.store.state.checkpoint.already_sent:
            return False
        self.store.update_checkpoint(action="ONE_CLICK_SENT", action_sent_at=self.now())
        return True

    def after_tap_accepted(self, accepted_at):
        if self.store.state.checkpoint.action != "ONE_CLICK_SENT":
            raise RuntimeError("主务农确认状态不一致")
        self.store.update_checkpoint(action="ONE_CLICK_CONFIRMED", action_sent_at=accepted_at)

    def before_maturity_harvest(self, target):
        current = self.store.state.checkpoint
        if not current.already_sent or current.maturity_harvest_sent:
            return False
        self.store.update_checkpoint(action="MATURITY_HARVEST_SENT", action_sent_at=self.now())
        return True

    def after_maturity_harvest_accepted(self, accepted_at):
        if self.store.state.checkpoint.action != "MATURITY_HARVEST_SENT":
            raise RuntimeError("同场收获确认状态不一致")
        self.store.update_checkpoint(action="MATURITY_HARVEST_CONFIRMED", action_sent_at=accepted_at)

    def on_harvest_observed(self):
        if not self.store.state.checkpoint.already_sent:
            raise RuntimeError("未发送务农，不能关联收获记录")
        self.store.update_checkpoint(harvest_observed=True)
