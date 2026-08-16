"""Read-only, sanitized diagnostics for the upstream gateway heartbeat."""

from __future__ import annotations

import json
import os
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

DEFAULT_STALE_AFTER_S = 90.0
MAX_EXIT_DIAG_BYTES = 256 * 1024


def hermes_home() -> Path:
    configured = os.environ.get("HERMES_HOME", "").strip()
    return Path(configured) if configured else Path.home() / ".hermes"


def _epoch(value: Any) -> float | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError):
        return None


def _gateway_owner(home: Path) -> tuple[int | None, float | None]:
    try:
        raw = json.loads((home / "gateway.pid").read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None, None
    value = raw.get("pid") if isinstance(raw, dict) else raw
    try:
        pid = int(value)
    except (TypeError, ValueError):
        return None, None
    if not isinstance(raw, dict):
        return pid, None
    try:
        start_time = float(raw["start_time"])
    except (KeyError, TypeError, ValueError):
        start_time = None
    return pid, start_time


def _process_started_at(pid: int) -> float | None:
    """Best-effort epoch start time; psutil is optional in plugin installs."""
    try:
        import psutil  # type: ignore[import-not-found]

        return float(psutil.Process(pid).create_time())
    except Exception:
        return None


def assess_gateway_heartbeat(
    *,
    home: Path | None = None,
    now: float | None = None,
    stale_after_s: float = DEFAULT_STALE_AFTER_S,
    process_started_at: Callable[[int], float | None] = _process_started_at,
) -> dict[str, Any]:
    """Assess upstream's heartbeat without exposing paths, PIDs, or timestamps."""
    root = home if home is not None else hermes_home()
    path = root / "state" / "gateway.heartbeat"
    expected_pid, expected_start = _gateway_owner(root)
    if not path.exists():
        return {
            "status": "legacy" if expected_pid is not None else "missing",
            "supported": False,
        }

    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("heartbeat must be an object")
        pid = int(payload["pid"])
        heartbeat_epoch = _epoch(payload.get("updated_at"))
        start_time = float(payload["start_time"]) if "start_time" in payload else None
        mtime = path.stat().st_mtime
    except (OSError, ValueError, TypeError, KeyError):
        return {"status": "malformed", "supported": True}

    current = time.time() if now is None else float(now)
    if expected_pid is not None and pid != expected_pid:
        return {"status": "pid_mismatch", "supported": True}

    # Current upstream's PID record contains the OS process start time, while
    # the heartbeat records the GatewayRunner start. Compare the PID record to
    # the live process when possible; comparing the two files directly would
    # falsely flag a gateway whose runner initialized more than two seconds
    # after its process began.
    live_start = process_started_at(pid)
    owner_start = live_start if live_start is not None else expected_start
    if expected_start is not None and live_start is not None and abs(expected_start - live_start) > 2.0:
        return {"status": "start_mismatch", "supported": True}
    if start_time is not None and owner_start is not None and start_time + 1.0 < owner_start:
        return {"status": "start_mismatch", "supported": True}

    # Require both the payload timestamp and the atomic file rewrite to be
    # recent. A copied/rewritten stale payload must not look healthy, and a
    # fresh payload whose file stopped advancing is stale as well.
    if heartbeat_epoch is None:
        return {"status": "malformed", "supported": True}
    age = max(0.0, current - min(heartbeat_epoch, mtime))
    return {
        "status": "stale" if age > stale_after_s else "fresh",
        "supported": True,
        "age_seconds": int(age),
    }


def assess_gateway_prior_exit(*, home: Path | None = None) -> dict[str, Any]:
    """Return a bounded label for the gateway life before the current start.

    The upstream exit log can include argv, paths, process details, and memory
    evidence. Relay deliberately emits only the classification and optional
    OOM hint; raw records never cross this boundary.
    """
    root = home if home is not None else hermes_home()
    path = root / "logs" / "gateway-exit-diag.log"
    try:
        size = path.stat().st_size
        with path.open("rb") as handle:
            handle.seek(max(0, size - MAX_EXIT_DIAG_BYTES))
            raw = handle.read(MAX_EXIT_DIAG_BYTES)
    except OSError:
        return {"prior_exit": "unknown"}

    records: list[dict[str, Any]] = []
    for line in raw.decode("utf-8", errors="replace").splitlines():
        try:
            value = json.loads(line)
        except (TypeError, ValueError):
            continue
        if isinstance(value, dict) and isinstance(value.get("tag"), str):
            records.append(value)

    starts = [index for index, record in enumerate(records) if record.get("tag") == "gateway.start"]
    if not starts:
        return {"prior_exit": "unknown"}
    current_start = starts[-1]
    if current_start == 0:
        return {"prior_exit": "unknown"}

    previous = records[current_start - 1]
    if previous.get("tag") == "gateway.previous_unclean_exit":
        result: dict[str, Any] = {"prior_exit": "unclean"}
        if previous.get("suspected_oom") is True:
            result["suspected_oom"] = True
        return result

    clean_exit_tags = {
        "atexit.hook",
        "asyncio.run.return",
        "asyncio.run.SystemExit",
        "asyncio.run.KeyboardInterrupt",
    }
    if previous.get("tag") in clean_exit_tags:
        return {"prior_exit": "clean"}
    return {"prior_exit": "unknown"}
