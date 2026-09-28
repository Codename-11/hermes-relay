"""Read-only Secure Link setup checks shared by the host CLI and control UIs."""
from __future__ import annotations

import asyncio
import ipaddress
import os
import re
import shutil
import socket
import ssl
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from .secure_proxy import _api_available, _dashboard_gate_enabled, _loopback_http_base


def setup_address(host: str, port: str | int) -> tuple[str, int]:
    """Accept an address, never a URL, command fragment, or unspecified bind."""
    host = host.strip()
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    if not host or len(host) > 253:
        raise ValueError("Enter the LAN, VPN, or DNS address the phone will use.")
    if "%" in host:
        raise ValueError("Use an unscoped server address; interface-scoped IPv6 cannot be paired across devices.")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if all(label.isdigit() for label in host.split(".")):
            raise ValueError("Use a complete IPv4 address or a DNS hostname.") from None
        if not all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in host.split(".")):
            raise ValueError("Enter a hostname or IP address without a scheme, path, or credentials.") from None
        host = host.lower()
    else:
        if address.is_unspecified or address.is_multicast:
            raise ValueError("Use a reachable server address, not a wildcard or multicast address.")
        host = str(address)
    if isinstance(port, bool) or not str(port).isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError("Port must be between 1 and 65535.")
    return host, int(port)


async def _can_bind(host: str, port: int) -> bool:
    try:
        addresses = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM), 2,
        )
        for family, kind, protocol, _, address in addresses:
            try:
                if ipaddress.ip_address(address[0]).is_loopback:
                    continue
                with socket.socket(family, kind, protocol) as listener:
                    listener.bind(address)
                return True
            except OSError:
                continue
    except (OSError, asyncio.TimeoutError):
        pass
    return False


