"""Terminal channel handler — stub for Phase 2.

TODO (Phase 2): PTY/tmux integration
  - Use asyncio.create_subprocess_exec with PTY for non-blocking I/O
  - Use libtmux for named session management (create, attach, detach)
  - Stream PTY output → WebSocket
  - WebSocket input → PTY stdin
  - Support terminal resize (SIGWINCH)
  - Batch terminal output at ~16ms frames to avoid flooding the WebSocket
"""

from __future__ import annotations

import json
import logging
import uuid
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


def _make_envelope(msg_type: str, payload: dict[str, Any], msg_id: str | None = None) -> str:
    return json.dumps(
        {
            "channel": "terminal",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


class TerminalHandler:
    """Stub handler for the terminal channel.

    All methods return a ``not implemented`` error envelope. Real
    implementation will arrive in Phase 2 with PTY/tmux integration.
    """

    async def handle(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        """Route an incoming terminal-channel envelope."""
        msg_type = envelope.get("type", "")
        msg_id = envelope.get("id")

        # TODO: Phase 2 — dispatch to real handlers
        #   terminal.attach  → find or create tmux session, open PTY
        #   terminal.input   → write to PTY stdin
        #   terminal.resize  → send SIGWINCH to PTY
        #   terminal.detach  → close PTY, detach from tmux session

        logger.info("Terminal channel not implemented — received %s", msg_type)
        await ws.send_str(
            _make_envelope(
                "terminal.error",
                {
                    "message": (
                        "Terminal channel is not yet implemented. "
                        "It will be available in Phase 2."
                    ),
                },
                msg_id,
            )
        )

    async def close(self) -> None:
        """Clean up any open PTY sessions.

        TODO (Phase 2): Close all PTY file descriptors and detach tmux sessions.
        """
