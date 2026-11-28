"""Private desktop connector downloads behind a Cloudflare Access application.

Run ``python -m plugin.connector_portal --config /private/portal.json``.
Optional dependency: ``PyJWT[crypto]>=2.10.1,<3``. JSON configuration requires
public_origin (the download page's HTTPS origin), relay_url (WSS), access_issuer,
access_audience, and an absolute base_zip_path to the credential-free installer.
"""
from __future__ import annotations

import argparse
import asyncio
from collections import OrderedDict
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
import hashlib
import hmac
import html
import ipaddress
import json
import logging
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import stat
import tempfile
import time
from typing import Any
from urllib.parse import urlsplit
import zipfile

from aiohttp import ClientError, ClientResponse, ClientSession, ClientTimeout, web
import jwt

from .relay.auth import DESKTOP_INVITE_TTL_SECONDS

logger = logging.getLogger(__name__)
_CHUNK_SIZE = 256 * 1024
_MAX_JSON_BYTES = 128 * 1024
_INVITE = re.compile(r"[0-9a-fA-F]{64}\Z")
_ISSUER = re.compile(r"https://[a-z0-9][a-z0-9-]*\.cloudflareaccess\.com\Z")
_SECURITY_HEADERS = {
    "Cache-Control": "no-store, private, max-age=0", "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff", "Referrer-Policy": "same-origin",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    "X-Frame-Options": "DENY",
}


async def _read_limited(reply: ClientResponse, limit: int) -> bytes:
    data = bytearray()
    async for chunk in reply.content.iter_chunked(16384):
        if len(data) + len(chunk) > limit:
            raise ValueError("response is too large")
        data.extend(chunk)
    return bytes(data)


@dataclass(frozen=True)
class PortalConfig:
    public_origin: str
    relay_url: str
    access_issuer: str
    access_audience: str
    base_zip_path: Path
    invite_url: str = "http://127.0.0.1:8767/connector/invite"
    prefix: str = "/relay"
    max_concurrent_downloads: int = 2
    downloads_per_window: int = 3
    window_seconds: int = 600

    def __post_init__(self) -> None:
        origin, relay = urlsplit(self.public_origin), urlsplit(self.relay_url)
        if (origin.scheme != "https" or not origin.hostname or origin.username
                or origin.password or origin.path or origin.query or origin.fragment):
            raise ValueError("public_origin must be an HTTPS origin without a trailing slash")
        if (relay.scheme != "wss" or not relay.hostname or relay.username
                or relay.password or relay.query or relay.fragment):
            raise ValueError("relay_url must be a WSS URL without credentials or query")
        if not _ISSUER.fullmatch(self.access_issuer):
            raise ValueError("access_issuer must be a fixed Cloudflare Access team origin")
        if not isinstance(self.access_audience, str) or not self.access_audience.strip():
            raise ValueError("access_audience is required")
        invite = urlsplit(self.invite_url)
        try:
            loopback = ipaddress.ip_address(invite.hostname or "").is_loopback
        except ValueError:
            loopback = False
        if (invite.scheme != "http" or not loopback or invite.port != 8767
                or invite.path != "/connector/invite" or invite.username or invite.password
                or invite.query or invite.fragment):
            raise ValueError("invite_url must be the local Relay endpoint on port 8767")
        if not isinstance(self.base_zip_path, Path) or not self.base_zip_path.is_absolute():
            raise ValueError("base_zip_path must be absolute")
        if not re.fullmatch(r"/[a-zA-Z0-9/_-]+", self.prefix) or self.prefix.endswith("/"):
            raise ValueError("prefix must be a path without a trailing slash")
        if (not 1 <= self.max_concurrent_downloads <= 8 or not 1 <= self.downloads_per_window <= 20
                or not 10 <= self.window_seconds <= 3600):
            raise ValueError("download limits outside supported range")

    @classmethod
    def from_file(cls, path: Path) -> PortalConfig:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077
                    or info.st_uid not in (os.getuid(), 0)):
                raise ValueError("configuration must be owner-only and owned by service user or root")
            raw = stream.read(_MAX_JSON_BYTES + 1)
        if len(raw) > _MAX_JSON_BYTES:
            raise ValueError("configuration too large")
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise ValueError("configuration must be an object")
        data["base_zip_path"] = Path(data["base_zip_path"])
        return cls(**data)