async def _certificate_matches(cert: Path, host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        check = "-checkip"
    except ValueError:
        check = "-checkhost"
    async def inspect(*arguments: str) -> tuple[int | None, bytes]:
        process = await asyncio.create_subprocess_exec(
            "openssl", "x509", "-in", str(cert), "-noout", *arguments,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            output, _ = await asyncio.wait_for(process.communicate(), 3)
            return process.returncode, output
        except asyncio.TimeoutError:
            process.kill()
            await process.wait()
            return None, b""
        except asyncio.CancelledError:
            if process.returncode is None:
                process.kill()
            await process.wait()
            raise

    expiry, _ = await inspect("-checkend", "0")
    if expiry != 0:
        return False
    result, message = await inspect(check, host)
    # Some supported OpenSSL versions return zero for a hostname mismatch.
    return result == 0 and b"does match certificate" in message


async def secure_link_preflight(server: Any, host: str | None = None, port: str | int | None = None) -> dict[str, Any]:
    """Inspect the running Relay's configuration; never write keys or restart it."""
    config = server.config
    candidate = getattr(server, "secure_proxy_candidate", None)
    current_url = candidate.get("proxy", {}).get("url") if isinstance(candidate, dict) else None
    current = urlsplit(current_url or "")
    proposed_host = host if host is not None else current.hostname or config.secure_proxy_host
    if proposed_host in {"0.0.0.0", "::"}:
        # Ask for the advertised address instead of guessing which interface a
        # phone can reach or turning a bind wildcard into an unusable QR.
        proposed_host = ""
    checks: list[dict[str, str]] = []

    def add(key: str, label: str, status: str, detail: str) -> None:
        checks.append({"id": key, "label": label, "status": status, "detail": detail})

    try:
        address, selected_port = setup_address(proposed_host, port if port is not None else config.secure_proxy_port)
    except ValueError as exc:
        add("address", "Server address", "blocked", str(exc))
        address, selected_port = "", config.secure_proxy_port
    url_host = f"[{address}]" if ":" in address else address
    proposed_url = f"https://{url_host}:{selected_port}" if address else None
    try:
        current_address, current_port = setup_address(current.hostname or "", current.port or 443)
    except ValueError:
        current_address, current_port = "", 0
    active = bool(current_url and current.scheme == "https" and current_address == address
                  and current_port == selected_port and current.path in {"", "/"}
                  and not any((current.username, current.password, current.query, current.fragment)))
    if address:
        loopback = address == "localhost"
        try:
            loopback = loopback or ipaddress.ip_address(address).is_loopback
        except ValueError:
            pass
        add("address", "Server address", "blocked" if loopback else "ok",
            "A phone cannot use the server's loopback address. Choose its LAN, VPN, or DNS address."
            if loopback else "This is the address the client will pair with. LAN/VPN/public reachability is still required.")
        available = active or await _can_bind(address, selected_port)
        add("listener", "HTTPS listener", "ok" if available else "blocked",
            "The existing Secure Link listener owns this address." if active else
            "The address and port are available to this Relay process." if available else
            "Relay cannot bind this address and port. Check the local interface, port owner, and permissions.")

    async def upstream(raw: str, label: str, key: str, dashboard: bool) -> None:
        try:
            base = _loopback_http_base(raw, label)
        except (ValueError, AttributeError):
            add(key, label, "blocked",
                f"{label} must use a loopback HTTP upstream for Secure Link. Configure a working local listener first; "
                "do not replace a LAN-only address with localhost unless that listener actually accepts loopback connections.")
            return
        ready = await (_dashboard_gate_enabled(base) if dashboard else _api_available(base))
        add(key, label, "ok" if ready else "blocked" if dashboard else "warning",
            ("Password/OAuth protection is enabled. Clients still sign in separately." if dashboard else "The optional API upstream is reachable.")
            if ready else
            ("Dashboard is unavailable or password/OAuth protection is disabled. Enable its authentication before exposing it."
             if dashboard else "The optional API is unavailable; this does not prove Chat or voice is unavailable."))

    await asyncio.gather(
        upstream(config.webapi_url, "API upstream", "api", False),
        upstream(config.secure_proxy_dashboard_url, "Dashboard protection", "dashboard", True),
    )
    identity = Path(config.hermes_config_path).expanduser().parent / "relay-secure-proxy"
    cert = Path(config.secure_proxy_cert).expanduser() if config.secure_proxy_cert else identity / "cert.pem"
    key = Path(config.secure_proxy_key).expanduser() if config.secure_proxy_key else identity / "key.pem"
    openssl = shutil.which("openssl") is not None
    if not openssl:
        add("certificate", "Certificate and pin", "blocked", "Install OpenSSL on the Relay host before enabling Secure Link.")
    elif cert.exists() or key.exists():
        try:
            valid = bool(address and cert.is_file() and key.is_file() and os.access(key, os.R_OK)
                         and await _certificate_matches(cert, address))
            if os.name == "posix" and key.is_file() and key.stat().st_mode & 0o077:
                valid = False
            if valid:
                # Also reject mismatched or encrypted keys without prompting.
                ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(cert, key, password="")
        except OSError:
            valid = False
        add("certificate", "Certificate and pin", "ok" if valid else "blocked",
            "Existing certificate matches this address and is not expired. No identity was changed." if valid else
            "Check the existing certificate/key pair: it must be readable, match this address, be unexpired, and keep the private key owner-only. Review rotation and re-pairing if the identity must change; this check never replaces keys.")
    else:
        parents = [cert.parent, key.parent]
        for index, parent in enumerate(parents):
            while not parent.exists() and parent != parent.parent:
                parent = parent.parent
            parents[index] = parent
        writable = all(os.access(parent, os.W_OK) for parent in parents)
        add("certificate", "Certificate and pin", "ok" if writable else "blocked",
            "Relay will create a local certificate and pin on enablement. Nothing has been generated by this check."
            if writable else "Relay cannot write its certificate directory. Fix its service-user permissions first.")
    ready = not any(check["status"] == "blocked" for check in checks)
    return {
        "schema_version": 1,
        "state": "enabled" if active and ready else "ready_to_configure" if ready else "needs_attention",
        "read_only": True,
        "url": proposed_url,
        "current_url": current_url,
        "host": address,
        "port": selected_port,
        "checks": checks,
        "ready_to_enable": ready,
        "pairing_ready": active and ready,
        "requires_repair": bool(current_url and not active),
        "connected_clients": server.client_count,
        "activation_mode": "instructions",
        "restart_notice": "Restarting Relay briefly disconnects its clients. Use the manager that already owns Relay; embedded installations may also require a Gateway restart.",
        "configuration_note": "Startup flags override environment settings. Update any existing --secure-link, --no-secure-link, --secure-link-host, and --secure-link-port flags to match the intended settings.",
        "environment": {
            "RELAY_SECURE_LINK_ENABLED": "1", "RELAY_SECURE_LINK_HOST": address,
            "RELAY_SECURE_LINK_PORT": str(selected_port),
        } if ready else {},
        "start_arguments": ["--secure-link", "--secure-link-host", address, "--secure-link-port", str(selected_port)] if ready else [],
        "disable_environment": {"RELAY_SECURE_LINK_ENABLED": "0"},
        "pair_command": "hermes pair --png" if active and ready else None,
    }
