"""Lab-owned auth helpers for standalone voice provider experiments."""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass
from pathlib import Path
from typing import Any

VOICE_LAB_HOME_ENV = "VOICE_LAB_HOME"
DEFAULT_VOICE_LAB_HOME = Path("voice-lab-runs")

XAI_OAUTH_ISSUER = "https://auth.x.ai"
XAI_OAUTH_DISCOVERY_URL = f"{XAI_OAUTH_ISSUER}/.well-known/openid-configuration"
XAI_OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
XAI_OAUTH_SCOPE = "openid profile email offline_access grok-cli:access api:access"
XAI_OAUTH_DEVICE_CODE_URL = f"{XAI_OAUTH_ISSUER}/oauth2/device/code"
XAI_API_BASE_URL = "https://api.x.ai/v1"
TOKEN_REFRESH_SKEW_SECONDS = 120


class VoiceLabAuthError(RuntimeError):
    """Raised when the lab-owned OAuth flow cannot complete."""


@dataclass(frozen=True)
class XAIToken:
    access_token: str
    source: str
    base_url: str = XAI_API_BASE_URL
    expires_at_ms: int | None = None


@dataclass(frozen=True)
class XAIAuthResult:
    auth_file: Path
    base_url: str
    expires_at_ms: int | None
    token_type: str | None


def voice_lab_home() -> Path:
    return Path(os.getenv(VOICE_LAB_HOME_ENV) or DEFAULT_VOICE_LAB_HOME)


def voice_lab_env_path() -> Path:
    return voice_lab_home() / ".env"


def xai_oauth_path() -> Path:
    return voice_lab_home() / "auth" / "xai-oauth.json"


def load_voice_lab_env_file() -> None:
    """Load simple KEY=VALUE pairs from VOICE_LAB_HOME/.env without extra deps."""
    env_path = voice_lab_env_path()
    if not env_path.is_file():
        return
    try:
        lines = env_path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        lines = env_path.read_text(encoding="latin-1").splitlines()
    except OSError:
        return

    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key or key in os.environ:
            continue
        os.environ[key] = _strip_env_quotes(value.strip())


def read_xai_oauth_token(
    *,
    auth_file: Path | None = None,
    refresh: bool = True,
) -> XAIToken | None:
    path = auth_file or xai_oauth_path()
    store = _read_token_store(path)
    if not store:
        return None
    tokens = _token_payload(store)
    access_token = str(tokens.get("access_token", "") or "").strip()
    base_url = str(tokens.get("base_url", "") or "").strip() or XAI_API_BASE_URL
    expires_at_ms = _int_or_none(tokens.get("expires_at_ms"))
    if access_token and not _token_expiring(expires_at_ms):
        return XAIToken(
            access_token=access_token,
            source=f"voice-lab oauth store in {path}",
            base_url=base_url,
            expires_at_ms=expires_at_ms,
        )

    refresh_token = str(tokens.get("refresh_token", "") or "").strip()
    if not refresh or not refresh_token:
        return None

    refreshed = refresh_xai_oauth_token(refresh_token=refresh_token)
    merged = dict(tokens)
    merged.update(refreshed)
    if "refresh_token" not in merged or not merged["refresh_token"]:
        merged["refresh_token"] = refresh_token
    merged["base_url"] = base_url
    _write_token_store(path, merged)
    token = str(merged.get("access_token", "") or "").strip()
    if not token:
        return None
    return XAIToken(
        access_token=token,
        source=f"voice-lab oauth store in {path}",
        base_url=base_url,
        expires_at_ms=_int_or_none(merged.get("expires_at_ms")),
    )


def login_xai_oauth(
    *,
    auth_file: Path | None = None,
    no_browser: bool = False,
    timeout_seconds: float | None = None,
) -> XAIAuthResult:
    path = auth_file or xai_oauth_path()
    discovery = _xai_oauth_discovery()
    token_endpoint = discovery["token_endpoint"]
    device = _request_xai_device_code(
        scope=os.getenv("VOICE_LAB_XAI_OAUTH_SCOPE", XAI_OAUTH_SCOPE),
    )
    verification_url = str(
        device.get("verification_uri_complete") or device["verification_uri"]
    )
    user_code = str(device["user_code"])
    print("Open this URL to authorize xAI for the standalone voice lab:")
    print(verification_url)
    print(f"If prompted, enter code: {user_code}")
    if not no_browser:
        try:
            webbrowser.open(verification_url)
        except Exception:
            pass

    expires_in = max(1, int(device["expires_in"]))
    if timeout_seconds is not None:
        expires_in = min(expires_in, max(1, int(timeout_seconds)))
    tokens = _poll_xai_device_token(
        token_endpoint=token_endpoint,
        device_code=str(device["device_code"]),
        expires_in=expires_in,
        poll_interval=max(1, int(device["interval"])),
    )
    tokens["base_url"] = XAI_API_BASE_URL
    _write_token_store(path, tokens)
    return XAIAuthResult(
        auth_file=path,
        base_url=XAI_API_BASE_URL,
        expires_at_ms=_int_or_none(tokens.get("expires_at_ms")),
        token_type=str(tokens.get("token_type", "") or "") or None,
    )


