"""Provider-neutral account usage snapshots for paired mobile clients.

Hermes already owns provider credentials and the canonical account-usage model.
Relay reuses that model for older gateways which do not yet expose a structured
``account.usage`` RPC, and supplies the missing OpenCode Go adapter.  Provider
keys remain host-side and are never serialized into the response.
"""

from __future__ import annotations

import asyncio
import math
import re
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

import aiohttp

SCHEMA_VERSION = 1
_OPENCODE_GO_DEFAULT_BASE_URL = "https://opencode.ai/zen/go/v1"
_OPENCODE_GO_USER_AGENT = "curl/8.4.0"
_MAX_DETAIL_LENGTH = 240
_PROFILE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


def resolve_profile_home(config_path: str, requested_profile: str | None) -> Path:
    """Resolve an exact Hermes profile home without mutating process globals."""
    root = Path(config_path).expanduser().resolve().parent
    profile = str(requested_profile or "").strip().lower()
    if profile in {"", "default"}:
        try:
            active = (root / "active_profile").read_text(encoding="utf-8").strip().lower()
        except (OSError, UnicodeError):
            active = ""
        if _PROFILE_ID.fullmatch(active):
            candidate = (root / "profiles" / active).resolve()
            if candidate.parent == (root / "profiles").resolve() and (candidate / "config.yaml").is_file():
                return candidate
        return root
    if not _PROFILE_ID.fullmatch(profile):
        raise ValueError("invalid profile")
    candidate = (root / "profiles" / profile).resolve()
    if candidate.parent != (root / "profiles").resolve() or not (candidate / "config.yaml").is_file():
        raise ValueError("unknown profile")
    return candidate


def _set_home(profile_home: Path | None):
    if profile_home is None:
        return None
    from hermes_constants import set_hermes_home_override

    return set_hermes_home_override(profile_home)


def _reset_home(token) -> None:
    if token is None:
        return
    from hermes_constants import reset_hermes_home_override

    reset_hermes_home_override(token)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _bounded_text(value: Any, limit: int = _MAX_DETAIL_LENGTH) -> str | None:
    text = str(value or "").strip()
    return text[:limit] if text else None


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        dt = value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    return _bounded_text(value, 80)


def unavailable_provider(
    provider_id: str,
    display_name: str,
    *,
    status: str = "not_configured",
    message: str | None = None,
) -> dict[str, Any]:
    return {
        "id": provider_id,
        "display_name": display_name,
        "status": status,
        "source": None,
        "fetched_at": None,
        "plan": None,
        "windows": [],
        "details": [],
        "message": _bounded_text(message),
    }


def serialize_account_snapshot(
    snapshot: Any,
    *,
    provider_id: str,
    display_name: str,
) -> dict[str, Any]:
    """Serialize upstream ``AccountUsageSnapshot`` without provider secrets."""
    if snapshot is None or not bool(getattr(snapshot, "available", False)):
        return unavailable_provider(provider_id, display_name)

    windows: list[dict[str, Any]] = []
    for index, window in enumerate(tuple(getattr(snapshot, "windows", ()) or ())[:8]):
        raw_percent = getattr(window, "used_percent", None)
        percent: float | None = None
        if isinstance(raw_percent, (int, float)) and not isinstance(raw_percent, bool):
            if math.isfinite(float(raw_percent)):
                percent = max(0.0, min(100.0, float(raw_percent)))
        label = _bounded_text(getattr(window, "label", None), 60) or f"Window {index + 1}"
        windows.append(
            {
                "id": label.lower().replace(" ", "_")[:40],
                "label": label,
                "used_percent": percent,
                "reset_at": _iso(getattr(window, "reset_at", None)),
                "detail": _bounded_text(getattr(window, "detail", None)),
            }
        )

    details = [
        text
        for item in tuple(getattr(snapshot, "details", ()) or ())[:8]
        if (text := _bounded_text(item)) is not None
    ]
    return {
        "id": provider_id,
        "display_name": display_name,
        "status": "available",
        "source": _bounded_text(getattr(snapshot, "source", None), 60),
        "fetched_at": _iso(getattr(snapshot, "fetched_at", None)) or _now_iso(),
        "plan": _bounded_text(getattr(snapshot, "plan", None), 80),
        "windows": windows,
        "details": details,
        "message": None,
    }


async def fetch_codex_usage(profile_home: Path | None = None) -> dict[str, Any]:
    token = _set_home(profile_home)
    try:
        from agent.account_usage import fetch_account_usage

        snapshot = await asyncio.to_thread(fetch_account_usage, "openai-codex")
        return serialize_account_snapshot(
            snapshot,
            provider_id="openai-codex",
            display_name="Codex",
        )
    except Exception:
        return unavailable_provider(
            "openai-codex",
            "Codex",
            status="unavailable",
            message="Could not load Codex usage",
        )
    finally:
        _reset_home(token)


