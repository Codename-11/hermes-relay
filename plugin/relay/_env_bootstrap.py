"""Load ``~/.hermes/.env`` before the relay imports anything that reads
environment variables.

Mirrors ``hermes_cli/main.py``'s startup sequence so the relay behaves
consistently across every launch path — systemd user unit, nohup, docker,
tmux, or a plain ``python -m plugin.relay`` invocation. The gateway already
relies on this pattern (its systemd unit has no ``EnvironmentFile=``),
and the relay ran into drift bugs whenever it was restarted without the
env sourced manually: voice endpoints would 500 with
``STT provider 'openai' configured but no API key available`` even though
the key was sitting in ``~/.hermes/.env``.

Precedence (matching ``hermes_cli.env_loader.load_hermes_dotenv``):

    1. ``$HERMES_HOME/.env`` (or ``~/.hermes/.env`` if ``HERMES_HOME`` is unset)
    2. Nothing else — the relay is always installed alongside hermes-agent

Failure modes are silent so a container image that explicitly provides env
via other means (``docker run -e ...``) can still start the relay without
``python-dotenv`` installed.
"""

from __future__ import annotations

import os
from pathlib import Path


def load_hermes_env() -> list[Path]:
    """Load ``~/.hermes/.env`` into :data:`os.environ`.

    Returns the list of files actually loaded (useful for diagnostics /
    tests). Prefers the upstream ``hermes_cli.env_loader`` helper when
    importable so we share the exact precedence and encoding fallbacks
    the rest of Hermes uses; falls back to a direct ``python-dotenv``
    call, and no-ops if neither is available.
    """
    # Preferred path — same helper ``hermes_cli/main.py`` calls. Keeps
    # loading semantics (override=True on the user env, latin-1 fallback,
    # HERMES_HOME resolution) in exact lockstep with hermes-agent.
    try:
        from hermes_cli.env_loader import load_hermes_dotenv  # type: ignore
    except ImportError:
        pass
    else:
        try:
            return list(load_hermes_dotenv())
        except Exception:
            # If hermes_cli is broken for some reason, fall through to the
            # direct dotenv path rather than crashing the relay.
            pass

    # Fallback — direct python-dotenv. Same file, same override behavior.
    try:
        from dotenv import load_dotenv  # type: ignore
    except ImportError:
        return []

    home = Path(os.getenv("HERMES_HOME") or (Path.home() / ".hermes"))
    env_path = home / ".env"
    if not env_path.is_file():
        return []

    try:
        load_dotenv(dotenv_path=env_path, override=True, encoding="utf-8")
    except UnicodeDecodeError:
        # Some users have .env files saved as latin-1 (notepad default on
        # older Windows). Retry once rather than dying on a BOM.
        load_dotenv(dotenv_path=env_path, override=True, encoding="latin-1")
    return [env_path]
