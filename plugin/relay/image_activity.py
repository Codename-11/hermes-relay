"""Read-only image-generation activity derived from Hermes session history.

This is an optional Relay compatibility surface for clients connected to an
upstream Gateway that does not emit tool lifecycle events when tool progress is
disabled. Hermes' session database remains the source of truth; Relay never
creates or mutates tool state.
"""

from __future__ import annotations

import json
import logging
import sqlite3
from pathlib import Path
from typing import Any

logger = logging.getLogger("hermes_relay.image_activity")

_IMAGE_TOOL_NAME = "image_generate"
_MAX_ROWS = 250


def _image_calls(raw_tool_calls: str | None) -> list[tuple[str, str]]:
    """Return ``(call_id, tool_name)`` pairs for persisted image tool calls."""
    if not raw_tool_calls:
        return []
    try:
        decoded = json.loads(raw_tool_calls)
    except (TypeError, ValueError):
        return []
    if not isinstance(decoded, list):
        return []

    calls: list[tuple[str, str]] = []
    for item in decoded:
        if not isinstance(item, dict):
            continue
        call_id = item.get("id")
        function = item.get("function")
        name = function.get("name") if isinstance(function, dict) else item.get("name")
        if (
            isinstance(call_id, str)
            and call_id
            and isinstance(name, str)
            and name == _IMAGE_TOOL_NAME
        ):
            calls.append((call_id, name))
    return calls


def read_image_activity(
    db_path: Path,
    session_id: str,
    since: float,
) -> list[dict[str, Any]]:
    """Read image tool calls for one session since ``since`` (Unix seconds).

    The SQLite connection is opened read-only and is safe to use against the
    Gateway's WAL database. Malformed rows are ignored so a compatibility
    endpoint can never interfere with the active Hermes turn.
    """
    if not db_path.is_file():
        return []

    try:
        connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    except sqlite3.Error as exc:
        logger.debug("Cannot open Hermes session DB %s: %s", db_path, exc)
        return []

    try:
        rows = connection.execute(
            """
            SELECT id, role, tool_call_id, tool_calls, tool_name, timestamp
            FROM messages
            WHERE session_id = ?
              AND active = 1
              AND timestamp >= ?
            ORDER BY timestamp DESC, id DESC
            LIMIT ?
            """,
            (session_id, since, _MAX_ROWS),
        ).fetchall()
    except sqlite3.Error as exc:
        logger.debug("Cannot read image activity from %s: %s", db_path, exc)
        return []
    finally:
        connection.close()

    rows.reverse()
    activities: dict[str, dict[str, Any]] = {}
    for _row_id, role, tool_call_id, tool_calls, tool_name, timestamp in rows:
        if role == "assistant":
            for call_id, name in _image_calls(tool_calls):
                activities.setdefault(
                    call_id,
                    {
                        "call_id": call_id,
                        "tool_name": name,
                        "state": "running",
                        "started_at": float(timestamp),
                        "completed_at": None,
                    },
                )
        elif (
            role == "tool"
            and tool_name == _IMAGE_TOOL_NAME
            and isinstance(tool_call_id, str)
            and tool_call_id in activities
        ):
            activity = activities[tool_call_id]
            activity["state"] = "completed"
            activity["completed_at"] = float(timestamp)

    return list(activities.values())
