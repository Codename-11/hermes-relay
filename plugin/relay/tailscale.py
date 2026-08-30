"""Thin wrappers around the ``tailscale`` CLI.

Implements ADR 25 — first-class Tailscale helper as optional hermes
enhancement. The relay stays loopback-bound on ``127.0.0.1:8767``. The
recommended tailnet ingress listens on Tailscale HTTPS 443 and proxies the
Dashboard on ``127.0.0.1:9119``; Relay rides its same-origin plugin transport
path. The optional Hermes API server on 8642 remains available for headless
compatibility.

All public functions are **safe to call unconditionally** — they shell
out to the ``tailscale`` CLI via ``subprocess.run(..., check=False)``
and return structured dicts on failure instead of raising. When the
CLI binary is absent (operator without Tailscale installed) the calls
no-op with a clear ``message`` field.

# TODO(upstream-merge #9295): remove this module when
# ``canonical_upstream_present()`` returns True on typical installs.
# Upstream PR https://github.com/NousResearch/hermes-agent/pull/9295
# adds ``hermes gateway run --tailscale`` which supersedes this helper.
"""

from __future__ import annotations

import json
import logging
import shutil
import subprocess
from typing import Any
from urllib.parse import urlsplit

log = logging.getLogger(__name__)

# Keep outer subprocess timeouts short — all these commands are fast when
# the daemon is healthy. Never block relay startup / install.sh on a
# hung ``tailscale`` daemon.
_TIMEOUT_SECONDS = 5

# Default ports — relay matches plugin/relay/server.py, API matches
# hermes-agent's API server default used by plugin/pair.py.
DEFAULT_RELAY_PORT = 8767
DEFAULT_DASHBOARD_LISTENER_PORT = 443
DEFAULT_DASHBOARD_PORT = 9119
DEFAULT_API_PORT = 8642
DEFAULT_PORT = DEFAULT_DASHBOARD_PORT


def _valid_port(port: Any) -> bool:
    """Return whether ``port`` is an integer in the TCP/UDP port range."""
    return isinstance(port, int) and not isinstance(port, bool) and 1 <= port <= 65535


def _invalid_port_result(port: Any) -> dict[str, Any]:
    """Structured validation failure preserving this module's no-raise API."""
    return {
        "ok": False,
        "message": f"port must be an integer from 1 to 65535 (got {port!r})",
        "command": None,
    }


def _run(argv: list[str]) -> tuple[int, str, str]:
    """Run ``argv`` and return ``(returncode, stdout, stderr)``.

    Never raises. Missing binary, non-zero exit, timeouts, and other
    OSErrors all flatten into ``(-1, "", "<reason>")``. Callers decide
    what to do with the structured failure.
    """
    try:
        result = subprocess.run(  # noqa: S603 — argv is constructed, not shell
            argv,
            check=False,
            capture_output=True,
            timeout=_TIMEOUT_SECONDS,
            text=True,
        )
    except FileNotFoundError:
        return -1, "", "binary not found"
    except subprocess.TimeoutExpired:
        return -1, "", f"timed out after {_TIMEOUT_SECONDS}s"
    except OSError as exc:
        return -1, "", f"os error: {exc}"
    return result.returncode, result.stdout or "", result.stderr or ""


def _tailscale_available() -> bool:
    """Return True when the ``tailscale`` binary is on PATH."""
    return shutil.which("tailscale") is not None


