"""Command-line wrapper around :mod:`plugin.relay.tailscale`.

Invoked via the ``hermes-relay-tailscale`` shim installed to
``~/.local/bin``. Subcommands::

    hermes-relay-tailscale status [--json]
    hermes-relay-tailscale enable [--dashboard-port N] [--api-port N] [--no-https] [--json]
    hermes-relay-tailscale disable [--dashboard-port N] [--api-port N] [--json]

The legacy direct-Relay form remains available deliberately as
``enable|disable --relay-only [--port N]``. New recommended setup publishes
Dashboard on 9119 so Relay uses its same-origin plugin transport.

All subcommands exit 0 on success, 1 on structured failure. ``--json``
prints the raw structured dict for scripting.

# TODO(upstream-merge #9295): remove this module when the canonical
# ``hermes gateway run --tailscale`` flag lands upstream. See
# :mod:`plugin.relay.tailscale` for the capability probe.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any

from . import tailscale


def _print_result(result: dict[str, Any] | None, *, as_json: bool) -> int:
    """Pretty-print ``result`` and return a process exit code."""
    if as_json:
        sys.stdout.write(json.dumps(result, indent=2, sort_keys=True) + "\n")
    else:
        if result is None:
            sys.stdout.write("tailscale: not available (binary absent or daemon stopped)\n")
        else:
            for key, value in result.items():
                sys.stdout.write(f"{key}: {value}\n")

    if result is None:
        return 1
    if isinstance(result, dict) and result.get("ok") is False:
        return 1
    return 0


def _cmd_status(args: argparse.Namespace) -> int:
    result = tailscale.status()
    return _print_result(result, as_json=args.json)


def _cmd_enable(args: argparse.Namespace) -> int:
    if args.relay_only or args.port is not None:
        port = args.port if args.port is not None else tailscale.DEFAULT_RELAY_PORT
        result = tailscale.enable(port=port, https=not args.no_https)
    else:
        result = tailscale.enable_stack(
            relay_port=args.dashboard_port,
            api_port=args.api_port,
            https=not args.no_https,
        )
    return _print_result(result, as_json=args.json)


def _cmd_disable(args: argparse.Namespace) -> int:
    if args.relay_only or args.port is not None:
        port = args.port if args.port is not None else tailscale.DEFAULT_RELAY_PORT
        result = tailscale.disable(port=port)
    else:
        result = tailscale.disable_stack(
            relay_port=args.dashboard_port,
            api_port=args.api_port,
        )
    return _print_result(result, as_json=args.json)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hermes-relay-tailscale",
        description="Thin wrapper around the tailscale CLI for publishing "
                    "Dashboard + optional API ports over the tailnet.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit JSON instead of a human-readable summary.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_status = sub.add_parser("status", help="Show Tailscale state + served ports.")
    p_status.set_defaults(func=_cmd_status)

    p_enable = sub.add_parser(
        "enable",
        help="Publish Dashboard + optional API via tailscale serve.",
    )
    p_enable.add_argument(
        "--dashboard-port",
        type=int,
        default=tailscale.DEFAULT_DASHBOARD_PORT,
        help="Dashboard listener used by the recommended stack (default: 9119).",
    )
    p_enable.add_argument(
        "--port",
        type=int,
        default=None,
        help="Deprecated: publish one explicit legacy/direct port.",
    )
    p_enable.add_argument("--api-port", type=int, default=tailscale.DEFAULT_API_PORT)
    p_enable.add_argument(
        "--relay-only",
        action="store_true",
        help="Deprecated: publish direct Relay only (default port: 8767).",
    )
    p_enable.add_argument(
        "--no-https",
        action="store_true",
        help="Serve over plain HTTP instead of Tailscale-managed HTTPS.",
    )
    p_enable.set_defaults(func=_cmd_enable)

    p_disable = sub.add_parser(
        "disable",
        help="Stop the recommended Dashboard + optional API publications.",
    )
    p_disable.add_argument(
        "--dashboard-port",
        type=int,
        default=tailscale.DEFAULT_DASHBOARD_PORT,
        help="Dashboard listener managed by the recommended stack (default: 9119).",
    )
    p_disable.add_argument(
        "--port",
        type=int,
        default=None,
        help="Deprecated: disable one explicit legacy/direct port.",
    )
    p_disable.add_argument("--api-port", type=int, default=tailscale.DEFAULT_API_PORT)
    p_disable.add_argument(
        "--relay-only",
        action="store_true",
        help="Deprecated: disable direct Relay only (default port: 8767).",
    )
    p_disable.set_defaults(func=_cmd_disable)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
