"""Authentication — pairing codes, session tokens, and rate limiting.

v0.3.0 adds:
  * User-chosen session TTL (including "never expire" via ``math.inf``).
  * Per-channel grants ({"chat", "terminal", "bridge"} → epoch seconds) so
    blast-radius-heavy channels can get shorter expiries than plain chat.
  * ``transport_hint`` field recording the scheme the phone paired over
    (``"wss"`` / ``"ws"`` / ``"unknown"``) — purely informational, displayed
    on the phone's Paired Devices screen.
  * ``PairingManager.register_code`` now accepts optional TTL + grants +
    transport hint metadata, which ``consume_code`` returns so the caller
    can thread them through to ``SessionManager.create_session``.
  * ``RateLimiter.clear_all_blocks`` so a successful loopback pair can wipe
    any stale block state (a phone re-pairing after a relay restart should
    not be penalized for auth failures against the prior token).
"""

from __future__ import annotations

import logging
import math
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from .config import PAIRING_ALPHABET, PAIRING_CODE_LENGTH

logger = logging.getLogger("hermes_relay.auth")

# ── Rate-limit constants ─────────────────────────────────────────────────────

_MAX_ATTEMPTS = 5
_WINDOW_SECONDS = 60
_BLOCK_SECONDS = 300  # 5 minutes
_CLEANUP_INTERVAL = 120

# ── TTL / grant defaults ─────────────────────────────────────────────────────

# Default session TTL when the caller doesn't specify one.
DEFAULT_TTL_SECONDS: float = 30 * 24 * 3600  # 30 days

# Caps for per-channel default grants when the caller doesn't specify grants.
# If the overall TTL is shorter than a cap, the shorter value wins — grants
# never outlive the session itself.
DEFAULT_TERMINAL_CAP: float = 30 * 24 * 3600  # 30 days
DEFAULT_BRIDGE_CAP: float = 7 * 24 * 3600  # 7 days

# Pairing codes still expire after 10 minutes regardless of session TTL.
_PAIRING_CODE_TTL = 600.0


# ── Data models ──────────────────────────────────────────────────────────────


def _is_never(value: float) -> bool:
    """True if ``value`` represents a never-expiring timestamp."""
    return math.isinf(value) and value > 0


def _default_grants(ttl_seconds: float, now: float) -> dict[str, float]:
    """Compute default per-channel grants given an overall session TTL.

    ``ttl_seconds == 0`` means "never expire" and is represented as
    ``math.inf``. All channels inherit the never in that case.
    """
    if ttl_seconds == 0:
        return {
            "chat": math.inf,
            "terminal": math.inf,
            "bridge": math.inf,
        }

    chat_exp = now + ttl_seconds
    terminal_exp = now + min(ttl_seconds, DEFAULT_TERMINAL_CAP)
    bridge_exp = now + min(ttl_seconds, DEFAULT_BRIDGE_CAP)
    return {
        "chat": chat_exp,
        "terminal": terminal_exp,
        "bridge": bridge_exp,
    }


def _materialize_grants(
    grants: dict[str, float] | None,
    ttl_seconds: float,
    now: float,
) -> dict[str, float]:
    """Turn caller-supplied grants (channel → seconds-from-now) into absolute
    expiry timestamps, falling back to defaults when ``grants`` is None.

    A grant value of ``0`` or ``math.inf`` means "never expire" for that
    channel. Explicit grants are still clamped to the overall session
    expiry — a channel cannot outlive the session.
    """
    defaults = _default_grants(ttl_seconds, now)
    if grants is None:
        return defaults

    session_expiry = math.inf if ttl_seconds == 0 else now + ttl_seconds
    out: dict[str, float] = dict(defaults)
    for channel, value in grants.items():
        if channel not in out:
            # Unknown channel — accept but don't enforce.
            pass
        if value == 0 or (isinstance(value, float) and math.isinf(value)):
            candidate = math.inf
        else:
            candidate = now + float(value)
        # Clamp to session lifetime.
        if _is_never(session_expiry):
            out[channel] = candidate
        elif _is_never(candidate):
            out[channel] = session_expiry
        else:
            out[channel] = min(candidate, session_expiry)
    return out