def status() -> dict[str, Any] | None:
    """Return a summary of the local Tailscale state, or ``None``.

    Returns ``None`` when the ``tailscale`` binary is absent or when
    ``tailscale status --json`` exits non-zero (daemon stopped, not
    logged in, etc.). Returns a dict with the keys below when it
    succeeds:

    - ``available`` (bool): CLI is present and responded successfully.
    - ``hostname`` (str | None): Tailscale-assigned short hostname.
    - ``tailscale_ip`` (str | None): First IPv4 in ``Self.TailscaleIPs``.
    - ``serve_ports`` (list[int]): Listener ports currently fronted by
      ``tailscale serve`` (best-effort — second subprocess call;
      empty list on failure).
    - ``serve_services`` (dict): Dashboard, API, and legacy direct Relay
      activity classified by loopback target, including non-matching listener
      ports such as an HTTPS 443 listener proxying Dashboard on 9119.
    - ``serve_routes`` (list[dict]): Parsed listener/path/proxy details from
      the live Serve JSON for operator diagnostics.
    """
    if not _tailscale_available():
        return None

    rc, out, err = _run(["tailscale", "status", "--json"])
    if rc != 0:
        log.debug("tailscale status failed: rc=%s stderr=%s", rc, err.strip())
        return None

    try:
        data = json.loads(out)
    except json.JSONDecodeError as exc:
        log.debug("tailscale status JSON parse failed: %s", exc)
        return None

    if not isinstance(data, dict):
        return None

    self_node = data.get("Self") or {}
    hostname = None
    if isinstance(self_node, dict):
        # HostName is the short hostname; DNSName is the FQDN
        # (e.g. "mybox.tail1234.ts.net.").
        hostname = self_node.get("DNSName") or self_node.get("HostName")
        if isinstance(hostname, str):
            hostname = hostname.rstrip(".")

    tailscale_ip = None
    if isinstance(self_node, dict):
        ips = self_node.get("TailscaleIPs") or []
        if isinstance(ips, list):
            for ip in ips:
                if isinstance(ip, str) and ":" not in ip:  # prefer IPv4
                    tailscale_ip = ip
                    break
            if tailscale_ip is None and ips:
                first = ips[0]
                if isinstance(first, str):
                    tailscale_ip = first

    serve_state = _serve_state()
    return {
        "available": True,
        "hostname": hostname,
        "tailscale_ip": tailscale_ip,
        "serve_ports": serve_state["ports"],
        "serve_services": _classify_serve_services(serve_state["routes"]),
        "serve_routes": serve_state["routes"],
    }


def _serve_ports() -> list[int]:
    """Return ports currently published via ``tailscale serve``.

    Best-effort — returns an empty list on any failure (binary absent,
    non-zero exit, unparseable JSON, or an ``serve status`` schema we
    don't recognize). Used only as an informational field on
    :func:`status`; nothing should branch on it.
    """
    return _serve_state()["ports"]


def _endpoint_port(value: Any) -> int | None:
    """Extract a valid port from a Serve key or proxy URL."""
    if not isinstance(value, str) or not value.strip():
        return None
    candidate = value.strip()
    if candidate.isdecimal():
        port = int(candidate)
        return port if _valid_port(port) else None
    try:
        parsed = urlsplit(candidate if "://" in candidate else f"//{candidate}")
        port = parsed.port
    except ValueError:
        return None
    return port if _valid_port(port) else None


def _serve_state() -> dict[str, Any]:
    """Parse current Serve listeners and Web proxy routes best-effort.

    Tailscale reports the tailnet listener in ``TCP``/``Web`` keys and the
    loopback destination separately in each Web handler's ``Proxy`` value.
    Keeping both avoids mistaking ``https://host:443 -> 127.0.0.1:9119`` for
    an unrelated service, while ``serve_ports`` remains backward-compatible
    as the list of ports the tailnet client actually dials.
    """
    empty: dict[str, Any] = {"ports": [], "routes": []}
    if not _tailscale_available():
        return empty

    rc, out, _ = _run(["tailscale", "serve", "status", "--json"])
    if rc != 0 or not out.strip():
        return empty

    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return empty
    if not isinstance(data, dict):
        return empty

    ports: set[int] = set()
    routes: list[dict[str, Any]] = []

    tcp = data.get("TCP")
    if isinstance(tcp, dict):
        for key, config in tcp.items():
            listen_port = _endpoint_port(key)
            if listen_port is None:
                continue
            ports.add(listen_port)
            https = bool(config.get("HTTPS")) if isinstance(config, dict) else False
            routes.append(
                {
                    "listen_port": listen_port,
                    "https": https,
                    "path": None,
                    "proxy": None,
                    "target_port": None,
                }
            )

    web = data.get("Web")
    if isinstance(web, dict):
        for key, config in web.items():
            listen_port = _endpoint_port(key)
            if listen_port is None:
                continue
            ports.add(listen_port)
            handlers = config.get("Handlers") if isinstance(config, dict) else None
            if not isinstance(handlers, dict):
                continue
            for path, handler in handlers.items():
                if not isinstance(path, str) or not isinstance(handler, dict):
                    continue
                proxy = handler.get("Proxy")
                proxy_value = proxy if isinstance(proxy, str) else None
                routes.append(
                    {
                        "listen_port": listen_port,
                        "https": _listener_uses_https(tcp, listen_port),
                        "path": path,
                        "proxy": proxy_value,
                        "target_port": _endpoint_port(proxy_value),
                    }
                )

    routes.sort(
        key=lambda item: (
            item["listen_port"],
            item["path"] is None,
            item["path"] or "",
            item["proxy"] or "",
        )
    )
    return {"ports": sorted(ports), "routes": routes}


