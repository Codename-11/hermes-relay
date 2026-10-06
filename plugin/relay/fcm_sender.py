"""BYO Firebase Cloud Messaging sender (Mac / relay half).

Service-account JSON stays on the host — never in the APK. Phone registers an
FCM device token via ``POST /push/token``; when proactive has no live WSS
subscriber the relay optionally sends a high-priority *data* wake so the app
can reconnect and drain the 24h buffer. Wake data may include a short
``preview`` of the message (default); devices can register ``include_preview:
false`` to omit it on the wire.

Env (either prefix accepted; RELAY_ preferred):
  RELAY_FCM_SERVICE_ACCOUNT_JSON / FCM_SERVICE_ACCOUNT_JSON
      path to SA JSON, or the JSON body itself
  RELAY_FCM_ENABLED / FCM_ENABLED
      default on when SA is present; set 0 to disable

If env is unset, the host also auto-loads::

  $HERMES_HOME/secrets/fcm-sa.json   (default ``~/.hermes/secrets/fcm-sa.json``)

Install via Dashboard → Hermes-Relay → Settings → Push wake, or::

  python -m plugin.relay.fcm_sender install /path/to/*-firebase-adminsdk-*.json
"""

from __future__ import annotations

import json
import logging
import os
import re
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
_DEFAULT_SA_REL = Path("secrets") / "fcm-sa.json"
_ENV_SA_KEYS = ("RELAY_FCM_SERVICE_ACCOUNT_JSON", "FCM_SERVICE_ACCOUNT_JSON")
_ENV_ENABLED_KEYS = ("RELAY_FCM_ENABLED", "FCM_ENABLED")
_ENV_LINE_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")


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


def hermes_home(env: Any | None = None) -> Path:
    e = env if env is not None else os.environ
    raw = ""
    if hasattr(e, "get"):
        raw = str(e.get("HERMES_HOME") or "").strip()
    return Path(raw).expanduser() if raw else Path.home() / ".hermes"


def default_service_account_path(
    home: Path | str | None = None,
    *,
    env: Any | None = None,
) -> Path:
    """Canonical host path for the Firebase Admin service-account JSON."""
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    return base / _DEFAULT_SA_REL


