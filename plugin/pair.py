"""Hermes Android pairing — generate QR codes and connection info.

Replaces the standalone bash script `skills/hermes-pairing-qr/hermes-pair`.
Exposed as the `hermes pair` CLI sub-command via plugin/cli.py.

The payload format matches what QrPairingScanner.kt in the Android app expects:
    {"hermes":1,"host":"<ip>","port":<port>,"key":"<token>","tls":<bool>}
"""

from __future__ import annotations

import io
import json
import os
import socket
import sys
import tempfile
from pathlib import Path
from typing import Optional


def _get_hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))


def _parse_env_file(path: Path) -> dict[str, str]:
    """Parse a simple KEY=value env file. Ignores comments and blank lines."""
    result: dict[str, str] = {}
    if not path.exists():
        return result
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            # Strip matching surrounding quotes
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            result[key.strip()] = value
    except OSError:
        pass
    return result


def read_server_config() -> dict:
    """Read API server config with fallback chain.

    Priority: hermes config.yaml → ~/.hermes/.env → environment vars → defaults.
    Returns dict with keys: host, port, key, tls.
    """
    host: Optional[str] = None
    port: Optional[int] = None
    key: Optional[str] = None
    tls = False

    # 1. Try hermes_cli.config.load_config()
    try:
        from hermes_cli.config import load_config  # type: ignore

        config = load_config()
        api = config.get("platforms", {}).get("api_server", {}) or {}
        extra = api.get("extra", {}) or {}
        key = extra.get("key") or api.get("api_key")
        port_val = extra.get("port") or api.get("port")
        if port_val is not None:
            port = int(port_val)
        host = extra.get("host") or api.get("host")
    except Exception:
        pass

    # 2. Fall back to ~/.hermes/.env
    if key is None or host is None or port is None:
        env_vals = _parse_env_file(_get_hermes_home() / ".env")
        if key is None:
            key = env_vals.get("API_SERVER_KEY")
        if host is None:
            host = env_vals.get("API_SERVER_HOST")
        if port is None and env_vals.get("API_SERVER_PORT"):
            try:
                port = int(env_vals["API_SERVER_PORT"])
            except ValueError:
                pass

    # 3. Fall back to process environment
    if key is None:
        key = os.getenv("API_SERVER_KEY", "")
    if host is None:
        host = os.getenv("API_SERVER_HOST", "127.0.0.1")
    if port is None:
        try:
            port = int(os.getenv("API_SERVER_PORT", "8642"))
        except ValueError:
            port = 8642

    return {"host": host, "port": port, "key": key or "", "tls": tls}


def _resolve_lan_ip(host: str) -> str:
    """Auto-detect LAN IP when host is loopback or bind-all.

    Cross-platform: uses a UDP socket connect() trick to find the outbound
    interface IP without sending any packets.
    """
    if host not in ("0.0.0.0", "127.0.0.1", "localhost", "::", "::1"):
        return host
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        # Doesn't actually send; just picks the outbound interface
        s.connect(("1.1.1.1", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return host


def build_payload(host: str, port: int, key: str, tls: bool) -> str:
    """Build compact JSON payload matching HermesPairingPayload.kt format."""
    return json.dumps(
        {"hermes": 1, "host": host, "port": port, "key": key, "tls": tls},
        separators=(",", ":"),
    )


def _mask_key(key: str) -> str:
    """Return a redacted preview of the API key."""
    if not key:
        return "(none - open access)"
    if len(key) <= 8:
        return "*" * len(key) + f" ({len(key)} chars)"
    return f"{key[:4]}...{key[-3:]} ({len(key)} chars)"


def render_text_block(host: str, port: int, key: str, tls: bool) -> str:
    """Return formatted connection details — always shown (works in any terminal)."""
    scheme = "https" if tls else "http"
    url = f"{scheme}://{host}:{port}"
    auth_status = "Bearer token configured" if key else "NO AUTH (open access)"

    lines = [
        "",
        "  Hermes Android Pairing",
        "  " + "-" * 40,
        "",
        f"  Server : {url}",
        f"  API Key: {_mask_key(key)}",
        f"  Auth   : {auth_status}",
        "",
        "  Enter manually in the app if QR won't scan:",
        f"    URL: {url}",
    ]
    if key:
        lines.append(f"    Key: {key}")
    lines.append("")
    return "\n".join(lines)


def render_qr_terminal(payload: str) -> str:
    """Render QR as Unicode half-block string for terminal output.

    Returns a helpful message if segno is not installed (graceful fallback).
    """
    try:
        import segno  # type: ignore
    except ImportError:
        return (
            "  (QR rendering unavailable — install segno: pip install segno)\n"
            "  Use the text details above to enter connection info manually.\n"
        )

    try:
        qr = segno.make(payload, error="m")
        buf = io.StringIO()
        qr.terminal(out=buf, compact=True)
        return buf.getvalue()
    except Exception as e:
        return f"  (QR render failed: {e})\n"


def render_qr_png(payload: str, path: Optional[str] = None) -> Optional[str]:
    """Save QR as PNG file. Returns the file path, or None on failure."""
    try:
        import segno  # type: ignore
    except ImportError:
        return None

    if path is None:
        path = str(Path(tempfile.gettempdir()) / "hermes-pairing-qr.png")

    try:
        qr = segno.make(payload, error="m")
        qr.save(path, scale=8, border=2)
        return path
    except Exception:
        return None


def pair_command(args) -> None:
    """CLI entry point for `hermes pair`. Called by argparse dispatch."""
    config = read_server_config()

    # Apply CLI overrides
    if getattr(args, "host", None):
        config["host"] = args.host
    if getattr(args, "port", None):
        config["port"] = int(args.port)

    host = _resolve_lan_ip(config["host"])
    port = config["port"]
    key = config["key"]
    tls = config["tls"]

    payload = build_payload(host, port, key, tls)

    # Always show text block — works in any terminal including Hermes TUI
    print(render_text_block(host, port, key, tls))

    png_only = getattr(args, "png", False)
    no_qr = getattr(args, "no_qr", False)

    if png_only:
        path = render_qr_png(payload)
        if path:
            print(f"  PNG saved: {path}")
        else:
            print("  PNG render failed — install segno: pip install segno")
    elif not no_qr and sys.stdout.isatty():
        print(render_qr_terminal(payload))
        png_path = render_qr_png(payload)
        if png_path:
            print(f"  PNG: {png_path}")
        print("  Scan with the Hermes-Relay Android app.")

    print("  WARNING: This contains your API key. Do not share screenshots.\n")


if __name__ == "__main__":
    # Allow running as `python -m plugin.pair` for manual testing
    import argparse

    parser = argparse.ArgumentParser(description="Hermes Android pairing")
    parser.add_argument("--png", action="store_true", help="Save PNG only")
    parser.add_argument("--no-qr", action="store_true", dest="no_qr", help="Text only")
    parser.add_argument("--host", help="Override server host")
    parser.add_argument("--port", type=int, help="Override server port")
    pair_command(parser.parse_args())
