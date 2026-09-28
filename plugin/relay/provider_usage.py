"""Provider-neutral account usage snapshots for paired mobile clients.

Hermes already owns provider credentials and the canonical account-usage model.
Relay reuses that model, adds credential-pool and balance structure for Android,
and supplies the missing OpenCode Go and Grok subscription adapters. Provider
keys remain host-side and are never serialized into the response.
"""

from __future__ import annotations

import asyncio
import hashlib
import math
import re
from urllib.parse import urlparse
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

import aiohttp

SCHEMA_VERSION = 2
RELAY_CAPABILITIES = (
    "credential_pools",
    "structured_balances",
    "opencode_go",
    "supergrok",
)
_OPENCODE_GO_DEFAULT_BASE_URL = "https://opencode.ai/zen/go/v1"
_OPENCODE_GO_USER_AGENT = "curl/8.4.0"
# Grok subscription usage is only served by xAI's CLI proxy, not the public API.
_SUPERGROK_IDENTITY_URL = "https://cli-chat-proxy.grok.com/v1/user"
_SUPERGROK_BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
_SUPERGROK_PERIOD_TYPE_PREFIX = "USAGE_PERIOD_TYPE_"
_SUPERGROK_MAX_PRODUCT_WINDOWS = 8
_SUPERGROK_CENTS_PER_USD = 100.0
_SUPERGROK_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_SUPERGROK_SLUG = re.compile(r"[^a-z0-9]+")
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


def _iso_epoch(value: Any) -> str | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            return _iso(datetime.fromtimestamp(float(value), timezone.utc))
        except (OverflowError, OSError, ValueError):
            return None
    return _iso(value)


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
        "balances": [],
        "renews_at": None,
        "action_url": None,
        "credentials": [],
        "active_credential_id": None,
        "active_credential_state": "unknown",
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
        "balances": [],
        "renews_at": None,
        "action_url": None,
        "credentials": [],
        "active_credential_id": None,
        "active_credential_state": "unknown",
        "message": None,
    }


def _public_credential_id(credential_id: str) -> str:
    return hashlib.sha256(credential_id.encode("utf-8")).hexdigest()[:12]


def _effective_credential_status(entry: Any, snapshot: Any) -> str:
    pool_status = str(getattr(entry, "last_status", "") or "").lower()
    if pool_status in {"dead", "invalid"}:
        return "unavailable"
    if pool_status in {"exhausted", "rate_limited", "cooldown"}:
        return "at_limit"
    windows = tuple(getattr(snapshot, "windows", ()) or ()) if snapshot is not None else ()
    if any(float(getattr(window, "used_percent", 0) or 0) >= 100 for window in windows):
        return "at_limit"
    return "available" if bool(getattr(snapshot, "available", False)) else "unavailable"


