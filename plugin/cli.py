"""CLI sub-command registration for the hermes-android plugin.

Registers the following top-level `hermes` sub-commands:

    hermes pair [--png] [--no-qr] [--host HOST] [--port PORT]
    hermes relay start [--port PORT] [--no-ssl] [--log-level LEVEL]
    hermes relay insecure-api-key [status|on|off]
    hermes-relay insecure-api-key [status|on|off]

Discovered by the hermes-agent v0.8.0+ plugin CLI registration system. The
plugin loader calls ``register_cli(subparser)`` with a freshly-built parser
for each sub-command; handlers are dispatched via ``args.func``.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request


# ── hermes pair ───────────────────────────────────────────────────────────────


def register_cli(subparser) -> None:
    """Attach `hermes pair` arguments to the provided subparser."""
    subparser.add_argument(
        "--png",
        action="store_true",
        help="Save PNG to the system temp dir only (no terminal QR)",
    )
    subparser.add_argument(
        "--no-qr",
        action="store_true",
        dest="no_qr",
        help="Show connection details as text only (no QR code)",
    )
    subparser.add_argument(
        "--no-relay",
        action="store_true",
        dest="no_relay",
        help=(
            "Skip relay pre-pairing. Renders an API-only QR — useful if "
            "you're only pairing for direct chat and haven't started the "
            "relay server."
        ),
    )
    subparser.add_argument(
        "--host",
        metavar="HOST",
        help="Override API server host (default: auto-detect LAN IP)",
    )
    subparser.add_argument(
        "--port",
        metavar="PORT",
        type=int,
        help="Override API server port (default: 8642)",
    )
    subparser.add_argument(
        "--ttl",
        metavar="DURATION",
        default="30d",
        help=(
            "Session TTL — one of 1d/7d/30d/90d/1y/never, or an explicit "
            "<N><unit> like 12h/4w. 'never' means the session never expires. "
            "Default: 30d."
        ),
    )
    subparser.add_argument(
        "--grants",
        metavar="SPEC",
        default=None,
        help=(
            "Per-channel grant overrides, comma-separated channel=duration "
            "pairs, e.g. 'terminal=7d,bridge=1d'. Unspecified channels fall "
            "back to server-side defaults (terminal capped at 30d, bridge "
            "capped at 7d)."
        ),
    )
    subparser.set_defaults(func=pair_command)


def pair_command(args) -> None:
    """Dispatch to the pairing module (lazy import for fast CLI startup)."""
    from .pair import pair_command as _pair

    _pair(args)


# ── hermes relay ──────────────────────────────────────────────────────────────


def register_relay_cli(subparser) -> None:
    """Attach `hermes relay` sub-parser.

    Currently exposes ``hermes relay start`` (run the WSS server in the
    foreground). Lifecycle management (``status``/``stop``) is deferred to
    a later session since the plan calls for libtmux-driven persistence
    that we haven't built yet.
    """
    sub = subparser.add_subparsers(dest="relay_cmd", required=True)

    start = sub.add_parser(
        "start",
        help="Run the Hermes-Relay WSS server (chat + terminal + bridge)",
    )
    start.add_argument("--host", metavar="HOST", help="Bind address (default: 0.0.0.0)")
    start.add_argument("--port", type=int, help="Listen port (default: 8767)")
    start.add_argument(
        "--no-ssl",
        action="store_true",
        help="Disable SSL (development only)",
    )
    start.add_argument(
        "--shell",
        metavar="PATH",
        help="Default shell for terminal sessions (absolute path; default: $SHELL)",
    )
    start.add_argument(
        "--webapi-url",
        metavar="URL",
        help="Hermes WebAPI base URL (default: http://localhost:8642)",
    )
    start.add_argument(
        "--log-level",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Log level (default: INFO)",
    )
    start.add_argument(
        "--allow-insecure-api-key",
        "--allow-insecure-api-bearer",
        action="store_true",
        dest="allow_insecure_api_bearer",
        help=(
            "Allow Hermes API-key voice auth over non-loopback plain HTTP "
            "(local LAN testing only)."
        ),
    )
    start.set_defaults(func=relay_start_command)

    insecure = sub.add_parser(
        "insecure-api-key",
        aliases=["insecure-api-bearer"],
        help="Show or change the running relay's plain-HTTP API-key voice auth toggle",
    )
    insecure.add_argument(
        "state",
        nargs="?",
        default="status",
        choices=["status", "on", "off", "enable", "disable"],
        help="Toggle state to apply to the running relay (default: status)",
    )
    insecure.add_argument(
        "--host",
        default="127.0.0.1",
        help="Loopback relay host to contact (default: 127.0.0.1)",
    )
    insecure.add_argument("--port", type=int, help="Relay port (default: RELAY_PORT or 8767)")
    insecure.add_argument("--json", action="store_true", help="Print raw JSON")
    insecure.set_defaults(func=relay_insecure_api_key_command)


def relay_command(args):
    """Dispatch `hermes relay` subcommands when the host CLI calls one handler."""
    handler = getattr(args, "func", None)
    if handler is not None and handler is not relay_command:
        return handler(args)
    return relay_start_command(args)


def relay_start_command(args) -> None:
    """Run the relay server in the foreground.

    Assembles a ``RelayConfig`` from env, applies CLI overrides, then hands
    off to :func:`plugin.relay.server.main`. Lazy-imports so ``hermes --help``
    stays fast.
    """
    import logging
    import sys

    from aiohttp import web

    from .relay import __version__
    from .relay.config import RelayConfig
    from .relay.server import create_app, _create_ssl_context

    config = RelayConfig.from_env()
    if getattr(args, "host", None):
        config.host = args.host
    if getattr(args, "port", None):
        config.port = args.port
    if getattr(args, "webapi_url", None):
        config.webapi_url = args.webapi_url
    if getattr(args, "log_level", None):
        config.log_level = args.log_level
    if getattr(args, "shell", None):
        config.terminal_shell = args.shell
    if getattr(args, "allow_insecure_api_bearer", False):
        config.allow_insecure_api_bearer = True

    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    app = create_app(config)

    ssl_ctx = None if getattr(args, "no_ssl", False) else _create_ssl_context(config)
    if ssl_ctx is None and not getattr(args, "no_ssl", False):
        logging.warning(
            "No SSL cert/key configured. Use --no-ssl for development, "
            "or set RELAY_SSL_CERT and RELAY_SSL_KEY for production."
        )

    scheme = "wss" if ssl_ctx else "ws"
    logging.info(
        "Starting Hermes-Relay v%s on %s://%s:%d",
        __version__,
        scheme,
        config.host,
        config.port,
    )

    web.run_app(
        app,
        host=config.host,
        port=config.port,
        ssl_context=ssl_ctx,
        print=None,
    )


def _relay_cli_port(args) -> int:
    if getattr(args, "port", None) is not None:
        return int(args.port)
    raw = os.environ.get("RELAY_PORT", "").strip()
    if raw:
        try:
            return int(raw)
        except ValueError:
            print(
                f"warning: invalid RELAY_PORT={raw!r}; using 8767",
                file=sys.stderr,
            )
    return 8767


def _relay_security_request(
    *,
    host: str,
    port: int,
    allow_insecure_api_bearer: bool | None,
    timeout: float = 5.0,
) -> dict:
    url = f"http://{host}:{port}/relay/security"
    data: bytes | None = None
    method = "GET"
    headers: dict[str, str] = {}
    if allow_insecure_api_bearer is not None:
        method = "PATCH"
        data = json.dumps(
            {"allow_insecure_api_bearer": allow_insecure_api_bearer}
        ).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        try:
            raw_error = exc.read().decode("utf-8")
            parsed = json.loads(raw_error)
            detail = parsed.get("error") if isinstance(parsed, dict) else raw_error
        except Exception:
            detail = exc.reason
        raise RuntimeError(f"Relay returned HTTP {exc.code}: {detail}") from exc
    except (urllib.error.URLError, OSError) as exc:
        raise RuntimeError(
            f"Could not reach relay at {url}. Is Hermes-Relay running?"
        ) from exc

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Relay returned non-JSON response from {url}") from exc
    if not isinstance(parsed, dict):
        raise RuntimeError(f"Relay returned invalid response from {url}")
    return parsed


def relay_insecure_api_key_command(args) -> None:
    """Toggle runtime acceptance of API-key voice auth over plain LAN HTTP."""
    state = getattr(args, "state", "status")
    desired: bool | None
    if state == "status":
        desired = None
    elif state in ("on", "enable"):
        desired = True
    else:
        desired = False

    host = getattr(args, "host", None) or "127.0.0.1"
    port = _relay_cli_port(args)
    try:
        data = _relay_security_request(
            host=host,
            port=port,
            allow_insecure_api_bearer=desired,
        )
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc

    if getattr(args, "json", False):
        print(json.dumps(data, indent=2, sort_keys=True))
        return

    enabled = bool(data.get("allow_insecure_api_bearer"))
    label = "enabled" if enabled else "disabled"
    if desired is None:
        print(f"Insecure API-key voice auth is {label} on {host}:{port}.")
    else:
        print(f"Insecure API-key voice auth {label} on {host}:{port}.")
    if enabled:
        print(
            "Plain HTTP LAN voice requests can now use the saved Hermes API key. "
            "Disable with `hermes relay insecure-api-key off` or "
            "`hermes-relay insecure-api-key off`."
        )


def main(argv: list[str] | None = None) -> None:
    """Standalone entry point for hosts without plugin CLI discovery.

    Newer Hermes Agent builds can expose this module as ``hermes relay ...``.
    Installed relay hosts also get a ``hermes-relay`` shim that calls this
    entry point directly, which keeps runtime operations available on older
    agent CLIs.
    """
    import argparse

    args_list = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(prog="hermes-relay")

    if args_list and args_list[0] == "relay":
        sub = parser.add_subparsers(dest="command", required=True)
        relay = sub.add_parser("relay", help="Manage the Hermes-Relay server")
        register_relay_cli(relay)
    else:
        register_relay_cli(parser)

    parsed = parser.parse_args(args_list)
    handler = getattr(parsed, "func", None)
    if handler is None:
        parser.error("missing command")
    handler(parsed)


if __name__ == "__main__":
    main()
