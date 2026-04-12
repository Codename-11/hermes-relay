"""hermes_relay_bootstrap — runtime patch for vanilla upstream hermes-agent.

This package is loaded at Python interpreter startup via a `.pth` file in the
hermes-agent venv's site-packages. It installs a `sys.meta_path` import hook
that waits for `aiohttp.web` to be imported, then replaces `web.Application`
with a thin subclass that detects when hermes-agent's `APIServerAdapter`
attaches itself to a fresh app and injects extra `/api/sessions/*`,
`/api/memory`, `/api/skills`, `/api/config`, and `/api/available-models`
routes.

The point: a vanilla upstream hermes-agent install with no custom server-side
patches still serves the management endpoints the Hermes-Relay Android app
expects, as long as the hermes-relay plugin is installed in the same venv.
Chat streaming continues to use upstream's standard `/v1/runs` endpoint, which
already emits structured tool events — that's why we don't need to inject any
chat handlers.

This module is removed in its entirety once upstream PR
https://github.com/NousResearch/hermes-agent/pull/8556 lands and reaches a
released hermes-agent version. The bootstrap feature-detects on route paths
and silently no-ops when the upstream-merged or fork-built endpoints are
already present, so it stays harmless during the rollout window.
"""

from __future__ import annotations

import logging
import sys

logger = logging.getLogger(__name__)

# Import and install the meta_path finder. Kept in a sub-module so this file
# stays tiny and the actual patch logic is easy to audit / disable.
from . import _patch  # noqa: E402

_patch.install_finder()