@dataclass
class Session:
    """Represents an authenticated device session.

    ``expires_at == math.inf`` means "never expires"; the ``is_expired``
    property returns False in that case. Per-channel grants live in
    ``grants`` — keys include ``"chat"``, ``"terminal"``, ``"bridge"``. The
    relay itself does not currently enforce grants on incoming traffic
    (chat goes direct to the API server, not through the relay), but the
    phone reads them for display and future Phase 2/3 gating.
    """

    token: str
    device_name: str
    device_id: str
    created_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    expires_at: float = 0.0
    grants: dict[str, float] = field(default_factory=dict)
    transport_hint: str = "unknown"
    first_seen: float = 0.0

    def __post_init__(self) -> None:
        if self.expires_at == 0.0:
            # Legacy default: 30-day expiry measured from creation.
            self.expires_at = self.created_at + DEFAULT_TTL_SECONDS
        if self.first_seen == 0.0:
            self.first_seen = self.created_at
        if not self.grants:
            # Synthesize defaults from the realized expires_at so older
            # code paths that constructed Session directly still behave.
            if _is_never(self.expires_at):
                ttl = 0.0
            else:
                ttl = max(0.0, self.expires_at - self.created_at)
            self.grants = _default_grants(ttl, self.created_at)

    @property
    def is_expired(self) -> bool:
        if _is_never(self.expires_at):
            return False
        return time.time() > self.expires_at

    def channel_expires_at(self, channel: str) -> float | None:
        """Return the expiry timestamp for ``channel``, or None if not granted."""
        return self.grants.get(channel)

    def channel_is_expired(self, channel: str) -> bool:
        """True if the specified channel grant has expired. Unknown channels
        are reported as expired so callers don't accidentally allow traffic
        on a channel they never granted."""
        exp = self.grants.get(channel)
        if exp is None:
            return True
        if _is_never(exp):
            return False
        return time.time() > exp


@dataclass
class PairingMetadata:
    """Optional TTL + grants + transport hint attached to a pairing code.

    Populated by :meth:`PairingManager.register_code` and returned by
    :meth:`PairingManager.consume_code` so the caller can thread the
    operator's pairing choices through to the new session.
    """

    ttl_seconds: float | None = None
    grants: dict[str, float] | None = None
    transport_hint: str | None = None


@dataclass
class _PairingEntry:
    """An active pairing code waiting for a device to claim it."""

    code: str
    created_at: float = field(default_factory=time.time)
    expires_at: float = 0.0
    metadata: PairingMetadata = field(default_factory=PairingMetadata)

    def __post_init__(self) -> None:
        if self.expires_at == 0.0:
            self.expires_at = self.created_at + _PAIRING_CODE_TTL

    @property
    def is_expired(self) -> bool:
        return time.time() > self.expires_at


# ── Pairing manager ─────────────────────────────────────────────────────────