def _validate_service_account(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError("service account must be a JSON object")
    email = data.get("client_email")
    key = data.get("private_key")
    if not isinstance(email, str) or not email.strip():
        raise ValueError("service account missing client_email")
    if not isinstance(key, str) or not key.strip():
        raise ValueError("service account missing private_key")
    if "BEGIN" not in key and "\\n" not in key and "\n" not in key:
        # Still allow short test fixtures; real keys contain PEM markers.
        pass
    project_id = data.get("project_id")
    if project_id is not None and not isinstance(project_id, str):
        raise ValueError("project_id must be a string when present")
    return data


def _parse_service_account_text(raw: str) -> dict[str, Any]:
    text = (raw or "").strip()
    if not text:
        raise ValueError("empty service account")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON: {exc}") from exc
    return _validate_service_account(data)


def _read_dotenv_map(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return out
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = _ENV_LINE_RE.match(stripped)
        if not match:
            continue
        key, value = match.group(1), match.group(2)
        if (value.startswith('"') and value.endswith('"')) or (
            value.startswith("'") and value.endswith("'")
        ):
            value = value[1:-1]
        out[key] = value
    return out


def resolve_fcm_env(
    env: Any | None = None,
    *,
    home: Path | str | None = None,
) -> dict[str, str]:
    """Merge process env with ``$HERMES_HOME/.env`` for FCM keys.

    Process env wins when set (live launchd). Missing keys fall back to ``.env``
    so Dashboard status matches disk after install without a restart.
    """
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    merged: dict[str, str] = {}
    for key, value in _read_dotenv_map(base / ".env").items():
        if key.startswith("RELAY_FCM_") or key.startswith("FCM_") or key == "HERMES_HOME":
            merged[key] = value
    source = env if env is not None else os.environ
    if hasattr(source, "items"):
        try:
            items = source.items()  # type: ignore[assignment]
        except Exception:  # noqa: BLE001
            items = []
        for key, value in items:
            if value is None:
                continue
            text = str(value).strip()
            if not text:
                continue
            if (
                key.startswith("RELAY_FCM_")
                or key.startswith("FCM_")
                or key == "HERMES_HOME"
            ):
                merged[str(key)] = text
    elif hasattr(source, "get"):
        for key in (*_ENV_SA_KEYS, *_ENV_ENABLED_KEYS, "HERMES_HOME"):
            raw = source.get(key)
            if raw is None:
                continue
            text = str(raw).strip()
            if text:
                merged[key] = text
    return merged


def load_service_account_from_env(
    env: Any | None = None,
    *,
    home: Path | str | None = None,
) -> dict[str, Any] | None:
    """Return parsed service-account dict, or None if unset/invalid.

    Resolution order:
      1. ``RELAY_FCM_SERVICE_ACCOUNT_JSON`` / ``FCM_SERVICE_ACCOUNT_JSON``
         (file path or inline JSON) — process env + ``.env``
      2. Default host file ``$HERMES_HOME/secrets/fcm-sa.json``
    """
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    # Plain test dicts (no home) stay isolated; everything else merges .env.
    if env is not None and isinstance(env, dict) and home is None:
        e = env
    else:
        e = resolve_fcm_env(env, home=base)

    raw = _env_get(e, *_ENV_SA_KEYS)
    if raw:
        if raw.startswith("{"):
            try:
                return _validate_service_account(json.loads(raw))
            except (json.JSONDecodeError, ValueError) as exc:
                logger.warning("RELAY_FCM_SERVICE_ACCOUNT_JSON is not valid JSON: %s", exc)
                return None
        path = Path(raw).expanduser()
        if path.is_file():
            try:
                return _validate_service_account(
                    json.loads(path.read_text(encoding="utf-8"))
                )
            except (OSError, json.JSONDecodeError, ValueError) as exc:
                logger.warning("Failed reading FCM service account %s: %s", path, exc)
                return None
            # Missing path: fall through to default file (stale env pointer).
        else:
            logger.warning(
                "RELAY_FCM_SERVICE_ACCOUNT_JSON path not found: %s (trying default)",
                path,
            )

    default_path = default_service_account_path(base, env=e)
    if default_path.is_file():
        try:
            return _validate_service_account(
                json.loads(default_path.read_text(encoding="utf-8"))
            )
        except (OSError, json.JSONDecodeError, ValueError) as exc:
            logger.warning("Failed reading default FCM service account %s: %s", default_path, exc)
            return None
    return None


def fcm_enabled(env: Any | None = None, *, home: Path | str | None = None) -> bool:
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    if env is not None and isinstance(env, dict) and home is None:
        e = env
    else:
        e = resolve_fcm_env(env, home=base)
    flag = _env_get(e, *_ENV_ENABLED_KEYS).lower()
    if flag in ("0", "false", "no", "off"):
        return False
    if flag in ("1", "true", "yes", "on"):
        return True
    return load_service_account_from_env(e, home=base) is not None


def _env_file_path(home: Path | str | None = None, *, env: Any | None = None) -> Path:
    return hermes_home(env) / ".env" if home is None else Path(home).expanduser() / ".env"


def upsert_dotenv(
    updates: dict[str, str | None],
    *,
    env_file: Path | str | None = None,
    home: Path | str | None = None,
    apply_environ: bool = True,
    environ: Any | None = None,
) -> Path:
    """Create/update keys in ``$HERMES_HOME/.env``. ``None`` removes a key."""
    path = Path(env_file).expanduser() if env_file is not None else _env_file_path(home)
    path.parent.mkdir(parents=True, exist_ok=True)
    existing: list[str] = []
    if path.is_file():
        existing = path.read_text(encoding="utf-8").splitlines()

    remaining = dict(updates)
    out: list[str] = []
    for line in existing:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            out.append(line)
            continue
        match = _ENV_LINE_RE.match(stripped)
        if not match:
            out.append(line)
            continue
        key = match.group(1)
        if key not in remaining:
            out.append(line)
            continue
        value = remaining.pop(key)
        if value is None:
            continue  # drop the key
        out.append(f"{key}={value}")

    for key, value in remaining.items():
        if value is None:
            continue
        out.append(f"{key}={value}")

    text = "\n".join(out)
    if text and not text.endswith("\n"):
        text += "\n"
    path.write_text(text, encoding="utf-8")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass

    if apply_environ:
        target = environ if environ is not None else os.environ
        for key, value in updates.items():
            if value is None:
                target.pop(key, None)
            else:
                target[key] = value
    return path


def install_service_account(
    source: str | Path | dict[str, Any],
    *,
    home: Path | str | None = None,
    env: Any | None = None,
    apply_environ: bool = True,
) -> dict[str, Any]:
    """Validate + write SA to the default secrets path; point env at it.

    ``source`` may be a dict, raw JSON text, or a filesystem path.
    Never logs private_key material. Returns a public status dict.
    """
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    if isinstance(source, dict):
        data = _validate_service_account(source)
    else:
        text = str(source).strip()
        as_path = Path(text).expanduser()
        if not text.startswith("{") and as_path.is_file():
            try:
                text = as_path.read_text(encoding="utf-8")
            except OSError as exc:
                raise ValueError(f"cannot read service account file: {exc}") from exc
        data = _parse_service_account_text(text)

    dest = default_service_account_path(base, env=env)
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(dest)
    try:
        os.chmod(dest, 0o600)
    except OSError:
        pass

    dest_s = str(dest)
    upsert_dotenv(
        {
            "RELAY_FCM_SERVICE_ACCOUNT_JSON": dest_s,
            "RELAY_FCM_ENABLED": "1",
            # Drop legacy alias so one source of truth remains.
            "FCM_SERVICE_ACCOUNT_JSON": None,
            "FCM_ENABLED": None,
        },
        home=base,
        apply_environ=apply_environ,
        environ=env if env is not None else os.environ,
    )
    logger.info(
        "FCM service account installed path=%s project_id=%s client_email=%s",
        dest_s,
        data.get("project_id") or "",
        data.get("client_email") or "",
    )
    return fcm_host_status(home=base, env=env if env is not None else os.environ)


def clear_service_account(
    *,
    home: Path | str | None = None,
    env: Any | None = None,
    apply_environ: bool = True,
    delete_file: bool = True,
) -> dict[str, Any]:
    """Remove host SA file + env pointers (disables push wake)."""
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    dest = default_service_account_path(base, env=env)
    if delete_file and dest.is_file():
        try:
            dest.unlink()
        except OSError as exc:
            logger.warning("Failed removing FCM service account %s: %s", dest, exc)

    upsert_dotenv(
        {
            "RELAY_FCM_SERVICE_ACCOUNT_JSON": None,
            "FCM_SERVICE_ACCOUNT_JSON": None,
            "RELAY_FCM_ENABLED": "0",
            "FCM_ENABLED": None,
        },
        home=base,
        apply_environ=apply_environ,
        environ=env if env is not None else os.environ,
    )
    return fcm_host_status(home=base, env=env if env is not None else os.environ)


def set_fcm_enabled_flag(
    enabled: bool,
    *,
    home: Path | str | None = None,
    env: Any | None = None,
    apply_environ: bool = True,
) -> dict[str, Any]:
    """Toggle wake without deleting the installed service account."""
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    upsert_dotenv(
        {
            "RELAY_FCM_ENABLED": "1" if enabled else "0",
            "FCM_ENABLED": None,
        },
        home=base,
        apply_environ=apply_environ,
        environ=env if env is not None else os.environ,
    )
    return fcm_host_status(home=base, env=env if env is not None else os.environ)


def fcm_host_status(
    *,
    home: Path | str | None = None,
    env: Any | None = None,
) -> dict[str, Any]:
    """Public status for Dashboard / CLI (no private_key)."""
    base = Path(home).expanduser() if home is not None else hermes_home(env)
    isolated = env is not None and isinstance(env, dict) and home is None
    e: Any = env if isolated else resolve_fcm_env(env, home=base)
    default_path = default_service_account_path(base, env=e)
    env_raw = _env_get(e, *_ENV_SA_KEYS)
    source: str | None = None
    if env_raw.startswith("{"):
        source = "env_inline"
    elif env_raw and Path(env_raw).expanduser().is_file():
        source = "env_path"
    elif default_path.is_file():
        source = "default_path"
    elif env_raw:
        source = "env_path_missing"

    sa = load_service_account_from_env(e, home=None if isolated else base)
    enabled = fcm_enabled(e, home=None if isolated else base)
    token_path = base / "hermes-relay-fcm-tokens.json"
    device_tokens = 0
    if token_path.is_file():
        try:
            device_tokens = len(FcmTokenStore(token_path).tokens())
        except Exception:  # noqa: BLE001 — status must never raise
            device_tokens = 0

    configured = sa is not None
    if configured and enabled:
        reason = "FCM wake ready — phone needs client config + token register."
    elif configured and not enabled:
        reason = "Service account installed but RELAY_FCM_ENABLED is off."
    else:
        reason = (
            "Install the Firebase Admin service-account JSON on this host "
            "(Dashboard → Settings → Push wake, or "
            "`python -m plugin.relay.fcm_sender install <file.json>`). "
            "Never put the service account in the phone app."
        )

    return {
        "configured": configured,
        "enabled": enabled,
        "project_id": (sa or {}).get("project_id") or None,
        "client_email": (sa or {}).get("client_email") or None,
        "path": str(default_path) if default_path.is_file() else None,
        "default_path": str(default_path),
        "source": source,
        "device_tokens": device_tokens,
        "reason": reason,
        "phone_setup": (
            "On the phone (sideload): Settings → Power tools → Threads → "
            "Push wake (BYO FCM). Load client google-services.json only — "
            "never the service account."
        ),
    }


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


# Lock-screen / shade preview length (chars). Full text stays on relay buffer.
_PREVIEW_MAX = 160


def truncate_preview(text: str | None, *, limit: int = _PREVIEW_MAX) -> str:
    """Collapse whitespace and truncate for FCM data preview (default on)."""
    if not text:
        return ""
    collapsed = " ".join(str(text).split())
    if not collapsed:
        return ""
    if len(collapsed) <= limit:
        return collapsed
    if limit <= 1:
        return collapsed[:limit]
    return collapsed[: max(1, limit - 1)].rstrip() + "…"


def build_wake_message(
    *,
    device_token: str,
    message_id: str,
    title: str | None = None,
    preview: str | None = None,
) -> dict[str, Any]:
    """High-priority data wake. Optional truncated ``preview`` (default path).

    Still data-only (no FCM ``notification`` block) so the app controls display
    and can honor a local hide-content preference.
    """
    data = {
        "type": "hermes_wake",
        "action": "wake",
        "message_id": str(message_id),
    }
    if title:
        data["title"] = str(title)[:120]
    prev = truncate_preview(preview)
    if prev:
        data["preview"] = prev
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
    preview: str | None = None,
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
        preview=preview,
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
        include_preview: bool = True,
    ) -> None:
        if not device_id or not token:
            raise ValueError("device_id and token required")
        self._data[device_id] = {
            "token": token,
            "platform": platform or "android",
            "project_id": project_id or "",
            # When False, Mac omits message preview from FCM data (hide content).
            "include_preview": bool(include_preview),
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

    def registrations(self) -> list[dict[str, Any]]:
        """Token rows with per-device preview preference (default include)."""
        out: list[dict[str, Any]] = []
        for entry in self._data.values():
            t = entry.get("token")
            if not isinstance(t, str) or not t:
                continue
            include = entry.get("include_preview")
            out.append(
                {
                    "token": t,
                    "include_preview": True if include is None else bool(include),
                    "platform": entry.get("platform") or "android",
                    "project_id": entry.get("project_id") or "",
                }
            )
        return out

    def all_entries(self) -> dict[str, dict[str, Any]]:
        return dict(self._data)


def _cli_main(argv: list[str] | None = None) -> int:
    """``python -m plugin.relay.fcm_sender install|status|clear|enable|disable``."""
    import argparse
    import sys

    parser = argparse.ArgumentParser(
        prog="python -m plugin.relay.fcm_sender",
        description=(
            "Install or inspect the host Firebase service account for BYO FCM wake. "
            "Never put the service account on the phone."
        ),
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_install = sub.add_parser("install", help="Copy/validate SA JSON into ~/.hermes/secrets/fcm-sa.json")
    p_install.add_argument("path", help="Path to Firebase Admin SDK service-account JSON")

    sub.add_parser("status", help="Show whether FCM wake is configured (no secrets)")
    sub.add_parser("clear", help="Remove installed SA and disable FCM")
    sub.add_parser("enable", help="Set RELAY_FCM_ENABLED=1")
    sub.add_parser("disable", help="Set RELAY_FCM_ENABLED=0 (keep SA file)")

    args = parser.parse_args(argv)
    try:
        if args.cmd == "install":
            status = install_service_account(args.path)
        elif args.cmd == "status":
            status = fcm_host_status()
        elif args.cmd == "clear":
            status = clear_service_account()
        elif args.cmd == "enable":
            status = set_fcm_enabled_flag(True)
        elif args.cmd == "disable":
            status = set_fcm_enabled_flag(False)
        else:
            parser.error(f"unknown command {args.cmd}")
            return 2
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(status, indent=2, sort_keys=True))
    return 0 if status.get("configured") or args.cmd in ("clear", "status", "disable") else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(_cli_main())