def _listener_uses_https(tcp: Any, listen_port: int) -> bool:
    """Return the TCP section's HTTPS flag for ``listen_port``."""
    if not isinstance(tcp, dict):
        return False
    for key, config in tcp.items():
        if _endpoint_port(key) != listen_port or not isinstance(config, dict):
            continue
        return bool(config.get("HTTPS"))
    return False


def _classify_serve_services(routes: list[dict[str, Any]]) -> dict[str, Any]:
    """Classify recommended and legacy services without hiding listeners."""
    definitions = (
        ("dashboard", DEFAULT_DASHBOARD_PORT),
        ("api", DEFAULT_API_PORT),
        ("legacy_relay", DEFAULT_RELAY_PORT),
    )
    services: dict[str, Any] = {}
    for name, target_port in definitions:
        listeners = sorted(
            {
                int(route["listen_port"])
                for route in routes
                if route.get("target_port") == target_port
                or (
                    route.get("target_port") is None
                    and route.get("listen_port") == target_port
                )
            }
        )
        services[name] = {
            "port": target_port,
            "active": bool(listeners),
            "listen_ports": listeners,
        }
    return services


def enable(port: int = DEFAULT_PORT, https: bool = True) -> dict[str, Any]:
    """Publish ``http://127.0.0.1:<port>`` via ``tailscale serve``.

    Shells ``tailscale serve --bg --https=<port> http://127.0.0.1:<port>``
    (or ``--http=<port>`` when ``https=False``). Returns ``{ok, message,
    command}``. No-op with ``ok=False`` when the CLI is absent.
    """
    return enable_proxy(listener_port=port, target_port=port, https=https)


def enable_proxy(
    listener_port: int,
    target_port: int,
    https: bool = True,
) -> dict[str, Any]:
    """Publish one tailnet listener backed by a distinct loopback target."""
    if not _valid_port(listener_port):
        return _invalid_port_result(listener_port)
    if not _valid_port(target_port):
        return _invalid_port_result(target_port)

    command = _build_enable_command(
        listener_port=listener_port,
        target_port=target_port,
        https=https,
    )

    if not _tailscale_available():
        return {
            "ok": False,
            "message": "tailscale binary not found",
            "command": command,
            "listener_port": listener_port,
            "target_port": target_port,
        }

    rc, out, err = _run(command)
    if rc == 0:
        return {
            "ok": True,
            "message": (out or err).strip() or (
                f"serving 127.0.0.1:{target_port} on tailnet port {listener_port}"
            ),
            "command": command,
            "listener_port": listener_port,
            "target_port": target_port,
        }
    return {
        "ok": False,
        "message": (err or out).strip() or f"tailscale serve exited {rc}",
        "command": command,
        "listener_port": listener_port,
        "target_port": target_port,
    }


def enable_stack(
    relay_port: int = DEFAULT_DASHBOARD_PORT,
    api_port: int | None = DEFAULT_API_PORT,
    https: bool = True,
    dashboard_listener_port: int = DEFAULT_DASHBOARD_LISTENER_PORT,
) -> dict[str, Any]:
    """Publish Dashboard and the optional Hermes API via Tailscale Serve.

    ``relay_port`` retains its historical parameter name for compatibility,
    but is the loopback Dashboard target (9119), not the tailnet listener.
    Recommended setup listens on HTTPS 443 and never disables an existing
    direct 8767 publication because paired clients may still depend on it.
    """
    if not _valid_port(dashboard_listener_port):
        result = _invalid_port_result(dashboard_listener_port)
        return {
            "ok": False,
            "message": f"dashboard listener: {result['message']}",
            "commands": [],
            "dashboard": result,
            "api": None,
        }
    if not _valid_port(relay_port):
        result = _invalid_port_result(relay_port)
        return {
            "ok": False,
            "message": f"dashboard: {result['message']}",
            "commands": [],
            "dashboard": result,
            "api": None,
        }
    if api_port is not None and not _valid_port(api_port):
        result = _invalid_port_result(api_port)
        return {
            "ok": False,
            "message": f"api: {result['message']}",
            "commands": [],
            "dashboard": None,
            "api": result,
        }
    if api_port == dashboard_listener_port:
        return {
            "ok": False,
            "message": (
                "api listener must differ from the Dashboard tailnet listener "
                f"({dashboard_listener_port})"
            ),
            "commands": [],
            "dashboard": None,
            "api": None,
        }

    results: dict[str, dict[str, Any] | None] = {
        "dashboard": enable_proxy(
            listener_port=dashboard_listener_port,
            target_port=relay_port,
            https=https,
        ),
        "api": None,
    }
    if api_port is not None:
        results["api"] = enable(port=api_port, https=https)

    ok = all(
        result is None or bool(result.get("ok"))
        for result in results.values()
    )
    messages = [
        f"{name}: {result.get('message')}"
        for name, result in results.items()
        if result is not None
    ]
    commands = [
        result.get("command")
        for result in results.values()
        if result is not None
    ]
    return {
        "ok": ok,
        "message": "; ".join(messages),
        "commands": commands,
        **results,
    }