class PairingManager:
    """Manages short-lived pairing codes used for initial device authentication.

    Codes are stored in-memory and expire after 10 minutes.
    """

    def __init__(self) -> None:
        self._codes: dict[str, _PairingEntry] = {}

    def generate_code(self) -> str:
        """Generate and register a new random pairing code."""
        code = "".join(
            secrets.choice(PAIRING_ALPHABET) for _ in range(PAIRING_CODE_LENGTH)
        )
        entry = _PairingEntry(code=code)
        self._codes[code.upper()] = entry
        logger.info("Generated pairing code %s (expires in 10m)", code)
        return code

    def register_code(
        self,
        code: str,
        ttl_seconds: float | None = None,
        grants: dict[str, float] | None = None,
        transport_hint: str | None = None,
    ) -> bool:
        """Register an externally-provided pairing code with optional metadata.

        The metadata is attached to the pairing entry and returned from
        :meth:`consume_code` so the WS auth handler can apply the caller's
        chosen TTL / grants to the freshly-minted session.

        Returns True on success, False if the code fails format validation.
        """
        normalized = code.upper().strip()
        if len(normalized) != PAIRING_CODE_LENGTH:
            return False
        if not all(c in PAIRING_ALPHABET for c in normalized):
            return False
        meta = PairingMetadata(
            ttl_seconds=ttl_seconds,
            grants=dict(grants) if grants is not None else None,
            transport_hint=transport_hint,
        )
        self._codes[normalized] = _PairingEntry(code=normalized, metadata=meta)
        logger.info(
            "Registered pairing code %s (ttl=%s, grants=%s, transport=%s)",
            normalized,
            "never" if ttl_seconds == 0 else ttl_seconds,
            grants,
            transport_hint,
        )
        return True

    def validate_code(self, code: str) -> bool:
        """Check whether a pairing code is currently valid."""
        self._cleanup()
        normalized = code.upper().strip()
        entry = self._codes.get(normalized)
        if entry is None:
            return False
        if entry.is_expired:
            del self._codes[normalized]
            return False
        return True

    def consume_code(self, code: str) -> PairingMetadata | None:
        """Validate and consume a pairing code (one-time use).

        Returns the :class:`PairingMetadata` attached to the code on
        success (which may contain None fields if no metadata was
        supplied at register time), or ``None`` if the code is invalid
        or expired.
        """
        self._cleanup()
        normalized = code.upper().strip()
        entry = self._codes.pop(normalized, None)
        if entry is None:
            return None
        if entry.is_expired:
            return None
        logger.info("Pairing code %s consumed", normalized)
        return entry.metadata

    def _cleanup(self) -> None:
        """Remove expired codes."""
        expired = [k for k, v in self._codes.items() if v.is_expired]
        for k in expired:
            del self._codes[k]


# ── Session manager ──────────────────────────────────────────────────────────