async def fetch_codex_usage(
    profile_home: Path | None = None,
    *,
    session_id: str | None = None,
    active_credential_id: str | None = None,
    snapshot_fetcher: Callable[..., Any] | None = None,
    pool_loader: Callable[[str], Any] | None = None,
) -> dict[str, Any]:
    token = _set_home(profile_home)
    try:
        if pool_loader is None:
            from agent.credential_pool import load_pool

            pool_loader = load_pool

        entries = pool_loader("openai-codex").entries()[:8]
    except Exception:
        return unavailable_provider(
            "openai-codex",
            "Codex",
            status="unavailable",
            message="Could not load Codex usage",
        )
    finally:
        _reset_home(token)

    if not entries:
        return unavailable_provider("openai-codex", "Codex")
    if snapshot_fetcher is None:
        from agent.account_usage import _fetch_codex_account_usage

        snapshot_fetcher = _fetch_codex_account_usage

    def fetch_entry_snapshot(entry: Any) -> Any:
        entry_token = _set_home(profile_home)
        try:
            return snapshot_fetcher(
                base_url=getattr(entry, "runtime_base_url", None),
                api_key=entry.runtime_api_key,
            )
        finally:
            _reset_home(entry_token)

    async def fetch_entry(entry: Any) -> tuple[Any, Any]:
        try:
            snapshot = await asyncio.to_thread(fetch_entry_snapshot, entry)
            return entry, snapshot
        except Exception:
            return entry, None

    fetched = await asyncio.gather(*(fetch_entry(entry) for entry in entries))
    active_mapping = None
    if active_credential_id:
        active_raw_id = str(active_credential_id).strip()
    elif profile_home is not None:
        from .active_credentials import read_active_credential

        active_mapping = read_active_credential(
            profile_home,
            session_id=session_id,
            provider_id="openai-codex",
        )
        active_raw_id = active_mapping["credential_id"] if active_mapping else None
    else:
        active_raw_id = None
    if active_raw_id not in {str(getattr(entry, "id", "")) for entry, _ in fetched}:
        active_raw_id = None

    active_state = "known" if active_raw_id else "unknown"
    if len(fetched) == 1 and active_raw_id is None:
        active_raw_id = str(getattr(fetched[0][0], "id", ""))
        active_state = "single_credential"

    credentials: list[dict[str, Any]] = []
    active_provider: dict[str, Any] | None = None
    for index, (entry, snapshot) in enumerate(fetched):
        raw_id = str(getattr(entry, "id", ""))
        public_id = _public_credential_id(raw_id)
        serialized = serialize_account_snapshot(
            snapshot,
            provider_id="openai-codex",
            display_name="Codex",
        )
        status = _effective_credential_status(entry, snapshot)
        credential = {
            "id": public_id,
            "label": _bounded_text(getattr(entry, "label", None), 80) or f"Credential {index + 1}",
            "active": raw_id == active_raw_id,
            "status": status,
            "pool_status": _bounded_text(getattr(entry, "last_status", None), 40),
            "last_status_at": _iso_epoch(getattr(entry, "last_status_at", None)),
            "reset_at": _iso_epoch(getattr(entry, "last_error_reset_at", None)),
            "plan": serialized["plan"],
            "windows": serialized["windows"],
            "details": serialized["details"],
            "message": serialized["message"],
        }
        credentials.append(credential)
        if credential["active"]:
            active_provider = serialized

    available_count = sum(row["status"] == "available" for row in credentials)
    limited_count = sum(row["status"] == "at_limit" for row in credentials)
    summary = active_provider or {
        "source": "credential_pool",
        "fetched_at": _now_iso(),
        "plan": None,
        "windows": [],
        "details": [],
    }
    return {
        "id": "openai-codex",
        "display_name": "Codex",
        "status": "available",
        "source": summary.get("source") or "credential_pool",
        "fetched_at": summary.get("fetched_at") or _now_iso(),
        "plan": summary.get("plan"),
        "windows": summary.get("windows", []),
        "details": [
            f"{available_count} available · {limited_count} at limit · {len(credentials)} total"
        ],
        "credentials": credentials,
        "active_credential_id": (
            _public_credential_id(active_raw_id) if active_raw_id else None
        ),
        "active_credential_state": active_state,
        "active_observed_at": (
            _iso(datetime.fromtimestamp(active_mapping["observed_at"], timezone.utc))
            if active_mapping and active_state == "known"
            else None
        ),
        "message": None,
    }


