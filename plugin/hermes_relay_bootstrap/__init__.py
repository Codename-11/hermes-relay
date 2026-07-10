"""hermes_relay_bootstrap — runtime patch for vanilla upstream hermes-agent.

This package is loaded at Python interpreter startup via a `.pth` file in the
hermes-agent venv's site-packages. It installs a `sys.meta_path` import hook
that waits for `aiohttp.web` to be imported, then replaces `web.Application`
with a thin subclass that detects when hermes-agent's `APIServerAdapter`
attaches itself to a fresh app and:

1. **Injects missing compatibility-only routes** — surfaces with no native
   upstream API-server replacement yet: `/api/sessions/search`, `/api/memory`,
   `/api/skills/{name}` (legacy detail), `PUT /api/skills/toggle` (501 stub),
   `/api/config`, and `/api/available-models`. Native routes still win per
   method/path if any of these ever land in core.

   Retired and no longer injected (native upstream owns them): sessions
   CRUD/messages/fork (`/api/sessions/*`, PR #33134) and the legacy read-only
   `GET /api/skills` list (native `/v1/skills` + `/v1/toolsets`, PR #33016).
   Older pre-#33134 core builds no longer get bootstrap-provided session CRUD;
   clients degrade via capability probing to `/v1/chat/completions` / `/v1/runs`.

2. **Installs slash-command middleware** — an aiohttp middleware that intercepts
   `/v1/chat/completions` and `/v1/runs` to handle gateway slash commands
   (`/help`, `/commands`, `/profile`, `/provider`) and return decline notices
   for stateful commands (`/model`, `/new`, `/retry`, etc.), preventing the
   LLM from hallucinating responses for them. This mirrors the upstream
   Stage 1 preprocessor from `gateway/platforms/api_server_slash.py` and
   skips itself when that native module exists.

This module keeps retiring per surface, not as one broad cleanup. The
remaining config/memory/legacy skill detail+toggle/available-models/session
search/slash-command surfaces need stable native replacements or deliberate
local UX removal before the package and `.pth` hook can disappear entirely.
"""

from __future__ import annotations

import logging
import sys

logger = logging.getLogger(__name__)

# Import and install the meta_path finder. Kept in a sub-module so this file
# stays tiny and the actual patch logic is easy to audit / disable.
from . import _patch  # noqa: E402

_patch.install_finder()
