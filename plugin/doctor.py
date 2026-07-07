"""Hermes-Relay plugin diagnostics.

The doctor command is intentionally local and read-only. It gives operators and
agents one stable surface for checking which parts of the Relay install are
plugin-owned, which standard upstream Hermes routes are reachable, and whether
the legacy bootstrap monkeypatch is still installed.
"""

from __future__ import annotations

import argparse
import importlib
import json
import os
import site
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable, Iterable

from .compat import collect_compat_status

PLUGIN_DIR = Path(__file__).resolve().parent
PLUGIN_NAME = "hermes-relay"
DEFAULT_API_URL = "http://localhost:8642"
DEFAULT_DASHBOARD_URL = "http://localhost:9119"
DEFAULT_RELAY_PORT = 8767

Probe = Callable[..., dict[str, Any]]


def _read_simple_manifest(path: Path) -> dict[str, Any]:
    """Read the small manifest fields the doctor needs without requiring yaml."""
    data: dict[str, Any] = {}
    if not path.exists():
        return data
    try:
        for raw in path.read_text(encoding="utf-8").splitlines():
            if not raw or raw.lstrip().startswith("#") or ":" not in raw:
                continue
            key, value = raw.split(":", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key in {"name", "version", "description", "author"}:
                data[key] = value
    except OSError:
        return {}
    return data


def _base_url(value: str | None, default: str) -> str:
    raw = (value or "").strip() or default
    return raw.rstrip("/")


def _url(base: str, path: str) -> str:
    return f"{base.rstrip('/')}/{path.lstrip('/')}"


def _default_api_url() -> str:
    return _base_url(
        os.environ.get("HERMES_API_URL")
        or os.environ.get("RELAY_WEBAPI_URL")
        or os.environ.get("WEBAPI_URL"),
        DEFAULT_API_URL,
    )


def _default_dashboard_url() -> str:
    return _base_url(
        os.environ.get("HERMES_DASHBOARD_URL")
        or os.environ.get("HERMES_WEB_URL")
        or os.environ.get("DASHBOARD_URL"),
        DEFAULT_DASHBOARD_URL,
    )


def _default_relay_port() -> int:
    raw = os.environ.get("RELAY_PORT", "").strip()
    if not raw:
        return DEFAULT_RELAY_PORT
    try:
        return int(raw)
    except ValueError:
        return DEFAULT_RELAY_PORT


def _route_exists_status(status: int | None) -> bool:
    """Treat auth and method errors as evidence that a route exists."""
    if status is None:
        return False
    return status != 404


def http_probe(
    url: str,
    *,
    method: str = "GET",
    timeout: float = 2.0,
) -> dict[str, Any]:
    """Probe a URL without sending credentials or request bodies."""
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read(65536).decode("utf-8", errors="replace")
            parsed: Any = None
            try:
                parsed = json.loads(body) if body else None
            except json.JSONDecodeError:
                parsed = None
            status = int(resp.status)
            return {
                "ok": 200 <= status < 400,
                "exists": _route_exists_status(status),
                "status": status,
                "method": method,
                "url": url,
                "json": parsed if isinstance(parsed, dict) else None,
            }
    except urllib.error.HTTPError as exc:
        status = int(exc.code)
        return {
            "ok": False,
            "exists": _route_exists_status(status),
            "status": status,
            "method": method,
            "url": url,
            "error": str(exc.reason),
        }
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return {
            "ok": False,
            "exists": False,
            "status": None,
            "method": method,
            "url": url,
            "error": str(exc),
        }


def _site_dirs(site_dirs: Iterable[Path] | None = None) -> list[Path]:
    if site_dirs is not None:
        return [Path(p) for p in site_dirs]

    candidates: list[Path] = []
    try:
        candidates.extend(Path(p) for p in site.getsitepackages())
    except Exception:
        pass
    try:
        candidates.append(Path(site.getusersitepackages()))
    except Exception:
        pass
    for entry in sys.path:
        p = Path(entry)
        if p.name == "site-packages":
            candidates.append(p)

    seen: set[str] = set()
    unique: list[Path] = []
    for candidate in candidates:
        key = str(candidate)
        if key not in seen:
            seen.add(key)
            unique.append(candidate)
    return unique


def _bootstrap_status(site_dirs: Iterable[Path] | None = None) -> dict[str, Any]:
    status = collect_compat_status(site_dirs=site_dirs)
    package = status["package"]
    return {
        "package_importable": package["importable"],
        "package_path": package["path"],
        "pth_files": [item["path"] for item in status["pth_files"]],
        "installed": bool(status["installed"]),
        "mode": "legacy-compatibility",
        "recommendation": status["recommendation"],
    }


def _plugin_manager_layout(plugin_dir: Path) -> dict[str, Any]:
    parent = plugin_dir.parent
    return {
        "plugin_dir": str(plugin_dir),
        "has_plugin_yaml": (plugin_dir / "plugin.yaml").exists(),
        "has_register_init": (plugin_dir / "__init__.py").exists(),
        "has_dashboard_manifest": (plugin_dir / "dashboard" / "manifest.json").exists(),
        "looks_like_user_plugin": parent.name == "plugins" and parent.parent.name == ".hermes",
        "recommended_install_identifier": "Codename-11/hermes-relay/plugin",
    }


def _relay_import_chain() -> dict[str, Any]:
    """Import the relay server module chain under the CURRENT package layout.

    ``hermes relay start`` runs ``create_app`` from ``<plugin pkg>.relay.server``,
    which transitively pulls the voice/realtime/voice_lab modules. Actually
    importing that chain here catches the failure class from issue #165 —
    absolute ``plugin.*`` imports crashing when the native plugin loader
    imports the tree as ``hermes_plugins.hermes_relay`` (no top-level
    ``plugin`` package exists) — which a filesystem-layout check can never see.
    """
    package = __package__ or "plugin"
    module_name = f"{package}.relay.server"
    try:
        importlib.import_module(module_name)
    except Exception as exc:  # any import-time failure is a real start failure
        return {
            "ok": False,
            "module": module_name,
            "error": f"{type(exc).__name__}: {exc}",
        }
    return {"ok": True, "module": module_name, "error": None}


def _check(checks: list[dict[str, str]], check_id: str, status: str, summary: str) -> None:
    checks.append({"id": check_id, "status": status, "summary": summary})


def collect_doctor_report(
    *,
    api_url: str | None = None,
    dashboard_url: str | None = None,
    relay_port: int | None = None,
    timeout: float = 2.0,
    probe: Probe = http_probe,
    site_dirs: Iterable[Path] | None = None,
) -> dict[str, Any]:
    """Collect a stable, JSON-serializable diagnostic report."""
    manifest = _read_simple_manifest(PLUGIN_DIR / "plugin.yaml")
    api_base = _base_url(api_url, _default_api_url())
    dashboard_base = _base_url(dashboard_url, _default_dashboard_url())
    port = relay_port if relay_port is not None else _default_relay_port()

    api_capabilities = probe(_url(api_base, "/v1/capabilities"), method="GET", timeout=timeout)
    dashboard_status = probe(_url(dashboard_base, "/api/status"), method="GET", timeout=timeout)
    dashboard_audio = probe(
        _url(dashboard_base, "/api/audio/transcribe"),
        method="HEAD",
        timeout=timeout,
    )
    dashboard_ws_ticket = probe(
        _url(dashboard_base, "/api/auth/ws-ticket"),
        method="POST",
        timeout=timeout,
    )
    relay_info = probe(
        f"http://127.0.0.1:{int(port)}/relay/info",
        method="GET",
        timeout=timeout,
    )

    layout = _plugin_manager_layout(PLUGIN_DIR)
    bootstrap = _bootstrap_status(site_dirs)
    relay_import = _relay_import_chain()
    checks: list[dict[str, str]] = []

    _check(
        checks,
        "plugin-root",
        "ok" if layout["has_plugin_yaml"] and layout["has_register_init"] else "error",
        "plugin root has plugin.yaml and register-capable __init__.py",
    )
    _check(
        checks,
        "dashboard-plugin",
        "ok" if layout["has_dashboard_manifest"] else "warn",
        "dashboard manifest is present under the plugin root",
    )
    _check(
        checks,
        "api-capabilities",
        "ok" if api_capabilities.get("ok") else "warn",
        "standard API /v1/capabilities reachable"
        if api_capabilities.get("ok")
        else "standard API not reachable from this host",
    )
    _check(
        checks,
        "dashboard-status",
        "ok" if dashboard_status.get("ok") else "warn",
        "dashboard /api/status reachable"
        if dashboard_status.get("ok")
        else "dashboard not reachable from this host",
    )
    _check(
        checks,
        "dashboard-audio",
        "ok" if dashboard_audio.get("exists") else "warn",
        "dashboard audio route exists"
        if dashboard_audio.get("exists")
        else "dashboard audio route was not detected",
    )
    _check(
        checks,
        "dashboard-ws-ticket",
        "ok" if dashboard_ws_ticket.get("exists") else "warn",
        "dashboard WebSocket ticket route exists"
        if dashboard_ws_ticket.get("exists")
        else "dashboard WebSocket ticket route was not detected",
    )
    _check(
        checks,
        "relay-import-chain",
        "ok" if relay_import["ok"] else "error",
        f"relay server module chain imports cleanly ({relay_import['module']})"
        if relay_import["ok"]
        else (
            f"importing {relay_import['module']} failed "
            f"({relay_import['error']}) — `hermes relay start` will crash. "
            "If the error names a missing 'plugin' module, this plugin build "
            "predates native-layout support; update it "
            "(hermes plugins install Codename-11/hermes-relay/plugin) or "
            "reinstall with install.sh."
        ),
    )
    _check(
        checks,
        "relay-loopback",
        "ok" if relay_info.get("ok") else "warn",
        "relay loopback /relay/info reachable"
        if relay_info.get("ok")
        else "relay is not reachable on loopback",
    )
    _check(
        checks,
        "legacy-bootstrap",
        "warn" if bootstrap["installed"] else "ok",
        "legacy bootstrap monkeypatch is installed"
        if bootstrap["installed"]
        else "legacy bootstrap monkeypatch is not installed",
    )

    return {
        "schema_version": 1,
        "plugin": {
            "name": manifest.get("name", PLUGIN_NAME),
            "version": manifest.get("version", ""),
            "layout": layout,
        },
        "standard": {
            "api_url": api_base,
            "dashboard_url": dashboard_base,
            "api": {"capabilities": api_capabilities},
            "dashboard": {
                "status": dashboard_status,
                "audio_transcribe": dashboard_audio,
                "ws_ticket": dashboard_ws_ticket,
            },
        },
        "relay": {
            "port": int(port),
            "info": relay_info,
            "import_chain": relay_import,
        },
        "bootstrap": bootstrap,
        "lifecycle": {
            "upstream_plugin_remove_cleans_external_artifacts": False,
            "external_artifacts": [
                "editable/root Python package installs",
                "systemd user service",
                "shell shims",
                "external skill path entries",
            ],
            "compat_cleanup_surface": "hermes relay compat remove",
            "legacy_cleanup_surface": (
                "uninstall.sh for service/shim/root-package legacy installs; "
                "delegates compat hook removal to hermes relay compat when available"
            ),
        },
        "checks": checks,
    }


def render_doctor_text(report: dict[str, Any]) -> str:
    plugin = report.get("plugin", {})
    standard = report.get("standard", {})
    relay = report.get("relay", {})
    bootstrap = report.get("bootstrap", {})
    layout = plugin.get("layout", {})

    lines = [
        "Hermes-Relay doctor",
        "",
        f"Plugin: {plugin.get('name', PLUGIN_NAME)} {plugin.get('version', '')}".rstrip(),
        f"Path: {layout.get('plugin_dir', '')}",
        f"Install identifier: {layout.get('recommended_install_identifier', '')}",
        "",
        "Standard Hermes:",
        f"  API: {standard.get('api_url', '')}",
        f"  Dashboard: {standard.get('dashboard_url', '')}",
        "",
        "Relay:",
        f"  Loopback port: {relay.get('port', DEFAULT_RELAY_PORT)}",
        "",
        "Legacy bootstrap:",
        f"  Installed: {'yes' if bootstrap.get('installed') else 'no'}",
    ]

    pth_files = bootstrap.get("pth_files") or []
    if pth_files:
        lines.append("  .pth files:")
        lines.extend(f"    {p}" for p in pth_files)

    lines.append("")
    lines.append("Checks:")
    for check in report.get("checks", []):
        status = str(check.get("status", "unknown")).upper()
        lines.append(f"  [{status}] {check.get('id')}: {check.get('summary')}")

    lines.append("")
    lines.append(
        "Note: standard chat, Manage, and dashboard voice should work against "
        "vanilla upstream Hermes. Relay and bootstrap surfaces are additive."
    )
    return "\n".join(lines)


def doctor_command(args: argparse.Namespace) -> int:
    report = collect_doctor_report(
        api_url=getattr(args, "api_url", None),
        dashboard_url=getattr(args, "dashboard_url", None),
        relay_port=getattr(args, "relay_port", None),
        timeout=float(getattr(args, "timeout", 2.0)),
    )

    if getattr(args, "json", False):
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(render_doctor_text(report))

    strict = bool(getattr(args, "strict", False))
    if strict and any(c.get("status") in {"warn", "error"} for c in report["checks"]):
        return 1
    if any(c.get("status") == "error" for c in report["checks"]):
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="hermes relay doctor",
        description="Inspect Hermes-Relay plugin, standard Hermes, relay, and legacy bootstrap state.",
    )
    parser.add_argument("--json", action="store_true", help="Emit JSON")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero on warnings")
    parser.add_argument("--api-url", help="Hermes API base URL")
    parser.add_argument("--dashboard-url", help="Hermes dashboard base URL")
    parser.add_argument("--relay-port", type=int, help="Relay loopback port")
    parser.add_argument("--timeout", type=float, default=2.0, help="Probe timeout in seconds")
    return doctor_command(parser.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