async def fetch_nous_usage(
    profile_home: Path | None = None,
    *,
    account_fetcher: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    token = _set_home(profile_home)
    try:
        from agent.account_usage import build_nous_credits_snapshot
        from hermes_cli.nous_account import get_nous_portal_account_info, nous_portal_topup_url

        if account_fetcher is None:
            account_fetcher = get_nous_portal_account_info

        account = await asyncio.to_thread(account_fetcher, force_fresh=True)
        snapshot = build_nous_credits_snapshot(account)
        result = serialize_account_snapshot(
            snapshot,
            provider_id="nous",
            display_name="Nous",
        )
        if not result["status"] == "available":
            return result

        def balance(balance_id: str, label: str, value: Any) -> dict[str, Any] | None:
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                return None
            amount = float(value)
            if not math.isfinite(amount):
                return None
            return {"id": balance_id, "label": label, "amount": amount, "currency": "USD"}

        access = getattr(account, "paid_service_access_info", None)
        subscription = getattr(account, "subscription", None)
        result["balances"] = [
            item
            for item in (
                balance("total", "Total usable", getattr(access, "total_usable_credits", None)),
                balance(
                    "subscription",
                    "Subscription",
                    getattr(access, "subscription_credits_remaining", None),
                ),
                balance(
                    "top_up",
                    "Top-up",
                    getattr(access, "purchased_credits_remaining", None),
                ),
                balance("rollover", "Rollover", getattr(subscription, "rollover_credits", None)),
            )
            if item is not None
        ]
        result["renews_at"] = _iso(getattr(subscription, "current_period_end", None))
        action_url = _bounded_text(nous_portal_topup_url(account), 500)
        parsed_action = urlparse(action_url or "")
        result["action_url"] = (
            action_url if parsed_action.scheme in {"http", "https"} and parsed_action.netloc else None
        )
        # Structured fields own mobile presentation. Preserve only genuinely
        # additional status lines; never render raw URLs or ISO timestamps.
        result["details"] = [
            detail for detail in result["details"] if detail.startswith("Status:")
        ]
        return result
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


def _supergrok_percent(value: Any) -> float | None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    number = float(value)
    if not math.isfinite(number):
        return None
    return max(0.0, min(100.0, number))


def _supergrok_cents(value: Any) -> float | None:
    """Unwrap xAI's ``{"val": <int cents>}`` money wrapper."""
    if not isinstance(value, dict):
        return None
    cents = value.get("val")
    if not isinstance(cents, (int, float)) or isinstance(cents, bool):
        return None
    amount = float(cents)
    if not math.isfinite(amount) or amount < 0:
        return None
    return amount


def _supergrok_period_label(period_type: Any) -> str:
    raw = str(period_type or "").strip()
    if raw.startswith(_SUPERGROK_PERIOD_TYPE_PREFIX):
        raw = raw[len(_SUPERGROK_PERIOD_TYPE_PREFIX) :]
    return raw.replace("_", " ").strip().title() or "Current period"


def _supergrok_product_label(product: Any) -> str | None:
    raw = _bounded_text(product, 60)
    if raw is None:
        return None
    return _SUPERGROK_CAMEL_BOUNDARY.sub(" ", raw).strip() or raw


def _supergrok_windows(config: dict[str, Any]) -> list[dict[str, Any]]:
    period = config.get("currentPeriod")
    period = period if isinstance(period, dict) else {}
    reset_at = _iso(period.get("end")) or _iso(config.get("billingPeriodEnd"))

    percent = _supergrok_percent(config.get("creditUsagePercent"))
    if percent is None:
        used = _supergrok_cents(config.get("used"))
        limit = _supergrok_cents(config.get("monthlyLimit"))
        if used is not None and limit is not None and limit > 0:
            percent = max(0.0, min(100.0, (used / limit) * 100))

    windows: list[dict[str, Any]] = []
    if percent is not None:
        windows.append(
            {
                "id": "period",
                "label": _supergrok_period_label(period.get("type")),
                "used_percent": percent,
                "reset_at": reset_at,
                "detail": None,
            }
        )
    elif reset_at:
        # A period with no recorded usage yet omits the figure entirely rather
        # than reporting zero. The window is real, so surface it without
        # inventing a percentage for it.
        windows.append(
            {
                "id": "period",
                "label": _supergrok_period_label(period.get("type")),
                "used_percent": None,
                "reset_at": reset_at,
                "detail": "No usage reported yet",
            }
        )

    products = config.get("productUsage")
    if isinstance(products, list):
        for item in products[:_SUPERGROK_MAX_PRODUCT_WINDOWS]:
            if not isinstance(item, dict):
                continue
            product_percent = _supergrok_percent(item.get("usagePercent"))
            label = _supergrok_product_label(item.get("product"))
            if product_percent is None or label is None:
                continue
            windows.append(
                {
                    "id": f"product_{_SUPERGROK_SLUG.sub('_', label.lower()).strip('_')[:32]}",
                    "label": label,
                    "used_percent": product_percent,
                    "reset_at": reset_at,
                    "detail": None,
                }
            )
    return windows


def _supergrok_details(config: dict[str, Any], payload: dict[str, Any]) -> list[str]:
    details: list[str] = []
    cap = _supergrok_cents(config.get("onDemandCap"))
    used = _supergrok_cents(config.get("onDemandUsed"))
    enabled = payload.get("onDemandEnabled") is True or config.get("onDemandEnabled") is True
    if enabled or (cap or 0) > 0 or (used or 0) > 0:
        parts = [
            part
            for part in (
                f"${used / _SUPERGROK_CENTS_PER_USD:.2f} used" if used is not None else None,
                f"of ${cap / _SUPERGROK_CENTS_PER_USD:.2f}" if cap is not None else None,
            )
            if part is not None
        ]
        if parts:
            details.append(f"On-demand: {' '.join(parts)}")
        elif enabled:
            details.append("On-demand enabled")
    prepaid = _supergrok_cents(config.get("prepaidBalance"))
    if prepaid:
        details.append(f"Prepaid balance: ${prepaid / _SUPERGROK_CENTS_PER_USD:.2f}")
    return details


async def fetch_supergrok_usage(
    *,
    profile_home: Path | None = None,
    session_factory: Callable[[], Any] = aiohttp.ClientSession,
    credential_resolver: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    """Fetch Grok subscription usage behind the ``xai-oauth`` sign-in.

    Subscription windows are only served by xAI's CLI proxy, so this adapter
    speaks the pinned ``cli-chat-proxy.grok.com`` contract: the
    Hermes-managed ``xai-oauth`` bearer reads the account identity, then the
    credits billing snapshot scoped to that identity.
    """
    home_token = _set_home(profile_home)
    try:
        if credential_resolver is None:
            from hermes_cli.auth import resolve_xai_oauth_runtime_credentials

            credential_resolver = resolve_xai_oauth_runtime_credentials

        credentials = await asyncio.to_thread(credential_resolver)
    except Exception as exc:
        if getattr(exc, "code", None) == "xai_auth_missing":
            return unavailable_provider("supergrok", "SuperGrok")
        return unavailable_provider(
            "supergrok",
            "SuperGrok",
            status="unavailable",
            message="Could not resolve SuperGrok credentials",
        )
    finally:
        _reset_home(home_token)

    access_token = str((credentials or {}).get("api_key") or "").strip()
    if not access_token:
        return unavailable_provider("supergrok", "SuperGrok")

    headers = {
        "Authorization": f"Bearer {access_token}",
        "Accept": "application/json",
    }
    try:
        async with session_factory() as session:
            async with session.get(
                _SUPERGROK_IDENTITY_URL,
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=15),
            ) as response:
                if response.status != 200:
                    return unavailable_provider(
                        "supergrok",
                        "SuperGrok",
                        status="unavailable",
                        message=f"Provider returned HTTP {response.status}",
                    )
                identity = await response.json()
            user_id = (
                str(identity.get("userId") or "").strip()
                if isinstance(identity, dict)
                else ""
            )
            if not user_id:
                return unavailable_provider(
                    "supergrok",
                    "SuperGrok",
                    status="unavailable",
                    message="Provider returned no account identity",
                )
            async with session.get(
                _SUPERGROK_BILLING_URL,
                headers={**headers, "x-userid": user_id},
                timeout=aiohttp.ClientTimeout(total=15),
            ) as response:
                if response.status != 200:
                    return unavailable_provider(
                        "supergrok",
                        "SuperGrok",
                        status="unavailable",
                        message=f"Provider returned HTTP {response.status}",
                    )
                payload = await response.json()
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, TypeError):
        return unavailable_provider(
            "supergrok",
            "SuperGrok",
            status="unavailable",
            message="Could not load SuperGrok usage",
        )

    config = payload.get("config") if isinstance(payload, dict) else None
    if not isinstance(config, dict):
        return unavailable_provider(
            "supergrok",
            "SuperGrok",
            status="unavailable",
            message="Provider returned an unsupported usage payload",
        )
    windows = _supergrok_windows(config)
    if not windows:
        return unavailable_provider(
            "supergrok",
            "SuperGrok",
            status="unavailable",
            message="Provider returned no usage windows",
        )
    return {
        "id": "supergrok",
        "display_name": "SuperGrok",
        "status": "available",
        "source": "provider_api",
        "fetched_at": _now_iso(),
        "plan": _bounded_text(payload.get("subscriptionTier"), 80),
        "windows": windows,
        "details": _supergrok_details(config, payload),
        "message": None,
    }


async def collect_provider_usage(
    *,
    profile_home: Path | None = None,
    session_id: str | None = None,
    active_credential_id: str | None = None,
    codex_fetcher: Callable[..., Awaitable[dict[str, Any]]] = fetch_codex_usage,
    nous_fetcher: Callable[[Path | None], Awaitable[dict[str, Any]]] = fetch_nous_usage,
    opencode_fetcher: Callable[..., Awaitable[dict[str, Any]]] = fetch_opencode_go_usage,
    supergrok_fetcher: Callable[..., Awaitable[dict[str, Any]]] = fetch_supergrok_usage,
) -> dict[str, Any]:
    providers = await asyncio.gather(
        codex_fetcher(
            profile_home,
            session_id=session_id,
            active_credential_id=active_credential_id,
        ),
        nous_fetcher(profile_home),
        opencode_fetcher(profile_home=profile_home),
        supergrok_fetcher(profile_home=profile_home),
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "fetched_at": _now_iso(),
        "capabilities": list(RELAY_CAPABILITIES),
        "providers": providers,
    }