def validate_template(path: Path) -> None:
    """Reject ambiguous archives and pre-provisioned credentials."""
    with zipfile.ZipFile(path, "r") as archive:
        names: set[str] = set()
        for entry in archive.infolist():
            name = entry.filename
            normalized = name.casefold().rstrip("/")
            if (name.startswith("/") or "\\" in name or ":" in name
                    or ".." in PurePosixPath(name).parts
                    or name.rstrip("/") != str(PurePosixPath(name))
                    or any(part.endswith((".", " ")) for part in PurePosixPath(name).parts)
                    or normalized in names
                    or normalized == "setup.json" or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError("invalid or pre-provisioned connector template")
            names.add(normalized)
        if not {"install.cmd", "install.ps1", "hermes-relay.exe", "manifest.json"}.issubset(names):
            raise ValueError("connector template lacks required installer files")


async def build_download_archive(base_zip_path: Path, output_path: Path, *,
                                 relay_url: str, invite: str, expires_at: int) -> None:
    """Copy in bounded chunks, yielding between writes, then append setup.json.

    Caller owns an owner-only output path and removes it on every outcome.
    No invitation is written to the template or a static directory.
    """
    if not _INVITE.fullmatch(invite) or not isinstance(expires_at, int):
        raise ValueError("invalid connector invitation")
    validate_template(base_zip_path)
    with base_zip_path.open("rb") as source, output_path.open("wb") as target:
        while chunk := source.read(_CHUNK_SIZE):
            target.write(chunk)
            await asyncio.sleep(0)
    with zipfile.ZipFile(output_path, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        entry = zipfile.ZipInfo("setup.json")
        entry.external_attr = (stat.S_IFREG | 0o600) << 16
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, json.dumps({"version": 1, "relay_url": relay_url,
                                          "invite": invite, "expires_at": expires_at}, separators=(",", ":")))


class AccessJWTVerifier:
    """Asynchronous cached JWKS retrieval from the configured trusted issuer."""

    def __init__(self, config: PortalConfig, session: ClientSession) -> None:
        self._issuer, self._audience = config.access_issuer, config.access_audience
        self._jwks_url = f"{config.access_issuer}/cdn-cgi/access/certs"
        self._session = session
        self._keys: dict[str, Any] = {}
        self._fetched_at = float("-inf")
        self._attempted_at = float("-inf")
        self._lock = asyncio.Lock()

    async def _refresh(self, kid: str) -> None:
        async with self._lock:
            now = time.monotonic()
            if kid in self._keys and now - self._fetched_at < 300:
                return
            if now - self._attempted_at < 10:
                return
            self._attempted_at = now
            async with self._session.get(self._jwks_url, allow_redirects=False) as reply:
                if reply.status != 200:
                    raise ValueError("Access keys unavailable")
                raw = await _read_limited(reply, _MAX_JSON_BYTES)
                document = json.loads(raw)
            keys = document.get("keys") if isinstance(document, dict) else None
            if not isinstance(keys, list) or not 1 <= len(keys) <= 32:
                raise ValueError("invalid Access key document")
            parsed: dict[str, Any] = {}
            for item in keys:
                if (isinstance(item, dict) and item.get("kty") == "RSA"
                        and item.get("alg", "RS256") == "RS256"
                        and item.get("use", "sig") == "sig" and isinstance(item.get("kid"), str)):
                    if item["kid"] in parsed:
                        raise ValueError("duplicate Access key id")
                    parsed[item["kid"]] = jwt.PyJWK.from_dict(item, algorithm="RS256").key
            if not parsed:
                raise ValueError("no Access signing keys")
            self._keys, self._fetched_at = parsed, now

    async def verify(self, token: str) -> str:
        try:
            if not token or len(token) > 16 * 1024:
                raise ValueError("missing Access assertion")
            header = jwt.get_unverified_header(token)
            kid = header.get("kid")
            if header.get("alg") != "RS256" or not isinstance(kid, str) or len(kid) > 256:
                raise ValueError("invalid Access assertion header")
            await self._refresh(kid)
            if time.monotonic() - self._fetched_at >= 300 or kid not in self._keys:
                raise ValueError("unknown Access signing key")
            claims = jwt.decode(token, self._keys[kid], algorithms=["RS256"],
                                audience=self._audience, issuer=self._issuer,
                                options={"require": ["iss", "aud", "exp", "sub"]})
            subject = claims["sub"]
            if not isinstance(subject, str) or not subject.strip() or len(subject) > 256:
                raise ValueError("missing Access identity")
            if claims.get("type") != "app":
                raise ValueError("interactive Access application token required")
            return subject
        except (jwt.PyJWTError, ValueError, KeyError, TypeError, ClientError, TimeoutError, OverflowError):
            raise web.HTTPUnauthorized(text="请先登录后再下载。") from None


class DownloadLimiter:
    def __init__(self, config: PortalConfig) -> None:
        self._config = config
        self._history: OrderedDict[str, list[float]] = OrderedDict()
        self.active = 0

    def acquire(self, subject: str) -> None:
        if self.active >= self._config.max_concurrent_downloads:
            raise web.HTTPServiceUnavailable(text="下载繁忙，请稍后重试。", headers={"Retry-After": "10"})
        now = time.monotonic()
        recent = [stamp for stamp in self._history.pop(subject, []) if now - stamp < self._config.window_seconds]
        self._history[subject] = recent
        if len(recent) >= self._config.downloads_per_window:
            raise web.HTTPTooManyRequests(text="下载过于频繁，请稍后重试。",
                                          headers={"Retry-After": str(self._config.window_seconds)})
        recent.append(now)
        while len(self._history) > 1024:
            self._history.popitem(last=False)
        self.active += 1


class CSRF:
    def __init__(self) -> None:
        self._secret = secrets.token_bytes(32)

    def issue(self, subject: str) -> str:
        value = f"{int(time.time()) + 900}.{secrets.token_hex(16)}.{hashlib.sha256(subject.encode()).hexdigest()}"
        return f"{value}.{hmac.new(self._secret, value.encode(), hashlib.sha256).hexdigest()}"

    def valid(self, value: str, subject: str) -> bool:
        try:
            expiry, nonce, identity, signature = value.split(".")
            message = f"{expiry}.{nonce}.{identity}"
            return (int(time.time()) < int(expiry) <= int(time.time()) + 900
                    and hmac.compare_digest(identity, hashlib.sha256(subject.encode()).hexdigest())
                    and hmac.compare_digest(signature, hmac.new(self._secret, message.encode(), hashlib.sha256).hexdigest()))
        except (ValueError, TypeError):
            return False


def _page(title: str, body: str, *, status: int = 200) -> web.Response:
    return web.Response(text=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{html.escape(title)}</title>
<style>body{{font:17px system-ui,sans-serif;background:#10151e;color:#eef2fa;margin:0}}main{{max-width:620px;margin:9vh auto;padding:28px}}h1{{font-size:32px}}p,li{{line-height:1.8;color:#c6cfde}}a,button{{display:inline-block;padding:14px 22px;border:0;border-radius:9px;background:#78b7ff;color:#092342;text-decoration:none;font:inherit;font-weight:650;cursor:pointer}}small{{color:#a9b4c7}}</style>
<main>{body}</main></html>''', content_type="text/html", status=status)


@web.middleware
async def security_headers(request: web.Request, handler: Callable[[web.Request], Awaitable[web.StreamResponse]]) -> web.StreamResponse:
    try:
        response = await handler(request)
    except web.HTTPException as error:
        recovery_messages = {
            401: ("请先登录后台", "登录后即可领取本次安装专用的安装包。"),
            403: ("请重新打开下载页", "当前请求未通过验证。请返回下载页，刷新后重新点击下载按钮。"),
            429: ("下载过于频繁", "请稍候再试，再次下载需要从下载页领取。"),
            503: ("暂时无法下载", "服务暂不可用或下载繁忙。请稍后返回下载页重试。"),
        }
        if error.status not in recovery_messages:
            error.headers.update(_SECURITY_HEADERS)
            raise
        title, message = recovery_messages[error.status]
        target = html.escape(str(request.app.router["connector_private"].url_for()), quote=True)
        # Use fixed copy, never exception text or request data, in recovery pages.
        response = _page(title, f'<h1>{title}</h1><p>{message}</p><a href="{target}">返回下载页</a>',
                         status=error.status)
        for header in ("Retry-After", "Allow", "WWW-Authenticate"):
            if header in error.headers:
                response.headers[header] = error.headers[header]
    response.headers.update(_SECURITY_HEADERS)
    return response


def create_app(config: PortalConfig) -> web.Application:
    validate_template(config.base_zip_path)
    app = web.Application(middlewares=[security_headers], client_max_size=8192)
    session_key = web.AppKey("portal_session", ClientSession)
    verifier_key = web.AppKey("portal_verifier", AccessJWTVerifier)
    temp_key = web.AppKey("portal_temporary", Path)
    csrf, limiter = CSRF(), DownloadLimiter(config)

    async def resources(application: web.Application) -> AsyncIterator[None]:
        async with ClientSession(timeout=ClientTimeout(total=10), trust_env=False) as session:
            with tempfile.TemporaryDirectory(prefix="relay-connector-") as directory:
                application[session_key], application[verifier_key] = session, AccessJWTVerifier(config, session)
                application[temp_key] = Path(directory)
                yield

    async def identity(request: web.Request) -> str:
        return await request.app[verifier_key].verify(request.headers.get("CF-Access-Jwt-Assertion", ""))

    async def landing(request: web.Request) -> web.Response:
        target = html.escape(f"{config.prefix}/private/", quote=True)
        return _page("Relay 桌面连接器", f'''<h1>Relay 桌面连接器</h1><p>让你的电脑在后台持续连接 Relay，登录后自动启动，断线自动重连。</p>
<p>进入后台登录后，领取仅供本次安装使用的 Windows 安装包。</p><a href="{target}">后台登录与下载</a>''')

    async def private_page(request: web.Request) -> web.Response:
        subject = await identity(request)
        token = html.escape(csrf.issue(subject), quote=True)
        target = html.escape(f"{config.prefix}/private/download", quote=True)
        return _page("下载桌面连接器", f'''<h1>安装桌面连接器</h1><p>安装时一次确认，授权这台电脑的文件、命令执行、屏幕与输入、USB、麦克风和摄像头能力。安装后以独立的长期授权连接，直到你撤销。</p>
<ol><li>点击下方按钮下载专属安装包。</li><li>解压全部文件，双击 <strong>Install.cmd</strong>，按提示确认安装。</li><li>以后登录自动后台运行，无需再次填写配对码。</li></ol>
<p>安装包的首次连接邀请只能使用一次，并在领取后 1 小时失效。请及时安装；超时后重新下载。请勿转发安装包。</p>
<form method="post" action="{target}"><input type="hidden" name="csrf" value="{token}"><button type="submit">下载 Windows 安装包</button></form>
<p><small>已安装设备可在 Relay 设备管理中撤销长期授权。电脑上的 Status.cmd 可查看状态，Stop.cmd 可暂停连接。</small></p>''')

    async def download(request: web.Request) -> web.StreamResponse:
        subject = await identity(request)
        if request.headers.get("Origin") != config.public_origin:
            raise web.HTTPForbidden(text="请从下载页面领取安装包。")
        if request.content_type != "application/x-www-form-urlencoded":
            raise web.HTTPForbidden(text="无效的下载表单。")
        form = await request.post()
        value = form.get("csrf")
        if not isinstance(value, str) or not csrf.valid(value, subject):
            raise web.HTTPForbidden(text="下载页面已过期，请刷新后重试。")
        limiter.acquire(subject)
        path: Path | None = None
        response: web.StreamResponse | None = None
        try:
            session = request.app[session_key]
            # Never forward browser headers to the local operator endpoint.
            started = int(time.time())
            async with session.post(config.invite_url, allow_redirects=False) as reply:
                if reply.status != 200:
                    raise ValueError("local Relay refused invitation")
                raw = await _read_limited(reply, 8192)
                grant = json.loads(raw)
            if not isinstance(grant, dict):
                raise ValueError("invalid local Relay response")
            invite, lifetime = grant.get("invite"), grant.get("expires_in")
            if (not isinstance(invite, str) or not _INVITE.fullmatch(invite)
                    or type(lifetime) is not int or not 0 < lifetime <= DESKTOP_INVITE_TTL_SECONDS):
                raise ValueError("invalid local Relay invitation")
            descriptor, filename = tempfile.mkstemp(suffix=".zip", dir=request.app[temp_key])
            os.close(descriptor)
            path = Path(filename)
            await build_download_archive(config.base_zip_path, path, relay_url=config.relay_url,
                                         invite=invite, expires_at=started + lifetime)
            response = web.StreamResponse(headers={**_SECURITY_HEADERS,
                "Content-Type": "application/zip",
                "Content-Disposition": 'attachment; filename="Relay-Connector-Windows.zip"',
                "Content-Length": str(path.stat().st_size)})
            await response.prepare(request)
            with path.open("rb") as archive:
                while chunk := archive.read(_CHUNK_SIZE):
                    await response.write(chunk)
            await response.write_eof()
            return response
        except (ClientError, TimeoutError, ValueError, OSError, zipfile.BadZipFile):
            if response is not None and response.prepared:
                response.force_close()
                return response
            logger.warning("Connector download could not be prepared")
            raise web.HTTPServiceUnavailable(text="暂时无法生成安装包，请稍后重试。") from None
        finally:
            try:
                if path is not None:
                    path.unlink(missing_ok=True)
            finally:
                limiter.active -= 1

    app.cleanup_ctx.append(resources)
    app.router.add_get(config.prefix + "/", landing)
    app.router.add_get(config.prefix + "/private/", private_page, name="connector_private")
    app.router.add_get(config.prefix + "/private", private_page)
    app.router.add_get(config.prefix + "/private/download", private_page)
    app.router.add_post(config.prefix + "/private/download", download)
    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Access-protected Relay connector download portal")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8788)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    web.run_app(create_app(PortalConfig.from_file(args.config)), host="127.0.0.1",
                port=args.port, access_log=None, print=None)


if __name__ == "__main__":
    main()
