"""Thin wrappers around the ``tailscale`` CLI.

Implements ADR 25 — first-class Tailscale helper as optional hermes
enhancement. The relay stays loopback-bound on ``127.0.0.1:8767``; this
module lets operators front it with ``tailscale serve`` so the port is
reachable over the tailnet with managed TLS + ACL-based identity.

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

log = logging.getLogger(__name__)

# Keep outer subprocess timeouts short — all these commands are fast when
# the daemon is healthy. Never block relay startup / install.sh on a
# hung ``tailscale`` daemon.
_TIMEOUT_SECONDS = 5

# Default relay port — matches plugin/relay/server.py binding.
DEFAULT_PORT = 8767


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
    - ``serve_ports`` (list[int]): Ports currently fronted by
      ``tailscale serve`` (best-effort — second subprocess call;
      empty list on failure).
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

    return {
        "available": True,
        "hostname": hostname,
        "tailscale_ip": tailscale_ip,
        "serve_ports": _serve_ports(),
    }


def _serve_ports() -> list[int]:
    """Return ports currently published via ``tailscale serve``.

    Best-effort — returns an empty list on any failure (binary absent,
    non-zero exit, unparseable JSON, or an ``serve status`` schema we
    don't recognize). Used only as an informational field on
    :func:`status`; nothing should branch on it.
    """
    if not _tailscale_available():
        return []

    rc, out, _ = _run(["tailscale", "serve", "status", "--json"])
    if rc != 0 or not out.strip():
        return []

    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return []

    if not isinstance(data, dict):
        return []

    ports: set[int] = set()

    # ``tailscale serve status --json`` shape (as of 2026-04):
    #   { "TCP": { "443": { ... } }, "Web": { "hostname:443": { "Handlers": { "/": {...} } } } }
    # We walk both TCP and Web sections — keys are either bare ports or
    # "host:port" strings. Any integer-parseable tail is captured.
    for section_key in ("TCP", "Web"):
        section = data.get(section_key)
        if not isinstance(section, dict):
            continue
        for key in section.keys():
            if not isinstance(key, str):
                continue
            # Take the tail after the last ':' so "hostname:443" → "443".
            tail = key.rsplit(":", 1)[-1]
            try:
                ports.add(int(tail))
            except ValueError:
                continue

    return sorted(ports)


def enable(port: int = DEFAULT_PORT, https: bool = True) -> dict[str, Any]:
    """Publish ``http://127.0.0.1:<port>`` via ``tailscale serve``.

    Shells ``tailscale serve --bg --https=<port> http://127.0.0.1:<port>``
    (or ``--http=<port>`` when ``https=False``). Returns ``{ok, message,
    command}``. No-op with ``ok=False`` when the CLI is absent.
    """
    command = _build_enable_command(port=port, https=https)

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
            "message": (out or err).strip() or f"serving 127.0.0.1:{port} on tailnet",
            "command": command,
        }
    return {
        "ok": False,
        "message": (err or out).strip() or f"tailscale serve exited {rc}",
        "command": command,
    }


def disable(port: int = DEFAULT_PORT) -> dict[str, Any]:
    """Remove the ``tailscale serve`` publication for ``port``.

    Uses ``tailscale serve --https=<port> off`` to revoke a specific
    port's publication. Syntax per the Tailscale docs; if the upstream
    CLI changes this, update here — the outer API (``ok`` + ``message``)
    stays the same.
    """
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


def _build_enable_command(port: int, https: bool) -> list[str]:
    """Construct the ``tailscale serve ... enable`` argv."""
    flag = f"--https={port}" if https else f"--http={port}"
    return [
        "tailscale",
        "serve",
        "--bg",
        flag,
        f"http://127.0.0.1:{port}",
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
    if not _tailscale_available():
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
    "DEFAULT_PORT",
    "canonical_upstream_present",
    "disable",
    "enable",
    "funnel_url",
    "status",
]
