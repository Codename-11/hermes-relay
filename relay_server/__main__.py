"""Entry point: ``python -m relay_server`` — delegates to ``plugin.relay``.

Kept as a thin shim so legacy docs, scripts, and systemd units that still
reference ``relay_server`` keep working. The ``load_hermes_env`` call
mirrors the one in ``plugin/relay/__main__.py`` so both entry points
bootstrap ``~/.hermes/.env`` identically — without this, launching via
the legacy entrypoint would skip env loading and 500 on voice endpoints.
"""

from plugin.relay._env_bootstrap import load_hermes_env

load_hermes_env()

from plugin.relay.server import main  # noqa: E402  — import order is deliberate

main()
