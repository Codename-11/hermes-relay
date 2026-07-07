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
# Canonical plugin version source is pyproject.toml's [project].version.
# Keep this runtime constant in sync with pyproject.toml for plugin-v*
# releases. Android releases use gradle/libs.versions.toml and android-v* tags;
# CLI releases use desktop/package.json and cli-v* tags. The /health endpoint
# reports this plugin version, and stale values make live diagnosis harder than
# it should be.
__version__ = "1.3.0"

from .server import create_app, main  # noqa: E402 — must come after __version__

__all__ = ["create_app", "main", "__version__"]