def disable(port: int = DEFAULT_PORT) -> dict[str, Any]:
    """Remove the ``tailscale serve`` publication for ``port``.

    Uses ``tailscale serve --https=<port> off`` to revoke a specific
    port's publication. Syntax per the Tailscale docs; if the upstream
    CLI changes this, update here — the outer API (``ok`` + ``message``)
    stays the same.
    """
    if not _valid_port(port):
        return _invalid_port_result(port)

    command = _build_disable_command(port=port)

    if not _tailscale_available():
        return {
            "ok": False,
            "message": "tailscale binary not found",
            "command": command,
        }

    rc, out, err = _run(command)
    if rc == 0:
        return {
            "ok": True,
            "message": (out or err).strip() or f"stopped serving port {port}",
            "command": command,
        }
    return {
        "ok": False,
        "message": (err or out).strip() or f"tailscale serve off exited {rc}",
        "command": command,
    }


def disable_stack(
    relay_port: int = DEFAULT_DASHBOARD_PORT,
    api_port: int | None = DEFAULT_API_PORT,
    dashboard_listener_port: int = DEFAULT_DASHBOARD_LISTENER_PORT,
) -> dict[str, Any]:
    """Remove Dashboard listener and API, leaving legacy 8767 intact."""
    if not _valid_port(dashboard_listener_port):
        result = _invalid_port_result(dashboard_listener_port)
        return {
            "ok": False,
            "message": f"dashboard listener: {result['message']}",
            "commands": [],
            "dashboard": result,
            "api": None,
        }
    if not _valid_port(relay_port):
        result = _invalid_port_result(relay_port)
        return {
            "ok": False,
            "message": f"dashboard: {result['message']}",
            "commands": [],
            "dashboard": result,
            "api": None,
        }
    if api_port is not None and not _valid_port(api_port):
        result = _invalid_port_result(api_port)
        return {
            "ok": False,
            "message": f"api: {result['message']}",
            "commands": [],
            "dashboard": None,
            "api": result,
        }
    if api_port == dashboard_listener_port:
        return {
            "ok": False,
            "message": (
                "api listener must differ from the Dashboard tailnet listener "
                f"({dashboard_listener_port})"
            ),
            "commands": [],
            "dashboard": None,
            "api": None,
        }

    results: dict[str, dict[str, Any] | None] = {
        "dashboard": disable(port=dashboard_listener_port),
        "api": None,
    }
    if api_port is not None:
        results["api"] = disable(port=api_port)

    ok = all(
        result is None or bool(result.get("ok"))
        for result in results.values()
    )
    messages = [
        f"{name}: {result.get('message')}"
        for name, result in results.items()
        if result is not None
    ]
    commands = [
        result.get("command")
        for result in results.values()
        if result is not None
    ]
    return {
        "ok": ok,
        "message": "; ".join(messages),
        "commands": commands,
        **results,
    }


def _build_enable_command(
    listener_port: int,
    target_port: int,
    https: bool,
) -> list[str]:
    """Construct the ``tailscale serve ... enable`` argv."""
    flag = (
        f"--https={listener_port}"
        if https
        else f"--http={listener_port}"
    )
    return [
        "tailscale",
        "serve",
        "--bg",
        flag,
        f"http://127.0.0.1:{target_port}",
    ]


def _build_disable_command(port: int) -> list[str]:
    """Construct the ``tailscale serve ... disable`` argv."""
    return ["tailscale", "serve", "--https=" + str(port), "off"]


