"""CLI sub-command registration for the hermes-android plugin.

Registers the following top-level `hermes` sub-commands:

    hermes pair [--png] [--no-qr] [--host HOST] [--port PORT]
    hermes relay start [--port PORT] [--no-ssl] [--log-level LEVEL]

Discovered by the hermes-agent v0.8.0+ plugin CLI registration system. The
plugin loader calls ``register_cli(subparser)`` with a freshly-built parser
for each sub-command; handlers are dispatched via ``args.func``.
"""

from __future__ import annotations


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
    start.set_defaults(func=relay_start_command)


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
