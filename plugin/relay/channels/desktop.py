"""Desktop channel handler — workspace awareness + active-editor hints.

The desktop CLI (`@hermes-relay/cli`) advertises its local workspace to
the relay on connect, and optionally polls an active-editor hint every
few seconds. Both arrive over the ``desktop`` channel:

  * ``desktop.workspace``      — client → server, once per auth.ok
  * ``desktop.active_editor``  — client → server, every ~5s (deduped by
    the client; only sent when the hint's value changed)

Storage model is intentionally trivial: an in-memory dict keyed by
WebSocket identity. No persistence, no db writes — if the relay
restarts, the next client auth re-advertises its workspace. This keeps
the feature light enough to land without a hermes-agent PR: the server
just stashes what the client sends and exposes it for future plugin
hooks (deferred to alpha.7).

The dispatcher deliberately swallows unknown envelope types with a
debug log rather than an error — forward-compat with future alpha.6+
client fields (e.g. shell-history-hash) that the relay hasn't learned
yet.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


class DesktopSession:
    """Per-session scratch space holding the latest workspace + editor
    snapshots advertised by a desktop CLI client. One instance per
    connected WebSocket; the outer :class:`DesktopChannel` owns the
    dict that maps ws → DesktopSession.

    All fields are best-effort — the client may advertise a workspace
    with only ``cwd`` and ``hostname`` if git isn't installed, and may
    never send an active-editor hint at all. Consumers should null-check
    liberally.
    """

    def __init__(self) -> None:
        # Latest ``desktop.workspace`` payload as-sent. Opaque dict — we
        # don't parse fields here because the wire schema is owned by
        # the client (versioned as ``version: 1``); future versions
        # round-trip harmlessly.
        self.workspace_context: dict[str, Any] | None = None
        self.workspace_received_at: float | None = None

        # Latest ``desktop.active_editor`` payload. Gets overwritten in
        # place on each poll — we don't keep history.
        self.active_editor: dict[str, Any] | None = None
        self.active_editor_received_at: float | None = None


class DesktopChannel:
    """Routes ``desktop`` envelopes from CLI clients into per-session
    context state.

    Phase A.5 / alpha.6 scope: stash only. The stashed payloads will be
    consumed by a hermes-agent plugin hook in a future release to
    inject "the user is in <repo> on branch <branch>" into the system
    prompt. For now the data exists for introspection (e.g. a future
    ``GET /desktop/session/<id>/context`` endpoint) and does not feed
    back into the agent loop.
    """

    def __init__(self) -> None:
        # ws → DesktopSession. Cleared per-ws in :meth:`detach_ws` so
        # disconnected clients don't leak session structs.
        self._sessions: dict[web.WebSocketResponse, DesktopSession] = {}

    # ── Envelope dispatch ────────────────────────────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Route an incoming desktop-channel envelope.

        Known types:
          * ``workspace``     — capture WorkspaceContext snapshot
          * ``active_editor`` — capture ActiveEditorHint snapshot

        Unknown types log-and-drop for forward compat.
        """
        msg_type = envelope.get("type", "")
        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            logger.debug(
                "desktop: dropping non-dict payload (type=%s value_type=%s)",
                msg_type,
                type(payload).__name__,
            )
            return

        session = self._sessions.get(ws)
        if session is None:
            session = DesktopSession()
            self._sessions[ws] = session

        if msg_type == "workspace":
            session.workspace_context = dict(payload)
            session.workspace_received_at = time.time()
            logger.info(
                "desktop: workspace advertised repo=%s branch=%s host=%s",
                payload.get("repo_name", "(none)"),
                payload.get("git_branch", "(none)"),
                payload.get("hostname", "(none)"),
            )
        elif msg_type == "active_editor":
            session.active_editor = dict(payload)
            session.active_editor_received_at = time.time()
            logger.debug(
                "desktop: active_editor source=%s editor=%s",
                payload.get("source", "?"),
                payload.get("editor", "(none)"),
            )
        else:
            logger.debug("desktop: ignoring unknown type %r", msg_type)

    # ── Accessors ────────────────────────────────────────────────────────

    def session_for(self, ws: web.WebSocketResponse) -> DesktopSession | None:
        """Return the DesktopSession for ``ws`` if any.

        Exposed so future plugin hooks / HTTP introspection routes can
        read ``session.workspace_context`` / ``session.active_editor``
        without importing the internal dict.
        """
        return self._sessions.get(ws)

    def all_sessions(self) -> list[DesktopSession]:
        """Snapshot every known session. Useful for a future
        ``/desktop/sessions`` debug route that lists what every
        connected client has advertised.
        """
        return list(self._sessions.values())

    # ── Lifecycle ───────────────────────────────────────────────────────

    async def detach_ws(self, ws: web.WebSocketResponse) -> None:
        """Drop per-ws state when the client disconnects. Called from
        the main ``_on_disconnect`` path in ``server.py``.
        """
        session = self._sessions.pop(ws, None)
        if session is not None and session.workspace_context is not None:
            logger.debug(
                "desktop: session detached (had workspace from %s)",
                session.workspace_context.get("hostname", "?"),
            )

    async def close(self) -> None:
        """Server shutdown — drop everything."""
        self._sessions.clear()
