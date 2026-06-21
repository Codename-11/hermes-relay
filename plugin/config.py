"""Plugin-level configuration helpers.

These helpers cover feature flags that are shared by the gateway plugin and
the standalone relay process. Values come from the Hermes ``.env`` file when
present, matching the dashboard's ``/api/env`` writer, with process
environment variables as a fallback.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

logger = logging.getLogger(__name__)

RELAY_AGENT_CONTEXT_ENABLED = "RELAY_AGENT_CONTEXT_ENABLED"
RELAY_CONTEXT_MEDIA_SENSITIVITY = "RELAY_CONTEXT_MEDIA_SENSITIVITY"

_TRUE_VALUES = {"1", "true", "yes", "on"}
_FALSE_VALUES = {"0", "false", "no", "off", ""}


def _env_path() -> Path:
    home = Path(os.getenv("HERMES_HOME") or (Path.home() / ".hermes"))
    return home / ".env"


def _strip_quotes(value: str) -> str:
    text = value.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("'", '"'):
        return text[1:-1]
    return text


def _dotenv_values() -> dict[str, str]:
    path = _env_path()
    if not path.is_file():
        return {}

    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        logger.debug("Could not read Hermes env file at %s", path, exc_info=True)
        return {}

    values: dict[str, str] = {}
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        values[key] = _strip_quotes(value)
    return values


def raw_config_value(key: str) -> str | None:
    """Return a raw plugin config value from ``.env`` or ``os.environ``."""
    values = _dotenv_values()
    if key in values:
        return values[key]
    return os.getenv(key)


def strict_bool(name: str, *, default: bool = False) -> bool:
    """Coerce a boolean env setting using the repo's strict token set."""
    raw = raw_config_value(name)
    if raw is None:
        return default

    normalized = str(raw).strip().lower()
    if normalized in _TRUE_VALUES:
        return True
    if normalized in _FALSE_VALUES:
        return False

    logger.warning("Invalid boolean for %s=%r; treating as %s", name, raw, default)
    return default


def agent_context_enabled() -> bool:
    """Master gate for relay-owned system-prompt context injection.

    Defaults to ON: installing the relay plugin is itself the opt-in, and the
    wrap is fail-open + auditable (chat "What the agent sees" → "Relay context
    (server-side)") + reversible from the dashboard toggle. Vanilla upstream
    (no plugin) is unaffected. Set ``RELAY_AGENT_CONTEXT_ENABLED=0`` to opt out.
    """
    return strict_bool(RELAY_AGENT_CONTEXT_ENABLED, default=True)


def context_media_sensitivity_enabled() -> bool:
    """Per-block gate for the media-sensitivity context instruction (default ON)."""
    return strict_bool(RELAY_CONTEXT_MEDIA_SENSITIVITY, default=True)


__all__ = [
    "RELAY_AGENT_CONTEXT_ENABLED",
    "RELAY_CONTEXT_MEDIA_SENSITIVITY",
    "agent_context_enabled",
    "context_media_sensitivity_enabled",
    "raw_config_value",
    "strict_bool",
]
