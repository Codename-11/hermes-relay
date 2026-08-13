"""Pinned-TLS ingress for Relay, API-server, and authenticated Dashboard.

The namespaces remain separate because their credentials are not
interchangeable: Relay authenticates its first websocket frame, API-server
uses its bearer key, and Dashboard uses its own cookie/native bearer.  The
Dashboard lane fails closed when the upstream is in loopback-token mode.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import ipaddress
import os
import ssl
import subprocess
import tempfile
import time
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import urlsplit

import aiohttp
from aiohttp import web

if TYPE_CHECKING:
    from .server import RelayServer


SECURE_LINK_NAME = "Hermes Secure Link"
SECURE_LINK_DESCRIPTION = (
    "Pinned TLS access to this host's Relay, API, and Dashboard services"
)
SECURE_LINK_CAPABILITIES = ("relay", "api", "dashboard")
MAX_PROXY_REQUEST_BYTES = 16 * 1024 * 1024
MAX_PROXY_RESPONSE_BYTES = 64 * 1024 * 1024
PROXY_HTTP_TOTAL_TIMEOUT_SECONDS = 15 * 60
PROXY_HTTP_IDLE_TIMEOUT_SECONDS = 2 * 60


def secure_link_services() -> dict[str, dict[str, object]]:
    """Return the stable service-discovery contract advertised to clients."""
    return {
        "relay": {
            "supported": True,
            "base_path": "/relay",
            "health_path": "/relay/health",
            "websocket_path": "/relay/ws",
            "authentication": "relay_session",
        },
        "api": {
            "supported": True,
            "base_path": "/api",
            "authentication": "api_bearer",
        },
        "dashboard": {
            "supported": True,
            "base_path": "/dashboard",
            "authentication": "dashboard_session",
        },
    }


def _private_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        # ``fchmod`` is POSIX-only. Windows ACLs remain authoritative there;
        # the post-replace chmod below still removes broad mode bits where the
        # platform exposes them, without leaking the temporary file handle.
        if hasattr(os, "fchmod"):
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
        if os.name == "nt":
            # POSIX mode bits do not express Windows ACLs. Remove inherited
            # access and grant the service identity full control explicitly;
            # failure is fatal so the proxy never advertises a weak key.
            identity = subprocess.run(
                ["whoami"], check=True, capture_output=True, text=True, timeout=5
            ).stdout.strip()
            if not identity:
                raise OSError("could not resolve Windows service identity")
            subprocess.run(
                ["icacls", str(path), "/inheritance:r", "/grant:r", f"{identity}:(F)"],
                check=True, capture_output=True, timeout=10,
            )
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def ensure_tls_identity(cert_path: Path, key_path: Path, advertised_host: str) -> None:
    """Create an atomic private identity whose SAN matches its advertised URL."""
    if cert_path.is_file() and key_path.is_file():
        os.chmod(key_path, 0o600)
        try:
            san_text = subprocess.run(
                [
                    "openssl", "x509", "-in", str(cert_path),
                    "-noout", "-ext", "subjectAltName",
                ],
                check=True, capture_output=True, text=True, timeout=10,
            ).stdout
            expected = f"IP Address:{advertised_host}"
            try:
                ipaddress.ip_address(advertised_host)
            except ValueError:
                expected = f"DNS:{advertised_host}"
            if expected in san_text:
                return
        except (OSError, subprocess.SubprocessError):
            pass
        raise ValueError(
            "existing Hermes Secure Link certificate does not match advertised host; "
            "explicitly remove/replace the identity and re-pair to rotate trust"
        )
    try:
        ipaddress.ip_address(advertised_host)
        san = f"IP:{advertised_host}"
    except ValueError:
        san = f"DNS:{advertised_host}"
    cert_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=cert_path.parent) as directory:
        generated_key = Path(directory) / "key.pem"
        generated_cert = Path(directory) / "cert.pem"
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256",
             "-nodes", "-days", "3650", "-subj", "/CN=Hermes Secure Link",
             "-addext", f"subjectAltName={san}", "-keyout", str(generated_key),
             "-out", str(generated_cert)],
            check=True, capture_output=True, timeout=30,
        )
        _private_write(key_path, generated_key.read_bytes())
        _private_write(cert_path, generated_cert.read_bytes())


def spki_pin_sha256(cert_path: Path) -> str:
    public_key = subprocess.run(
        ["openssl", "x509", "-in", str(cert_path), "-pubkey", "-noout"],
        check=True, capture_output=True, timeout=10,
    ).stdout
    der = subprocess.run(
        ["openssl", "pkey", "-pubin", "-outform", "DER"], input=public_key,
        check=True, capture_output=True, timeout=10,
    ).stdout
    return "sha256/" + base64.b64encode(hashlib.sha256(der).digest()).decode("ascii")


def certificate_der_base64(cert_path: Path) -> str:
    """Return the public leaf certificate as compact DER base64 for pairing."""
    der = ssl.PEM_cert_to_DER_cert(cert_path.read_text(encoding="ascii"))
    return base64.b64encode(der).decode("ascii")


_HOP_HEADERS = frozenset({
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade", "host", "forwarded",
    "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
    "x-hermes-relay-session", "x-hermes-proxy-secret", "x-hermes-proxy-peer",
})


def _loopback_http_base(raw: str, label: str) -> str:
    parsed = urlsplit(raw.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError(f"{label} upstream must be loopback HTTP")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError(f"{label} upstream contains unsupported URL components")
    return raw.rstrip("/")


def _safe_tail(request: web.Request, prefix: str) -> str | None:
    raw = request.raw_path.split("?", 1)[0]
    lowered = raw.lower()
    if not raw.startswith(prefix) or "\\" in raw or "//" in raw:
        return None
    if any(value in lowered for value in ("%2f", "%5c", "%2e")):
        return None
    tail = raw[len(prefix):]
    if any(part in {".", ".."} for part in tail.split("/")):
        return None
    return tail


def _forward_headers(
    request: web.Request,
    *,
    dashboard: bool = False,
    forwarded_host: str | None = None,
) -> dict[str, str]:
    headers = {
        name: value for name, value in request.headers.items()
        if name.lower() not in _HOP_HEADERS
        and (dashboard or name.lower() != "cookie")
    }
    if dashboard:
        if not forwarded_host:
            raise ValueError("trusted Secure Link authority is required")
        headers.update({
            "X-Forwarded-Prefix": "/dashboard",
            "X-Forwarded-Proto": "https",
            "X-Forwarded-Host": forwarded_host,
            "X-Forwarded-For": request.remote or "unknown",
        })
    return headers


def _scope_dashboard_cookie(value: str) -> str:
    parts = [part.strip() for part in value.split(";")]
    scoped: list[str] = []
    path_seen = False
    for part in parts:
        if part.lower().startswith("path="):
            scoped.append("Path=/dashboard")
            path_seen = True
        elif part.lower().startswith("domain="):
            # The loopback upstream domain must never escape into the client.
            continue
        else:
            scoped.append(part)
    if not path_seen:
        scoped.append("Path=/dashboard")
    return "; ".join(scoped)


def _rewrite_dashboard_location(value: str, upstream_base: str) -> str | None:
    """Map same-Dashboard redirects under /dashboard; reject unsafe HTTP hops."""
    if value.startswith("/") and not value.startswith("//"):
        return "/dashboard" + value
    target = urlsplit(value)
    upstream = urlsplit(upstream_base)
    if (
        target.scheme in {"http", "https"}
        and target.hostname == upstream.hostname
        and target.port == upstream.port
    ):
        suffix = target.path or "/"
        if target.query:
            suffix += "?" + target.query
        return "/dashboard" + suffix
    if target.scheme == "https" and target.hostname:
        # OAuth providers intentionally leave the Hermes origin.
        return value
    return None


async def _dashboard_gate_enabled(base: str) -> bool:
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=3)) as client:
            async with client.get(f"{base}/api/health") as response:
                if response.status != 200:
                    return False
                body = await response.json(content_type=None)
                return body.get("auth_required") is True
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, TypeError):
        return False


async def _api_available(base: str) -> bool:
    try:
        async with aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=3)
        ) as client:
            async with client.get(f"{base}/health") as response:
                return 200 <= response.status < 300
    except (aiohttp.ClientError, asyncio.TimeoutError):
        return False


async def _proxy_http(
    request: web.Request,
    upstream: str,
    *,
    dashboard: bool = False,
    forwarded_host: str | None = None,
) -> web.StreamResponse:
    if request.method in {"CONNECT", "TRACE"}:
        raise web.HTTPMethodNotAllowed(request.method, [])
    if (
        request.content_length is not None
        and request.content_length > MAX_PROXY_REQUEST_BYTES
    ):
        raise web.HTTPRequestEntityTooLarge(
            max_size=MAX_PROXY_REQUEST_BYTES,
            actual_size=request.content_length,
        )
    body = await request.read()
    if len(body) > MAX_PROXY_REQUEST_BYTES:
        raise web.HTTPRequestEntityTooLarge(
            max_size=MAX_PROXY_REQUEST_BYTES,
            actual_size=len(body),
        )
    headers = _forward_headers(
        request,
        dashboard=dashboard,
        forwarded_host=forwarded_host,
    )
    timeout = aiohttp.ClientTimeout(
        total=PROXY_HTTP_TOTAL_TIMEOUT_SECONDS,
        connect=5,
        sock_read=PROXY_HTTP_IDLE_TIMEOUT_SECONDS,
    )
    async with aiohttp.ClientSession(
        timeout=timeout,
        auto_decompress=False,
    ) as client:
        async with client.request(
            request.method, upstream, headers=headers, params=request.query,
            data=body, allow_redirects=False,
        ) as response:
            if (
                response.content_length is not None
                and response.content_length > MAX_PROXY_RESPONSE_BYTES
            ):
                raise web.HTTPBadGateway(text="upstream response exceeds secure limit")
            forwarded: list[tuple[str, str]] = []
            for raw_name, raw_value in response.raw_headers:
                name = raw_name.decode("latin1")
                value = raw_value.decode("latin1")
                lowered = name.lower()
                if lowered in _HOP_HEADERS or (not dashboard and lowered == "set-cookie"):
                    continue
                if dashboard and lowered == "set-cookie":
                    value = _scope_dashboard_cookie(value)
                elif dashboard and lowered == "location":
                    rewritten = _rewrite_dashboard_location(value, upstream)
                    if rewritten is None:
                        continue
                    value = rewritten
                forwarded.append((name, value))
            downstream = web.StreamResponse(status=response.status, headers=forwarded)
            await downstream.prepare(request)
            response_size = 0
            async for chunk in response.content.iter_chunked(64 * 1024):
                response_size += len(chunk)
                if response_size > MAX_PROXY_RESPONSE_BYTES:
                    downstream.force_close()
                    break
                await downstream.write(chunk)
            await downstream.write_eof()
            return downstream


async def _proxy_websocket(
    request: web.Request, upstream_url: str, headers: dict[str, str]
) -> web.StreamResponse:
    downstream = web.WebSocketResponse(heartbeat=30, max_msg_size=4 * 1024 * 1024)
    await downstream.prepare(request)
    try:
        async with aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=None, connect=5)
        ) as client:
            async with client.ws_connect(
                upstream_url, heartbeat=30, max_msg_size=4 * 1024 * 1024,
                headers=headers,
            ) as upstream:
                async def forward(source, target) -> None:
                    async for message in source:
                        if message.type == aiohttp.WSMsgType.TEXT:
                            await target.send_str(message.data)
                        elif message.type == aiohttp.WSMsgType.BINARY:
                            await target.send_bytes(message.data)
                tasks = [asyncio.create_task(forward(downstream, upstream)),
                         asyncio.create_task(forward(upstream, downstream))]
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*done, *pending, return_exceptions=True)
    except (aiohttp.ClientError, asyncio.TimeoutError):
        await downstream.close(code=1011, message=b"upstream unavailable")
    return downstream


def create_secure_proxy_app(server: "RelayServer") -> web.Application:
    app = web.Application(client_max_size=16 * 1024 * 1024)
    api_base = _loopback_http_base(server.config.webapi_url, "API")
    dashboard_base = _loopback_http_base(
        server.config.secure_proxy_dashboard_url, "Dashboard"
    )
    candidate = getattr(server, "secure_proxy_candidate", None)
    proxy_record = candidate.get("proxy") if isinstance(candidate, dict) else None
    proxy_url = proxy_record.get("url") if isinstance(proxy_record, dict) else None
    secure_link_authority = urlsplit(proxy_url).netloc if proxy_url else ""
    if not secure_link_authority:
        configured_host = getattr(server.config, "secure_proxy_host", "localhost")
        configured_port = getattr(server.config, "secure_proxy_port", 9443)
        if configured_host in {"0.0.0.0", "::"}:
            configured_host = "localhost"
        host_text = (
            f"[{configured_host}]"
            if ":" in configured_host and not configured_host.startswith("[")
            else configured_host
        )
        secure_link_authority = f"{host_text}:{configured_port}"
    availability_lock = asyncio.Lock()
    availability_cache: dict[str, object] = {
        "updated_at": 0.0,
        "api": None,
        "dashboard": None,
    }

    @web.middleware
    async def reject_unsafe_methods(
        request: web.Request,
        handler,
    ) -> web.StreamResponse:
        if request.method in {"CONNECT", "TRACE"}:
            raise web.HTTPMethodNotAllowed(request.method, [])
        return await handler(request)

    app.middlewares.append(reject_unsafe_methods)

    async def service_availability() -> tuple[object, object]:
        now = time.monotonic()
        if now - float(availability_cache["updated_at"]) < 15:
            return availability_cache["api"], availability_cache["dashboard"]
        if availability_lock.locked() and availability_cache["updated_at"]:
            return availability_cache["api"], availability_cache["dashboard"]
        async with availability_lock:
            now = time.monotonic()
            if now - float(availability_cache["updated_at"]) >= 15:
                api_available, dashboard_available = await asyncio.gather(
                    _api_available(api_base),
                    _dashboard_gate_enabled(dashboard_base),
                )
                availability_cache.update({
                    "updated_at": time.monotonic(),
                    "api": api_available,
                    "dashboard": dashboard_available,
                })
        return availability_cache["api"], availability_cache["dashboard"]

    async def health(request: web.Request) -> web.Response:
        api_available, dashboard_available = await service_availability()
        services = secure_link_services()
        services["relay"]["available"] = True
        services["api"]["available"] = api_available
        services["dashboard"]["available"] = dashboard_available
        return web.json_response({
            "status": "ok", "surface": "hermes_secure_proxy",
            "display_name": SECURE_LINK_NAME,
            "description": SECURE_LINK_DESCRIPTION,
            "security": "pinned_tls",
            "capabilities": list(SECURE_LINK_CAPABILITIES),
            "namespaces": list(SECURE_LINK_CAPABILITIES),
            "services": services,
        })

    async def relay_ws(request: web.Request) -> web.StreamResponse:
        # Fresh pairing has no session token until Relay receives the first
        # system/auth frame. Upstream /ws exposes nothing before that mandatory
        # auth gate, so proxying the upgrade is the secure bootstrap boundary.
        upstream_url = f"http://127.0.0.1:{server.config.port}/ws"
        return await _proxy_websocket(request, upstream_url, {
            "X-Hermes-Proxy-Secret": server.secure_proxy_internal_secret,
            "X-Hermes-Proxy-Peer": request.remote or "unknown",
        })

    async def api_proxy(request: web.Request) -> web.StreamResponse:
        tail = _safe_tail(request, "/api")
        if tail is None:
            raise web.HTTPBadRequest(text="unsafe proxy path")
        try:
            return await _proxy_http(request, f"{api_base}{tail}")
        except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
            raise web.HTTPBadGateway(text="API upstream unavailable") from exc

    async def dashboard_proxy(request: web.Request) -> web.StreamResponse:
        tail = _safe_tail(request, "/dashboard")
        if tail is None:
            raise web.HTTPBadRequest(text="unsafe proxy path")
        _, dashboard_available = await service_availability()
        if dashboard_available is not True:
            raise web.HTTPServiceUnavailable(
                text="Dashboard secure ingress requires Dashboard authentication"
            )
        upstream = f"{dashboard_base}{tail or '/'}"
        headers = _forward_headers(
            request,
            dashboard=True,
            forwarded_host=secure_link_authority,
        )
        if request.headers.get("Upgrade", "").lower() == "websocket":
            return await _proxy_websocket(
                request, upstream.replace("http://", "ws://", 1), headers
            )
        try:
            return await _proxy_http(
                request,
                upstream,
                dashboard=True,
                forwarded_host=secure_link_authority,
            )
        except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
            raise web.HTTPBadGateway(text="Dashboard upstream unavailable") from exc

    app.router.add_get("/relay/health", health, allow_head=True)
    app.router.add_get("/relay/ws", relay_ws)
    app.router.add_route("*", "/api/{tail:.*}", api_proxy)
    app.router.add_route("*", "/dashboard/{tail:.*}", dashboard_proxy)
    return app


def tls_context(cert_path: Path, key_path: Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_path, key_path)
    return context


def advertised_candidate(
    host: str, port: int, pin: str, certificate_der: str | None = None
) -> dict[str, object]:
    url_host = f"[{host}]" if ":" in host else host
    return {
        "role": "plugin_proxy", "priority": 0, "recommended": True,
        "security": "pinned_tls",
        "display_name": SECURE_LINK_NAME,
        "description": SECURE_LINK_DESCRIPTION,
        "capabilities": list(SECURE_LINK_CAPABILITIES),
        "proxy": {"url": f"https://{url_host}:{port}",
                  "transport_hint": "https", "pin_sha256": pin,
                  **({"cert_der": certificate_der} if certificate_der else {}),
                  "surfaces": list(SECURE_LINK_CAPABILITIES),
                  "services": secure_link_services()},
    }
