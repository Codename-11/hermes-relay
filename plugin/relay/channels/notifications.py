"""Notifications channel handler — opt-in notification triage helper.

The phone runs a ``NotificationListenerService`` (the same Android API
Wear OS, Android Auto, and Tasker use) that the user explicitly grants
via Android Settings. When granted, the service forwards each posted
notification's metadata over the existing WSS connection as a
``notifications`` channel envelope. We hold the most recent N entries
in a bounded in-memory deque so the agent can answer questions like
"what came in while I was in that meeting?" without needing to be
streaming during every notification post.

Trust model:
  * The user controls the grant via Android Settings — they can revoke
    at any time, and Android shows the running listener in the system
    permissions list.
  * The data never touches disk — purely in-memory, capped at 100
    entries, and lost on relay restart by design.
  * The server-side cache is per-relay-process, not per-session: any
    paired device with chat-channel auth can read the cache via the
    HTTP route, matching the trust model of every other relay endpoint
    that exposes user-shared data (notifications belong to the user,
    not to a specific phone).
"""

from __future__ import annotations

import collections
import logging
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)

# Cap on the in-memory cache. Picked to bound memory while still being
# generous enough to cover "all the notifications that came in over the
# last few hours" for a typical phone. Each entry is small (a few
# strings), so 100 × ~1KB worst-case is trivial.
DEFAULT_CACHE_SIZE = 100


class NotificationsChannel:
    """Holds the bounded recent-notifications cache for the relay.

    One instance per :class:`RelayServer`. Envelopes from the
    ``notifications`` channel are dispatched here via
    :meth:`handle_envelope`, and the HTTP route ``/notifications/recent``
    reads via :meth:`get_recent`.
    """

    def __init__(self, max_entries: int = DEFAULT_CACHE_SIZE) -> None:
        # ``deque`` with ``maxlen`` evicts the oldest entry on append
        # once the cap is reached — exactly the LRU-by-time behavior
        # we want for "recent notifications".
        self.recent: collections.deque[dict[str, Any]] = collections.deque(
            maxlen=max_entries
        )
        self._max_entries = max_entries

    # ── Dispatcher ───────────────────────────────────────────────────────

    async def handle(
        self, ws: web.WebSocketResponse, envelope: dict[str, Any]
    ) -> None:
        """Route an incoming notifications-channel envelope.

        Currently the only supported inbound type is
        ``notification.posted``. Anything else is ignored with a debug
        log line — we don't ack notifications back to the phone because
        the phone doesn't need confirmation that the relay cached them.
        """
        msg_type = envelope.get("type", "")
        payload = envelope.get("payload", {})

        if msg_type == "notification.posted":
            await self.handle_envelope(envelope)
        else:
            logger.debug("notifications: ignoring unknown type %r", msg_type)

    async def handle_envelope(self, envelope: dict[str, Any]) -> None:
        """Append the payload of a posted-notification envelope to the cache.

        Defensive about payload shape — anything that isn't a dict is
        dropped silently. Bad data from the phone shouldn't crash the
        whole relay.
        """
        payload = envelope.get("payload")
        if not isinstance(payload, dict):
            logger.debug(
                "notifications: dropping non-dict payload (type=%s)",
                type(payload).__name__,
            )
            return

        self.recent.append(payload)
        logger.debug(
            "notifications: cached entry from %s (cache=%d/%d)",
            payload.get("package_name", "?"),
            len(self.recent),
            self._max_entries,
        )

    # ── Reader ───────────────────────────────────────────────────────────

    def get_recent(self, limit: int) -> list[dict[str, Any]]:
        """Return up to ``limit`` most-recent entries (newest first).

        ``limit`` is clamped to ``[1, max_entries]`` so callers can't
        ask for negative values or more than the cache holds. The
        deque is iterated newest-first by reversing the slice.
        """
        if limit < 1:
            limit = 1
        if limit > self._max_entries:
            limit = self._max_entries

        # The deque is append-on-the-right; newest entries are at the
        # tail. Pull the last ``limit`` items and reverse so the
        # response is newest-first.
        snapshot = list(self.recent)
        tail = snapshot[-limit:]
        tail.reverse()
        return tail

    def clear(self) -> None:
        """Drop all cached entries. Wired for tests + future debug routes."""
        self.recent.clear()
