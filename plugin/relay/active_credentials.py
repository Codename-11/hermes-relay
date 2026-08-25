"""Secret-free active credential snapshots shared by Gateway hooks and Relay.

The Gateway and Relay server commonly run in separate processes.  Hooks record
only the stable pool-entry id selected by a live session; provider tokens never
leave the owning Gateway process.
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from pathlib import Path
from collections.abc import Iterable
from typing import Any

_STATE_FILE = "hermes-relay-active-credentials.json"
_MAX_SESSIONS = 64
_MAX_AGE_SECONDS = 7 * 24 * 60 * 60
_LOCK = threading.Lock()


def state_path(profile_home: Path) -> Path:
    return profile_home / _STATE_FILE


def _read(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError, TypeError):
        return {"schema_version": 1, "sessions": {}}
    sessions = payload.get("sessions") if isinstance(payload, dict) else None
    return {
        "schema_version": 1,
        "sessions": sessions if isinstance(sessions, dict) else {},
    }


def record_active_credential(
    profile_home: Path,
    *,
    session_id: str,
    provider_id: str,
    credential_id: str,
) -> None:
    """Atomically record a bounded, secret-free active credential mapping."""
    record_active_credential_aliases(
        profile_home,
        session_ids=(session_id,),
        provider_id=provider_id,
        credential_id=credential_id,
    )


def record_active_credential_aliases(
    profile_home: Path,
    *,
    session_ids: Iterable[str],
    provider_id: str,
    credential_id: str,
) -> None:
    """Atomically map every authoritative Gateway/session alias to one entry."""
    aliases = {
        str(session_id or "").strip()[:160]
        for session_id in session_ids
        if str(session_id or "").strip()
    }
    provider = str(provider_id or "").strip()[:80]
    credential = str(credential_id or "").strip()[:160]
    if not aliases or not provider or not credential:
        return

    path = state_path(profile_home)
    now = time.time()
    with _LOCK:
        payload = _read(path)
        sessions = payload["sessions"]
        for session in aliases:
            sessions[session] = {
                "provider_id": provider,
                "credential_id": credential,
                "observed_at": now,
            }
        retained = sorted(
            (
                (key, row)
                for key, row in sessions.items()
                if isinstance(row, dict)
                and isinstance(row.get("observed_at"), (int, float))
                and now - float(row["observed_at"]) <= _MAX_AGE_SECONDS
            ),
            key=lambda item: float(item[1]["observed_at"]),
            reverse=True,
        )[:_MAX_SESSIONS]
        payload["sessions"] = dict(retained)

        path.parent.mkdir(parents=True, exist_ok=True)
        fd, raw_tmp = tempfile.mkstemp(prefix=f".{_STATE_FILE}.", dir=str(path.parent))
        tmp = Path(raw_tmp)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, separators=(",", ":"), sort_keys=True)
                handle.flush()
                os.fsync(handle.fileno())
            try:
                os.chmod(tmp, 0o600)
            except OSError:
                pass
            os.replace(tmp, path)
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass


def read_active_credential(
    profile_home: Path,
    *,
    session_id: str | None,
    provider_id: str,
) -> dict[str, Any] | None:
    """Return the exact session mapping, or ``None`` when it is not proven."""
    session = str(session_id or "").strip()
    if not session:
        return None
    row = _read(state_path(profile_home))["sessions"].get(session)
    if not isinstance(row, dict) or row.get("provider_id") != provider_id:
        return None
    observed_at = row.get("observed_at")
    if not isinstance(observed_at, (int, float)):
        return None
    if time.time() - float(observed_at) > _MAX_AGE_SECONDS:
        return None
    credential_id = str(row.get("credential_id") or "").strip()
    if not credential_id:
        return None
    return {"credential_id": credential_id, "observed_at": float(observed_at)}
