"""Compat shim — the canonical relay lives at ``plugin.relay``.

Kept so existing entrypoints (``python -m relay_server``, legacy imports in
scripts and docs) continue to work after the Phase 2 plugin consolidation.
New code should import from ``plugin.relay`` directly.
"""

from plugin.relay import __version__, create_app, main  # noqa: F401

__all__ = ["__version__", "create_app", "main"]
