"""Minimal pinned-TLS facade for Relay pairing and WebSocket transport.

Only health and Relay's authenticated websocket are exposed.  In particular,
this module does not proxy loopback HTTP management, desktop, API, or dashboard
routes: several of those intentionally trust loopback and would be unsafe to
re-export through a network listener.
"""

from __future__ import annotations

import base64
import hashlib
import ipaddress
import os
import ssl
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

import aiohttp
from aiohttp import web

if TYPE_CHECKING:
    from .server import RelayServer


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
            "existing secure proxy certificate does not match advertised host; "
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
             "-nodes", "-days", "3650", "-subj", "/CN=Hermes Relay Secure Proxy",
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


def create_secure_proxy_app(server: "RelayServer") -> web.Application:
    app = web.Application(client_max_size=1024)

    async def health(request: web.Request) -> web.Response:
        return web.json_response({"status": "ok", "surface": "relay_secure_proxy"})

    async def relay_ws(request: web.Request) -> web.StreamResponse:
        # Fresh pairing has no session token until Relay receives the first
        # system/auth frame. Upstream /ws exposes nothing before that mandatory
        # auth gate, so proxying the upgrade is the secure bootstrap boundary.
        downstream = web.WebSocketResponse(heartbeat=30, max_msg_size=4 * 1024 * 1024)
        await downstream.prepare(request)
        upstream_url = f"http://127.0.0.1:{server.config.port}/ws"
        try:
            async with aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=None, connect=5)
            ) as client:
                async with client.ws_connect(
                    upstream_url,
                    heartbeat=30,
                    max_msg_size=4 * 1024 * 1024,
                    headers={
                        "X-Hermes-Proxy-Secret": server.secure_proxy_internal_secret,
                        "X-Hermes-Proxy-Peer": request.remote or "unknown",
                    },
                ) as upstream:
                    async def forward(source, target) -> None:
                        async for message in source:
                            if message.type == aiohttp.WSMsgType.TEXT:
                                await target.send_str(message.data)
                            elif message.type == aiohttp.WSMsgType.BINARY:
                                await target.send_bytes(message.data)
                    import asyncio
                    tasks = [asyncio.create_task(forward(downstream, upstream)),
                             asyncio.create_task(forward(upstream, downstream))]
                    done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    for task in pending:
                        task.cancel()
                    await asyncio.gather(*done, *pending, return_exceptions=True)
        except aiohttp.ClientError:
            await downstream.close(code=1011, message=b"relay upstream unavailable")
        return downstream

    app.router.add_get("/relay/health", health, allow_head=True)
    app.router.add_get("/relay/ws", relay_ws)
    return app


def tls_context(cert_path: Path, key_path: Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_path, key_path)
    return context


def advertised_candidate(host: str, port: int, pin: str) -> dict[str, object]:
    url_host = f"[{host}]" if ":" in host else host
    return {
        "role": "plugin_proxy", "priority": 0, "recommended": True,
        "security": "pinned_tls",
        "proxy": {"url": f"https://{url_host}:{port}",
                  "transport_hint": "https", "pin_sha256": pin},
    }