class SessionManager:
    """Manages long-lived session tokens for authenticated devices."""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def create_session(
        self,
        device_name: str,
        device_id: str,
        ttl_seconds: float | None = None,
        grants: dict[str, float] | None = None,
        transport_hint: str = "unknown",
    ) -> Session:
        """Create a new session for an authenticated device.

        Parameters
        ----------
        ttl_seconds:
            Overall session TTL in seconds. ``0`` means "never expire"
            (stored internally as ``math.inf``). ``None`` uses
            :data:`DEFAULT_TTL_SECONDS`.
        grants:
            Optional mapping from channel name to seconds-from-now. Any
            channel missing from this dict gets the default for its cap.
            Values of ``0`` or ``math.inf`` mean "never" for that channel.
            Grants are clamped to the session's overall lifetime.
        transport_hint:
            Recorded for the Paired Devices UI. Pass ``"wss"``, ``"ws"``,
            or ``"unknown"``.
        """
        if ttl_seconds is None:
            ttl_seconds = DEFAULT_TTL_SECONDS

        now = time.time()
        if ttl_seconds == 0:
            expires_at: float = math.inf
        else:
            expires_at = now + float(ttl_seconds)

        resolved_grants = _materialize_grants(grants, float(ttl_seconds), now)

        token = str(uuid.uuid4())
        session = Session(
            token=token,
            device_name=device_name,
            device_id=device_id,
            created_at=now,
            last_seen=now,
            expires_at=expires_at,
            grants=resolved_grants,
            transport_hint=transport_hint,
            first_seen=now,
        )
        self._sessions[token] = session
        logger.info(
            "Created session for device %s (%s), expires %s, transport=%s",
            device_name,
            device_id,
            "never" if _is_never(expires_at) else time.strftime(
                "%Y-%m-%d %H:%M", time.localtime(expires_at)
            ),
            transport_hint,
        )
        return session

    def get_session(self, token: str) -> Session | None:
        """Look up a session by token. Returns None if not found or expired."""
        self._cleanup()
        session = self._sessions.get(token)
        if session is None:
            return None
        if session.is_expired:
            del self._sessions[token]
            logger.info("Session %s expired", token[:8])
            return None
        # Defensive: synthesize default grants for sessions created by an
        # older code path that predated the grants refactor. In-memory
        # state is wiped on every restart so in practice this only matters
        # if callers hand-build Session objects.
        if not session.grants:
            if _is_never(session.expires_at):
                ttl = 0.0
            else:
                ttl = max(0.0, session.expires_at - session.created_at)
            session.grants = _default_grants(ttl, session.created_at)
        session.last_seen = time.time()
        return session

    def list_sessions(self) -> list[Session]:
        """Return all non-expired sessions."""
        self._cleanup()
        return list(self._sessions.values())

    def find_by_prefix(self, token_prefix: str) -> list[Session]:
        """Return sessions whose token begins with ``token_prefix`` (case-sensitive)."""
        self._cleanup()
        return [s for s in self._sessions.values() if s.token.startswith(token_prefix)]

    def validate_token(self, token: str) -> bool:
        """Check whether a session token is currently valid."""
        return self.get_session(token) is not None

    def revoke_session(self, token: str) -> bool:
        """Revoke a session. Returns True if it existed."""
        session = self._sessions.pop(token, None)
        if session is not None:
            logger.info("Revoked session for %s", session.device_name)
            return True
        return False

    def update_session(
        self,
        token: str,
        ttl_seconds: int | None = None,
        grants: dict[str, float] | None = None,
    ) -> Session | None:
        """Update a session's TTL and/or grants in place.

        * ``ttl_seconds`` (optional): new session lifetime. ``0`` means
          "never expire". When provided, ``expires_at`` is recalculated
          from ``now + ttl_seconds`` (i.e. the clock restarts — "extend
          by 30 days" = "30 more days starting now", not "add 30 days
          to the existing expiry"). When omitted, ``expires_at`` is
          unchanged.
        * ``grants`` (optional): per-channel seconds-from-now values,
          same shape as ``create_session``. When provided, grants are
          re-materialized (clamped to the current session lifetime —
          whether that's the new TTL or the existing one). When omitted,
          existing grants are **re-clamped** to the new session lifetime
          if ``ttl_seconds`` was provided (shorter TTL may clip a grant
          that was previously further out; longer TTL leaves grants
          unchanged because they were already ≤ the old session expiry).

        Returns the updated :class:`Session`, or ``None`` if ``token``
        is unknown (or expired — you can't renew an already-expired
        session, re-pair instead).

        At least one of ``ttl_seconds`` / ``grants`` must be provided;
        the caller (aiohttp handler) is responsible for rejecting
        no-op calls before dispatch.
        """
        self._cleanup()
        session = self._sessions.get(token)
        if session is None:
            return None
        if session.is_expired:
            # Don't silently resurrect an expired session; caller should
            # re-pair to get a fresh token.
            return None

        now = time.time()

        # 1. Resolve the new session expiry (if TTL changed) and update
        #    last_seen so the change is visible in list_sessions.
        if ttl_seconds is not None:
            if ttl_seconds == 0:
                session.expires_at = math.inf
            else:
                session.expires_at = now + float(ttl_seconds)
        session.last_seen = now

        # 2. Re-materialize grants. We need the session's CURRENT
        #    lifetime as the clamp target, which is either the newly-set
        #    expires_at or the pre-existing one.
        #
        #    _materialize_grants wants ttl_seconds as a float (0 = never),
        #    so reconstruct that from expires_at.
        if math.isinf(session.expires_at):
            effective_ttl = 0.0
        else:
            effective_ttl = max(session.expires_at - now, 0.0)

        if grants is not None:
            # Caller provided fresh per-channel values; re-materialize
            # from scratch (uses defaults for channels the caller didn't
            # pass, same as create_session).
            session.grants = _materialize_grants(grants, effective_ttl, now)
        else:
            # Caller only changed TTL. Regenerate grants from defaults
            # based on the new session lifetime — this handles both
            # shorter (all grants correctly clipped) and longer (grants
            # stretch to the new defaults) cleanly. Preserving old
            # absolute-time grants across an extend would leave them
            # where they originally were relative to the old "now",
            # which is usually not what the user means by "Extend".
            #
            # If the user wants to preserve custom grants across an
            # extend, they should pass them explicitly.
            session.grants = _default_grants(effective_ttl, now)

        logger.info(
            "Updated session for %s: expires_at=%s, grants=%s",
            session.device_name,
            "never" if math.isinf(session.expires_at) else session.expires_at,
            session.grants,
        )
        return session

    def active_count(self) -> int:
        """Return the number of non-expired sessions."""
        self._cleanup()
        return len(self._sessions)

    def _cleanup(self) -> None:
        """Remove expired sessions."""
        expired = [k for k, v in self._sessions.items() if v.is_expired]
        for k in expired:
            del self._sessions[k]


