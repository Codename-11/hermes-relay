"""Hermes-Relay server — unified WSS server embedded in the hermes-android plugin.

Exposes the chat, terminal, and bridge channels over a single WebSocket on
port 8767 (default). Replaces the standalone ``relay_server/`` package; the
top-level ``relay_server`` module is kept as a thin compat shim so existing
entrypoints like ``python -m relay_server`` keep working.

See ``plugin/relay/server.py`` for the aiohttp server,
``plugin/relay/channels/terminal.py`` for the PTY-backed terminal handler.
"""

# Define __version__ *before* importing .server — server.py reads it back
# during its own import via ``from . import __version__``, so this ordering
# avoids a circular-import crash during package initialization.
#
# Keep this in sync with pyproject.toml's [project].version. The /health
# endpoint reports this string, and shipping a stale version makes
# diagnosing "is the running process up to date?" harder than it should be
# (sample: spent two hours on Docker-Server 2026-04-12 because the running
# relay still reported 0.2.0 from this constant after a successful pull).
__version__ = "0.5.0"

from .server import create_app, main  # noqa: E402 — must come after __version__

__all__ = ["create_app", "main", "__version__"]
