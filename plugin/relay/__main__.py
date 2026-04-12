"""Entry point: ``python -m plugin.relay``.

We load ``~/.hermes/.env`` here — before the first import from
:mod:`.server` — so every module that reads ``os.getenv`` at import time
(config, auth, the upstream voice tools the relay hands off to) sees the
same environment regardless of how the process was launched. The gateway
does the same thing in ``hermes_cli/main.py`` and that's why its systemd
unit doesn't need an ``EnvironmentFile=`` directive.
"""

from ._env_bootstrap import load_hermes_env

load_hermes_env()

from .server import main  # noqa: E402  — import order is deliberate

main()
