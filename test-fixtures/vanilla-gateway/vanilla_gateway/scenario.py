"""Declarative scenario loading and validation."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any


class ScenarioError(ValueError):
    """Raised when a scenario does not satisfy the fixture schema."""


_STEP_OPS = {"event", "persist", "sleep", "close", "set_running"}
_LIVE_STATUSES = {"starting", "working", "waiting", "idle"}
_SAFE_NAME = re.compile(r"[A-Za-z0-9_.-]{1,120}")


@dataclass(frozen=True)
class Scenario:
    name: str
    live_session_id: str
    stored_session_id: str
    profile: str
    contract_requirements: tuple[str, ...]
    initial_history: tuple[dict[str, Any], ...]
    turns: tuple[dict[str, Any], ...]
    active_list_supported: bool
    active_list_snapshots: tuple[tuple[dict[str, Any], ...], ...]

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "Scenario":
        required = ("name", "live_session_id", "stored_session_id", "turns")
        missing = [key for key in required if key not in raw]
        if missing:
            raise ScenarioError(f"missing scenario fields: {', '.join(missing)}")
        if not isinstance(raw["turns"], list):
            raise ScenarioError("turns must be a list")
        if not isinstance(raw["name"], str) or not _SAFE_NAME.fullmatch(raw["name"]):
            raise ScenarioError("name must be a short metadata-safe scenario identifier")
        for turn_index, turn in enumerate(raw["turns"]):
            if not isinstance(turn, dict) or not isinstance(turn.get("steps"), list):
                raise ScenarioError(f"turn {turn_index} must contain a steps list")
            for step_index, step in enumerate(turn["steps"]):
                if not isinstance(step, dict) or step.get("op") not in _STEP_OPS:
                    raise ScenarioError(
                        f"turn {turn_index} step {step_index} has unsupported op",
                    )
                if step["op"] == "event" and (
                    not isinstance(step.get("type"), str)
                    or not _SAFE_NAME.fullmatch(step["type"])
                ):
                    raise ScenarioError(
                        f"turn {turn_index} event step requires a metadata-safe type",
                    )
                if step["op"] == "event" and step.get("scope", "exact") not in {
                    "exact", "foreign", "unscoped",
                }:
                    raise ScenarioError("event scope must be exact, foreign, or unscoped")
                if step["op"] == "persist" and not isinstance(step.get("messages"), list):
                    raise ScenarioError("persist step requires a messages list")
                if step["op"] == "set_running" and not isinstance(step.get("value"), bool):
                    raise ScenarioError("set_running step requires a boolean value")
                if step["op"] == "sleep":
                    milliseconds = step.get("milliseconds")
                    if not isinstance(milliseconds, int) or not 0 <= milliseconds <= 5_000:
                        raise ScenarioError("sleep milliseconds must be between 0 and 5000")
        history = raw.get("initial_history", [])
        if not isinstance(history, list):
            raise ScenarioError("initial_history must be a list")
        requirements = raw.get("contract_requirements", [])
        if (
            not isinstance(requirements, list)
            or not all(isinstance(requirement, str) and requirement for requirement in requirements)
        ):
            raise ScenarioError("contract_requirements must be a list of non-empty strings")
        if len(set(requirements)) != len(requirements):
            raise ScenarioError("contract_requirements must not contain duplicates")
        active_list = raw.get("active_list", {})
        if not isinstance(active_list, dict):
            raise ScenarioError("active_list must be an object")
        active_list_supported = active_list.get("supported", False)
        if not isinstance(active_list_supported, bool):
            raise ScenarioError("active_list supported must be a boolean")
        snapshots = active_list.get("snapshots", [])
        if not isinstance(snapshots, list):
            raise ScenarioError("active_list snapshots must be a list")
        if not active_list_supported and snapshots:
            raise ScenarioError("unsupported active_list cannot declare snapshots")
        validated_snapshots: list[tuple[dict[str, Any], ...]] = []
        for snapshot_index, snapshot in enumerate(snapshots):
            if not isinstance(snapshot, list):
                raise ScenarioError(f"active_list snapshot {snapshot_index} must be a list")
            validated_rows: list[dict[str, Any]] = []
            for row_index, row in enumerate(snapshot):
                if not isinstance(row, dict):
                    raise ScenarioError(
                        f"active_list snapshot {snapshot_index} row {row_index} must be an object"
                    )
                if not isinstance(row.get("id"), str) or not row["id"]:
                    raise ScenarioError(
                        f"active_list snapshot {snapshot_index} row {row_index} requires an id"
                    )
                if not isinstance(row.get("session_key"), str) or not row["session_key"]:
                    raise ScenarioError(
                        f"active_list snapshot {snapshot_index} row {row_index} requires a session_key"
                    )
                if row.get("status") not in _LIVE_STATUSES:
                    raise ScenarioError(
                        f"active_list snapshot {snapshot_index} row {row_index} has invalid status"
                    )
                validated_rows.append(dict(row))
            validated_snapshots.append(tuple(validated_rows))
        return cls(
            name=str(raw["name"]),
            live_session_id=str(raw["live_session_id"]),
            stored_session_id=str(raw["stored_session_id"]),
            profile=str(raw.get("profile", "default")),
            contract_requirements=tuple(requirements),
            initial_history=tuple(dict(row) for row in history),
            turns=tuple(dict(turn) for turn in raw["turns"]),
            active_list_supported=active_list_supported,
            active_list_snapshots=tuple(validated_snapshots),
        )


def load_scenario(name_or_path: str | Path) -> Scenario:
    """Load a bundled scenario by name or an external JSON scenario by path."""
    candidate = Path(name_or_path)
    if candidate.is_file():
        raw = json.loads(candidate.read_text(encoding="utf-8"))
    else:
        name = str(name_or_path)
        if not name.endswith(".json"):
            name += ".json"
        resource = files("vanilla_gateway.scenarios").joinpath(name)
        if not resource.is_file():
            raise ScenarioError(f"unknown scenario: {name_or_path}")
        raw = json.loads(resource.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ScenarioError("scenario root must be an object")
    return Scenario.from_dict(raw)