# ── Rate limiter ─────────────────────────────────────────────────────────────


class RateLimiter:
    """IP-based rate limiter for authentication attempts.

    Tracks failed attempts per IP within a sliding window. After
    ``max_attempts`` failures within ``window_seconds``, the IP is
    blocked for ``block_seconds``.
    """

    def __init__(
        self,
        max_attempts: int = _MAX_ATTEMPTS,
        window_seconds: float = _WINDOW_SECONDS,
        block_seconds: float = _BLOCK_SECONDS,
    ) -> None:
        self.max_attempts = max_attempts
        self.window_seconds = window_seconds
        self.block_seconds = block_seconds

        self._failures: dict[str, list[float]] = {}
        self._blocked: dict[str, float] = {}
        self._last_cleanup: float = 0.0

    def is_blocked(self, ip: str) -> bool:
        """Return True if the IP is currently blocked."""
        self._maybe_cleanup()
        now = time.monotonic()
        until = self._blocked.get(ip)
        if until is not None:
            if now < until:
                return True
            del self._blocked[ip]
        return False

    def record_failure(self, ip: str) -> None:
        """Record a failed auth attempt. May trigger a block."""
        now = time.monotonic()
        timestamps = self._failures.setdefault(ip, [])
        timestamps.append(now)

        # Prune outside window
        cutoff = now - self.window_seconds
        self._failures[ip] = [t for t in timestamps if t > cutoff]

        if len(self._failures[ip]) >= self.max_attempts:
            self._blocked[ip] = now + self.block_seconds
            self._failures.pop(ip, None)
            logger.warning(
                "IP %s blocked for %ds after %d failed auth attempts",
                ip,
                self.block_seconds,
                self.max_attempts,
            )

    def record_success(self, ip: str) -> None:
        """Clear failure history for an IP after successful auth."""
        self._failures.pop(ip, None)

    def clear_all_blocks(self) -> None:
        """Wipe all block state and pending failure counts across every IP.

        Called from the loopback-only ``/pairing/register`` route: the
        operator is explicitly re-pairing, so any stale rate-limit state
        from a prior session (e.g. a phone that tripped the block against
        the now-invalid token) is noise. This only runs for requests
        originating from localhost, so there's no risk of a remote peer
        clearing their own block.
        """
        n_blocks = len(self._blocked)
        n_failures = len(self._failures)
        self._blocked.clear()
        self._failures.clear()
        if n_blocks or n_failures:
            logger.info(
                "Cleared rate-limit state: %d blocks, %d pending failure counts",
                n_blocks,
                n_failures,
            )

    def _maybe_cleanup(self) -> None:
        now = time.monotonic()
        if now - self._last_cleanup < _CLEANUP_INTERVAL:
            return
        self._last_cleanup = now

        # Expired blocks
        expired = [ip for ip, until in self._blocked.items() if now >= until]
        for ip in expired:
            del self._blocked[ip]

        # Stale failure windows
        cutoff = now - self.window_seconds
        stale: list[str] = []
        for ip, timestamps in self._failures.items():
            self._failures[ip] = [t for t in timestamps if t > cutoff]
            if not self._failures[ip]:
                stale.append(ip)
        for ip in stale:
            del self._failures[ip]


# Re-export a few symbols for callers that want to introspect the model.
__all__ = [
    "DEFAULT_TTL_SECONDS",
    "DEFAULT_TERMINAL_CAP",
    "DEFAULT_BRIDGE_CAP",
    "PairingManager",
    "PairingMetadata",
    "RateLimiter",
    "Session",
    "SessionManager",
]
