"""hermes_relay_bootstrap — runtime patch for vanilla upstream hermes-agent.

This package is loaded at Python interpreter startup via a `.pth` file in the
hermes-agent venv's site-packages. It installs a `sys.meta_path` import hook
that waits for `aiohttp.web` to be imported, then replaces `web.Application`
with a thin subclass that detects when hermes-agent's `APIServerAdapter`
attaches itself to a fresh app and:

1. **Injects missing compatibility routes** — older hermes-agent builds may
   lack `/api/sessions/*`, `/api/memory`, `/api/skills`, `/api/config`, and
   `/api/available-models`. Current upstream already has the session API and
   read-only `/v1/skills` + `/v1/toolsets`, so native routes win per method/path
   and the bootstrap only fills gaps.

2. **Installs slash-command middleware** — an aiohttp middleware that intercepts
   `/v1/chat/completions` and `/v1/runs` to handle gateway slash commands
   (`/help`, `/commands`, `/profile`, `/provider`) and return decline notices
   for stateful commands (`/model`, `/new`, `/retry`, etc.), preventing the
   LLM from hallucinating responses for them. This mirrors the upstream
   Stage 1 preprocessor from `gateway/platforms/api_server_slash.py`.

Chat streaming prefers upstream's native
`/api/sessions/{session_id}/chat/stream` endpoint when it is advertised. Older
builds that only get bootstrap-provided session CRUD fall back to standard
`/v1/chat/completions` or `/v1/runs` paths.

This module retires per surface, not as one broad PR cleanup. Sessions can go
once the supported hermes-agent baseline includes PR #33134, read-only skills
should use PR #33016's `/v1/skills`, and the remaining config/memory/legacy
skill/available-model/slash-command surfaces need stable replacements or local
UX removal before the package and `.pth` hook can disappear.
"""

from __future__ import annotations

import logging
import sys

logger = logging.getLogger(__name__)

# Import and install the meta_path finder. Kept in a sub-module so this file
# stays tiny and the actual patch logic is easy to audit / disable.
from . import _patch  # noqa: E402

_patch.install_finder()