async def fetch_nous_usage(profile_home: Path | None = None) -> dict[str, Any]:
    token = _set_home(profile_home)
    try:
        from agent.account_usage import build_nous_credits_snapshot
        from hermes_cli.nous_account import get_nous_portal_account_info

        account = await asyncio.to_thread(get_nous_portal_account_info, force_fresh=True)
        snapshot = build_nous_credits_snapshot(account)
        return serialize_account_snapshot(
            snapshot,
            provider_id="nous",
            display_name="Nous",
        )
    except Exception:
        return unavailable_provider(
            "nous",
            "Nous",
            status="unavailable",
            message="Could not load Nous usage",
        )
    finally:
        _reset_home(token)


async def fetch_opencode_go_usage(
    *,
    profile_home: Path | None = None,
    session_factory: Callable[[], Any] = aiohttp.ClientSession,
    credential_resolver: Callable[[str], dict[str, Any]] | None = None,
) -> dict[str, Any]:
    token = _set_home(profile_home)
    try:
        if credential_resolver is None:
            from hermes_cli.auth import resolve_api_key_provider_credentials

            credential_resolver = resolve_api_key_provider_credentials

        credentials = await asyncio.to_thread(
            credential_resolver,
            "opencode-go",
        )
    except Exception:
        credentials = {}
    finally:
        _reset_home(token)
    api_key = str(credentials.get("api_key") or "").strip()
    if not api_key:
        return unavailable_provider("opencode-go", "OpenCode Go")

    base_url = str(credentials.get("base_url") or _OPENCODE_GO_DEFAULT_BASE_URL).rstrip("/")
    try:
        async with session_factory() as session:
            async with session.get(
                f"{base_url}/usage",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "User-Agent": _OPENCODE_GO_USER_AGENT,
                },
                timeout=aiohttp.ClientTimeout(total=15),
            ) as response:
                if response.status != 200:
                    return unavailable_provider(
                        "opencode-go",
                        "OpenCode Go",
                        status="unavailable",
                        message=f"Provider returned HTTP {response.status}",
                    )
                payload = await response.json()
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, TypeError):
        return unavailable_provider(
            "opencode-go",
            "OpenCode Go",
            status="unavailable",
            message="Could not load OpenCode Go usage",
        )

    usage = payload.get("usage") if isinstance(payload, dict) else None
    if not isinstance(usage, dict):
        return unavailable_provider(
            "opencode-go",
            "OpenCode Go",
            status="unavailable",
            message="Provider returned an unsupported usage payload",
        )

    windows: list[dict[str, Any]] = []
    for key, label in (
        ("rolling", "Session · 5h"),
        ("weekly", "Weekly"),
        ("monthly", "Monthly"),
    ):
        raw = usage.get(key)
        if not isinstance(raw, dict):
            continue
        raw_percent = raw.get("percent")
        if not isinstance(raw_percent, (int, float)) or isinstance(raw_percent, bool):
            continue
        percent = float(raw_percent)
        if not math.isfinite(percent):
            continue
        windows.append(
            {
                "id": key,
                "label": label,
                "used_percent": max(0.0, min(100.0, percent)),
                "reset_at": _iso(raw.get("resetsAt")),
                "detail": None,
            }
        )

    if not windows:
        return unavailable_provider(
            "opencode-go",
            "OpenCode Go",
            status="unavailable",
            message="Provider returned no usage windows",
        )
    return {
        "id": "opencode-go",
        "display_name": "OpenCode Go",
        "status": "available",
        "source": "provider_api",
        "fetched_at": _now_iso(),
        "plan": None,
        "windows": windows,
        "details": [],
        "message": None,
    }


async def collect_provider_usage(
    *,
    profile_home: Path | None = None,
    codex_fetcher: Callable[[Path | None], Awaitable[dict[str, Any]]] = fetch_codex_usage,
    nous_fetcher: Callable[[Path | None], Awaitable[dict[str, Any]]] = fetch_nous_usage,
    opencode_fetcher: Callable[..., Awaitable[dict[str, Any]]] = fetch_opencode_go_usage,
) -> dict[str, Any]:
    providers = await asyncio.gather(
        codex_fetcher(profile_home),
        nous_fetcher(profile_home),
        opencode_fetcher(profile_home=profile_home),
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "fetched_at": _now_iso(),
        "providers": providers,
    }