def refresh_xai_oauth_token(*, refresh_token: str) -> dict[str, Any]:
    discovery = _xai_oauth_discovery()
    payload = _post_form(
        discovery["token_endpoint"],
        {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": _xai_oauth_client_id(),
        },
    )
    return _normalize_token_payload(payload)


def _xai_oauth_discovery() -> dict[str, str]:
    try:
        data = _get_json(XAI_OAUTH_DISCOVERY_URL)
    except VoiceLabAuthError:
        data = {
            "token_endpoint": f"{XAI_OAUTH_ISSUER}/oauth2/token",
        }
    token_endpoint = str(data.get("token_endpoint", "") or "").strip()
    if not token_endpoint:
        raise VoiceLabAuthError("xAI OAuth discovery did not include a token endpoint")
    return {"token_endpoint": token_endpoint}


def _request_xai_device_code(*, scope: str) -> dict[str, Any]:
    status, payload = _post_form_response(
        XAI_OAUTH_DEVICE_CODE_URL,
        {"client_id": _xai_oauth_client_id(), "scope": scope},
    )
    if status != 200:
        raise VoiceLabAuthError(
            f"xAI device-code request failed (HTTP {status}): {_oauth_error(payload)}"
        )
    required = (
        "device_code",
        "user_code",
        "verification_uri",
        "verification_uri_complete",
        "expires_in",
        "interval",
    )
    missing = [name for name in required if name not in payload]
    if missing:
        raise VoiceLabAuthError(
            f"xAI device-code response missing fields: {', '.join(missing)}"
        )
    return payload


def _poll_xai_device_token(
    *,
    token_endpoint: str,
    device_code: str,
    expires_in: int,
    poll_interval: int,
) -> dict[str, Any]:
    deadline = time.monotonic() + max(1, expires_in)
    interval = max(1, poll_interval)
    while time.monotonic() < deadline:
        status, payload = _post_form_response(
            token_endpoint,
            {
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "client_id": _xai_oauth_client_id(),
                "device_code": device_code,
            },
        )
        if status == 200:
            if not str(payload.get("access_token", "") or "").strip():
                raise VoiceLabAuthError(
                    "xAI device-code token response did not include an access_token"
                )
            if not str(payload.get("refresh_token", "") or "").strip():
                raise VoiceLabAuthError(
                    "xAI device-code token response did not include a refresh_token"
                )
            return _normalize_token_payload(payload)
        error = str(payload.get("error", "") or "")
        if error == "authorization_pending":
            time.sleep(interval)
            continue
        if error == "slow_down":
            interval = min(interval + 1, 30)
            time.sleep(interval)
            continue
        raise VoiceLabAuthError(f"xAI device-code authorization failed: {_oauth_error(payload)}")
    raise VoiceLabAuthError("Timed out waiting for xAI device authorization")


def _normalize_token_payload(payload: dict[str, Any]) -> dict[str, Any]:
    data = dict(payload)
    expires_in = _int_or_none(data.get("expires_in"))
    if expires_in is not None:
        data["expires_at_ms"] = int(time.time() * 1000) + expires_in * 1000
    return data


def _xai_oauth_client_id() -> str:
    return os.getenv("VOICE_LAB_XAI_OAUTH_CLIENT_ID", XAI_OAUTH_CLIENT_ID).strip()


def _read_token_store(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _write_token_store(path: Path, tokens: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    store = {
        "version": 1,
        "provider": "xai",
        "auth_type": "oauth_device_code",
        "tokens": tokens,
    }
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(store, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


def _token_payload(store: dict[str, Any]) -> dict[str, Any]:
    tokens = store.get("tokens")
    if isinstance(tokens, dict):
        return tokens
    return store


def _token_expiring(expires_at_ms: int | None) -> bool:
    if expires_at_ms is None:
        return False
    return time.time() * 1000 >= expires_at_ms - TOKEN_REFRESH_SKEW_SECONDS * 1000


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _get_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            data = json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise VoiceLabAuthError(f"GET {url} failed: {exc}") from exc
    if not isinstance(data, dict):
        raise VoiceLabAuthError(f"GET {url} returned a non-object response")
    return data


def _post_form(url: str, data: dict[str, str]) -> dict[str, Any]:
    status, payload = _post_form_response(url, data)
    if status < 200 or status >= 300:
        raise VoiceLabAuthError(f"xAI OAuth token request failed: {_oauth_error(payload)}")
    return payload


def _post_form_response(url: str, data: dict[str, str]) -> tuple[int, dict[str, Any]]:
    encoded = urllib.parse.urlencode(data).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=encoded,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = int(getattr(response, "status", 200))
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            payload = {"error": body or f"HTTP {exc.code}"}
        status = int(exc.code)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise VoiceLabAuthError(f"xAI OAuth token request failed: {exc}") from exc
    if not isinstance(payload, dict):
        raise VoiceLabAuthError("xAI OAuth token request returned a non-object response")
    return status, payload


def _oauth_error(payload: dict[str, Any]) -> str:
    return str(
        payload.get("error_description")
        or payload.get("error")
        or "unknown OAuth error"
    )


def _strip_env_quotes(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value
