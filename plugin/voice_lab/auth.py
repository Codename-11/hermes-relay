"""Lab-owned auth helpers for standalone voice provider experiments."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Thread
from typing import Any

VOICE_LAB_HOME_ENV = "VOICE_LAB_HOME"
DEFAULT_VOICE_LAB_HOME = Path("voice-lab-runs")

XAI_OAUTH_ISSUER = "https://auth.x.ai"
XAI_OAUTH_DISCOVERY_URL = f"{XAI_OAUTH_ISSUER}/.well-known/openid-configuration"
XAI_OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
XAI_OAUTH_SCOPE = "openid profile email offline_access grok-cli:access api:access"
XAI_OAUTH_REDIRECT_HOST = "127.0.0.1"
XAI_OAUTH_REDIRECT_PORT = 56121
XAI_OAUTH_REDIRECT_PATH = "/callback"
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
    timeout_seconds: float = 180.0,
) -> XAIAuthResult:
    path = auth_file or xai_oauth_path()
    discovery = _xai_oauth_discovery()
    authorization_endpoint = discovery["authorization_endpoint"]
    token_endpoint = discovery["token_endpoint"]
    redirect_uri = (
        f"http://{XAI_OAUTH_REDIRECT_HOST}:{XAI_OAUTH_REDIRECT_PORT}"
        f"{XAI_OAUTH_REDIRECT_PATH}"
    )
    code_verifier = _pkce_code_verifier()
    code_challenge = _pkce_code_challenge(code_verifier)
    state = secrets.token_urlsafe(24)
    authorize_url = _xai_oauth_authorize_url(
        authorization_endpoint=authorization_endpoint,
        redirect_uri=redirect_uri,
        code_challenge=code_challenge,
        state=state,
    )

    server, thread, result = _start_callback_server(expected_state=state)
    try:
        print("Open this URL to authorize xAI for the standalone voice lab:")
        print(authorize_url)
        if not no_browser:
            webbrowser.open(authorize_url)

        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline and "code" not in result and "error" not in result:
            time.sleep(0.1)
        if "error" in result:
            raise VoiceLabAuthError(str(result["error"]))
        code = str(result.get("code", "") or "").strip()
        if not code:
            raise VoiceLabAuthError("Timed out waiting for xAI OAuth browser callback")
    finally:
        server.shutdown()
        thread.join(timeout=2)

    tokens = _exchange_authorization_code(
        token_endpoint=token_endpoint,
        code=code,
        code_verifier=code_verifier,
        redirect_uri=redirect_uri,
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
            "authorization_endpoint": f"{XAI_OAUTH_ISSUER}/oauth2/authorize",
            "token_endpoint": f"{XAI_OAUTH_ISSUER}/oauth2/token",
        }
    authorization_endpoint = str(data.get("authorization_endpoint", "") or "").strip()
    token_endpoint = str(data.get("token_endpoint", "") or "").strip()
    if not authorization_endpoint or not token_endpoint:
        raise VoiceLabAuthError("xAI OAuth discovery did not include auth/token endpoints")
    return {
        "authorization_endpoint": authorization_endpoint,
        "token_endpoint": token_endpoint,
    }


def _xai_oauth_authorize_url(
    *,
    authorization_endpoint: str,
    redirect_uri: str,
    code_challenge: str,
    state: str,
) -> str:
    query = urllib.parse.urlencode(
        {
            "response_type": "code",
            "client_id": _xai_oauth_client_id(),
            "redirect_uri": redirect_uri,
            "scope": os.getenv("VOICE_LAB_XAI_OAUTH_SCOPE", XAI_OAUTH_SCOPE),
            "state": state,
            "code_challenge": code_challenge,
            "code_challenge_method": "S256",
        }
    )
    return f"{authorization_endpoint}?{query}"


def _exchange_authorization_code(
    *,
    token_endpoint: str,
    code: str,
    code_verifier: str,
    redirect_uri: str,
) -> dict[str, Any]:
    payload = _post_form(
        token_endpoint,
        {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect_uri,
            "client_id": _xai_oauth_client_id(),
            "code_verifier": code_verifier,
        },
    )
    return _normalize_token_payload(payload)


def _normalize_token_payload(payload: dict[str, Any]) -> dict[str, Any]:
    data = dict(payload)
    expires_in = _int_or_none(data.get("expires_in"))
    if expires_in is not None:
        data["expires_at_ms"] = int(time.time() * 1000) + expires_in * 1000
    return data


def _start_callback_server(*, expected_state: str) -> tuple[HTTPServer, Thread, dict[str, Any]]:
    result: dict[str, Any] = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            return

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            if parsed.path != XAI_OAUTH_REDIRECT_PATH:
                self.send_response(404)
                self.end_headers()
                return
            state = (params.get("state") or [""])[0]
            if state != expected_state:
                result["error"] = "xAI OAuth callback state did not match"
                self._write_response("Authorization failed. You can close this tab.")
                return
            if "error" in params:
                result["error"] = (params.get("error_description") or params["error"])[0]
                self._write_response("Authorization failed. You can close this tab.")
                return
            result["code"] = (params.get("code") or [""])[0]
            self._write_response("Authorization complete. You can close this tab.")

        def _write_response(self, body: str) -> None:
            encoded = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    server = HTTPServer((XAI_OAUTH_REDIRECT_HOST, XAI_OAUTH_REDIRECT_PORT), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, result


def _pkce_code_verifier(length: int = 64) -> str:
    return secrets.token_urlsafe(length)[:128]


def _pkce_code_challenge(code_verifier: str) -> str:
    digest = hashlib.sha256(code_verifier.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


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
        "auth_type": "oauth_pkce",
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
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise VoiceLabAuthError(f"xAI OAuth token request failed: {body}") from exc
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise VoiceLabAuthError(f"xAI OAuth token request failed: {exc}") from exc
    if not isinstance(payload, dict):
        raise VoiceLabAuthError("xAI OAuth token request returned a non-object response")
    return payload


def _strip_env_quotes(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value
