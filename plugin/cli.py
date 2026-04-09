"""CLI sub-command registration for the hermes-android plugin.

Registers: hermes pair [--png] [--no-qr] [--host HOST] [--port PORT]

Discovered by hermes-agent v0.8.0+ plugin CLI registration system. The
plugin loader calls `register_cli(subparser)` with a freshly-built parser
for the `pair` sub-command.
"""

from __future__ import annotations


def register_cli(subparser) -> None:
    """Attach `hermes pair` arguments to the provided subparser.

    Called by the hermes-agent plugin loader during argparse setup.
    """
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
        "--host",
        metavar="HOST",
        help="Override server host (default: auto-detect LAN IP)",
    )
    subparser.add_argument(
        "--port",
        metavar="PORT",
        type=int,
        help="Override server port (default: 8642)",
    )
    subparser.set_defaults(func=pair_command)


def pair_command(args) -> None:
    """Dispatch to the pairing module (lazy import for fast CLI startup)."""
    from .pair import pair_command as _pair

    _pair(args)
