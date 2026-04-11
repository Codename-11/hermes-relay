"""Bridge channel handler — stub for Phase 3.

TODO (Phase 3): Bridge protocol integration
  - Mirror the upstream relay protocol (hermes-android-plugin/android_relay.py)
    into the multiplexed WSS connection
  - Receive bridge.command from the agent (via the plugin) and forward to the phone
  - Receive bridge.response from the phone and return to the agent
  - Track bridge.status updates (accessibility, overlay, battery)
  - Replace the standalone bridge relay on port 8766 with this channel
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
            "channel": "bridge",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


class BridgeHandler:
    """Stub handler for the bridge channel.

    All methods return a ``not implemented`` error envelope. Real
    implementation will arrive in Phase 3 when the upstream bridge
    protocol is migrated into the multiplexed WSS connection.
    """

    async def handle(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        """Route an incoming bridge-channel envelope."""
        msg_type = envelope.get("type", "")
        msg_id = envelope.get("id")

        # TODO: Phase 3 — dispatch to real handlers
        #   bridge.command   → forward command from agent to phone
        #   bridge.response  → return phone response to waiting agent future
        #   bridge.status    → update cached device status

        logger.info("Bridge channel not implemented — received %s", msg_type)
        await ws.send_str(
            _make_envelope(
                "bridge.error",
                {
                    "message": (
                        "Bridge channel is not yet implemented. "
                        "It will be available in Phase 3."
                    ),
                },
                msg_id,
            )
        )

    async def close(self) -> None:
        """Clean up bridge state.

        TODO (Phase 3): Cancel any in-flight bridge commands, notify
        the agent plugin that the phone disconnected.
        """
