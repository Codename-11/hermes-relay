"""BYO Firebase Cloud Messaging sender (Mac / relay half).

Service-account JSON stays on the host — never in the APK. Phone registers an
FCM device token via ``POST /push/token``; when proactive has no live WSS
subscriber the relay optionally sends a high-priority *data* wake so the app
can reconnect and drain the 24h buffer.

Env (either prefix accepted; RELAY_ preferred):
  RELAY_FCM_SERVICE_ACCOUNT_JSON / FCM_SERVICE_ACCOUNT_JSON
      path to SA JSON, or the JSON body itself
  RELAY_FCM_ENABLED / FCM_ENABLED
      default on when SA is present; set 0 to disable
"""

from __future__ import annotations

import json
import logging
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_TOKEN_URL = "https://oauth2.googleapis.com/token"
_SEND_URL = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"


@dataclass
class FcmSendResult:
    ok: bool
    status: int = 0
    body: str = ""
    error: str = ""


def _env_get(env: Any, *keys: str) -> str:
    for key in keys:
        raw = env.get(key)
        if raw is None:
            continue
        text = str(raw).strip()
        if text:
            return text
    return ""


def load_service_account_from_env(
    env: Any | None = None,
) -> dict[str, Any] | None:
    """Return parsed service-account dict, or None if unset/invalid."""
    e = env if env is not None else os.environ
    raw = _env_get(e, "RELAY_FCM_SERVICE_ACCOUNT_JSON", "FCM_SERVICE_ACCOUNT_JSON")
    if not raw:
        return None
    if raw.startswith("{"):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning("RELAY_FCM_SERVICE_ACCOUNT_JSON is not valid JSON: %s", exc)
            return None
    else:
        path = Path(raw).expanduser()
        if not path.is_file():
            logger.warning("RELAY_FCM_SERVICE_ACCOUNT_JSON path not found: %s", path)
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            logger.warning("Failed reading FCM service account %s: %s", path, exc)
            return None
    if not isinstance(data, dict):
        return None
    if not data.get("client_email") or not data.get("private_key"):
        logger.warning("FCM service account missing client_email/private_key")
        return None
    return data


def fcm_enabled(env: Any | None = None) -> bool:
    e = env if env is not None else os.environ
    flag = _env_get(e, "RELAY_FCM_ENABLED", "FCM_ENABLED").lower()
    if flag in ("0", "false", "no", "off"):
        return False
    if flag in ("1", "true", "yes", "on"):
        return True
    return load_service_account_from_env(e) is not None


def _b64url(data: bytes) -> str:
    import base64

    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def mint_access_token(
    service_account: dict[str, Any],
    *,
    now: float | None = None,
    opener: Any = None,
) -> str:
    """JWT bearer grant → OAuth access token (stdlib only)."""
    import hashlib
    import hmac

    # Prefer cryptography/PyJWT if present; otherwise pure-stdlib RS256 via
    # openssl is heavy — use google-auth style with hmac only for tests of
    # payload shape is insufficient for real RSA. Real path uses urllib +
    # the `jwt` algorithm via the `cryptography` package if available, else
    # falls back to google.oauth2 when installed.
    try:
        from google.auth.transport.requests import Request
        from google.oauth2 import service_account as ga_sa

        creds = ga_sa.Credentials.from_service_account_info(
            service_account,
            scopes=[_FCM_SCOPE],
        )
        creds.refresh(Request())
        return creds.token
    except Exception:
        pass

    try:
        import jwt  # PyJWT
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError(
            "FCM send requires google-auth or PyJWT+cryptography on the host"
        ) from exc

    now_ts = int(now if now is not None else time.time())
    payload = {
        "iss": service_account["client_email"],
        "sub": service_account["client_email"],
        "aud": _TOKEN_URL,
        "iat": now_ts,
        "exp": now_ts + 3600,
        "scope": _FCM_SCOPE,
    }
    assertion = jwt.encode(
        payload,
        service_account["private_key"],
        algorithm="RS256",
    )
    if isinstance(assertion, bytes):
        assertion = assertion.decode("ascii")

    body = (
        "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
        f"&assertion={assertion}"
    ).encode("utf-8")
    req = urllib.request.Request(
        _TOKEN_URL,
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    open_fn = opener or urllib.request.urlopen
    with open_fn(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    token = data.get("access_token")
    if not token:
        raise RuntimeError(f"token endpoint returned no access_token: {data!r}")
    return str(token)


def build_wake_message(
    *,
    device_token: str,
    message_id: str,
    title: str | None = None,
) -> dict[str, Any]:
    """High-priority data-only FCM payload (no notification body with secrets)."""
    data = {
        "type": "hermes_wake",
        "action": "wake",
        "message_id": str(message_id),
    }
    if title:
        data["title"] = str(title)[:120]
    return {
        "message": {
            "token": device_token,
            "data": data,
            "android": {
                "priority": "HIGH",
                "ttl": "120s",
            },
        }
    }


def send_wake(
    *,
    device_token: str,
    message_id: str,
    service_account: dict[str, Any],
    title: str | None = None,
    access_token: str | None = None,
    opener: Any = None,
) -> FcmSendResult:
    project_id = service_account.get("project_id")
    if not project_id:
        return FcmSendResult(ok=False, error="service account missing project_id")
    if not device_token:
        return FcmSendResult(ok=False, error="empty device token")

    try:
        token = access_token or mint_access_token(service_account, opener=opener)
    except Exception as exc:
        logger.warning("FCM mint_access_token failed: %s", exc)
        return FcmSendResult(ok=False, error=f"auth failed: {exc}")

    url = _SEND_URL.format(project_id=project_id)
    payload = build_wake_message(
        device_token=device_token,
        message_id=message_id,
        title=title,
    )
    raw = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=raw,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=UTF-8",
        },
        method="POST",
    )
    open_fn = opener or urllib.request.urlopen
    try:
        with open_fn(req, timeout=15) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            status = getattr(resp, "status", 200) or 200
            return FcmSendResult(ok=200 <= int(status) < 300, status=int(status), body=body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
        return FcmSendResult(ok=False, status=int(exc.code), body=body, error=str(exc))
    except Exception as exc:
        return FcmSendResult(ok=False, error=str(exc))


class FcmTokenStore:
    """Persist device_id → FCM registration token under HERMES_HOME."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        self._data: dict[str, dict[str, Any]] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.is_file():
            self._data = {}
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                self._data = {
                    str(k): v for k, v in raw.items() if isinstance(v, dict)
                }
            else:
                self._data = {}
        except (OSError, json.JSONDecodeError):
            self._data = {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, indent=2, sort_keys=True), encoding="utf-8")
        tmp.replace(self.path)

    def upsert(
        self,
        *,
        device_id: str,
        token: str,
        platform: str = "android",
        project_id: str = "",
    ) -> None:
        if not device_id or not token:
            raise ValueError("device_id and token required")
        self._data[device_id] = {
            "token": token,
            "platform": platform or "android",
            "project_id": project_id or "",
            "updated_at": time.time(),
        }
        self._save()

    def remove(self, device_id: str) -> None:
        if device_id in self._data:
            del self._data[device_id]
            self._save()

    def tokens(self) -> list[str]:
        out: list[str] = []
        for entry in self._data.values():
            t = entry.get("token")
            if isinstance(t, str) and t:
                out.append(t)
        return out

    def all_entries(self) -> dict[str, dict[str, Any]]:
        return dict(self._data)