def funnel_url(port: int = DEFAULT_PORT) -> str | None:
    """Return the public Funnel URL fronting ``port`` if one is active.

    Tailscale Funnel is the "open to the public internet" sibling of
    ``tailscale serve`` — same syntax but any internet user (not just
    the tailnet) can reach the fronted port, still with TLS terminated
    on Tailscale's edge. Returns ``https://<hostname>/`` when the given
    port is currently funneled, otherwise ``None``.

    Used by :func:`plugin.pair.build_endpoint_candidates` to auto-
    populate the ``role=public`` candidate when ``mode=auto`` is picked
    without an explicit ``--public-url`` — removes the "pin the public
    URL manually on the Remote Access tab" operator step when Funnel
    is already live.

    Safe on all platforms: returns ``None`` when the CLI is absent,
    when ``funnel status`` exits non-zero, when the JSON isn't shaped
    the way we expect, or when nothing is funneled on ``port``.

    Shape parsed (as of 2026-04):
      { "AllowFunnel": { "host:port": true }, ... }
      or the newer ``tailscale funnel status --json`` form, which
      returns the same ``AllowFunnel`` key under the top-level Web
      config.
    """
    if not _valid_port(port) or not _tailscale_available():
        return None

    # Try the modern ``funnel status`` invocation first; fall back to
    # parsing ``serve status`` if that path isn't available on this
    # Tailscale version.
    hostname = _funnel_hostname()
    if hostname is None:
        return None

    allow = _funnel_allowed_ports()
    if port not in allow:
        return None

    # Tailscale Funnel terminates TLS at Tailscale's edge, so the URL
    # is always https and always on the default 443. The ``port`` arg
    # tells us *which local port is funneled*, not what port callers
    # will dial — the public URL itself is hostname-only.
    return f"https://{hostname}/"


def _funnel_hostname() -> str | None:
    """Tailscale-assigned hostname (``*.ts.net``) for Funnel URLs.

    Reads from the same ``status --json`` blob :func:`status` uses,
    but factored out so :func:`funnel_url` can call it without
    re-running the expensive status() probe (which also shells
    ``serve status`` for ``serve_ports``).
    """
    rc, out, _ = _run(["tailscale", "status", "--json"])
    if rc != 0:
        return None
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    self_node = data.get("Self") or {}
    if not isinstance(self_node, dict):
        return None
    hostname = self_node.get("DNSName") or self_node.get("HostName")
    if not isinstance(hostname, str):
        return None
    return hostname.strip().rstrip(".") or None


def _funnel_allowed_ports() -> set[int]:
    """Ports currently published with Funnel (public-internet) access.

    Parses ``tailscale serve status --json`` for ``AllowFunnel`` flags
    and extracts the integer tails of the ``host:port`` keys. Returns
    an empty set on any parse failure — downstream treats empty as
    "nothing funneled".
    """
    rc, out, _ = _run(["tailscale", "serve", "status", "--json"])
    if rc != 0 or not out.strip():
        return set()
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return set()
    if not isinstance(data, dict):
        return set()

    funnel_map = data.get("AllowFunnel")
    if not isinstance(funnel_map, dict):
        return set()

    ports: set[int] = set()
    for key, allowed in funnel_map.items():
        if not allowed:
            continue
        if not isinstance(key, str):
            continue
        tail = key.rsplit(":", 1)[-1]
        try:
            ports.add(int(tail))
        except ValueError:
            continue
    return ports


def canonical_upstream_present() -> bool:
    """Return True when the canonical ``--tailscale`` flag is available.

    Probes ``hermes gateway run --help`` and greps for ``--tailscale``.
    Absent binary / non-zero exit / no match → False. This is the
    exit-criteria probe for removing this module once upstream PR
    https://github.com/NousResearch/hermes-agent/pull/9295 merges.
    """
    if shutil.which("hermes") is None:
        return False

    rc, out, err = _run(["hermes", "gateway", "run", "--help"])
    if rc != 0:
        return False

    haystack = (out or "") + "\n" + (err or "")
    return "--tailscale" in haystack


__all__ = [
    "DEFAULT_API_PORT",
    "DEFAULT_DASHBOARD_LISTENER_PORT",
    "DEFAULT_DASHBOARD_PORT",
    "DEFAULT_PORT",
    "DEFAULT_RELAY_PORT",
    "canonical_upstream_present",
    "disable",
    "disable_stack",
    "enable",
    "enable_proxy",
    "enable_stack",
    "funnel_url",
    "status",
]
