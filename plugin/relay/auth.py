"""Authentication — pairing codes, session tokens, and rate limiting."""

from __future__ import annotations

import logging
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from .config import PAIRING_ALPHABET, PAIRING_CODE_LENGTH

logger = logging.getLogger(__name__)

# ── Rate-limit constants ─────────────────────────────────────────────────────

_MAX_ATTEMPTS = 5
_WINDOW_SECONDS = 60
_BLOCK_SECONDS = 300  # 5 minutes
_CLEANUP_INTERVAL = 120

# ── Data models ──────────────────────────────────────────────────────────────


@dataclass
class Session:
    """Represents an authenticated device session."""

    token: str
    device_name: str
    device_id: str
    created_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    expires_at: float = 0.0

    def __post_init__(self) -> None:
        if self.expires_at == 0.0:
            # Default: 30-day expiry
            self.expires_at = self.created_at + 30 * 24 * 3600

    @property
    def is_expired(self) -> bool:
        return time.time() > self.expires_at


@dataclass
class _PairingEntry:
    """An active pairing code waiting for a device to claim it."""

    code: str
    created_at: float = field(default_factory=time.time)
    # Pairing codes expire after 10 minutes
    expires_at: float = 0.0

    def __post_init__(self) -> None:
        if self.expires_at == 0.0:
            self.expires_at = self.created_at + 600

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

    def register_code(self, code: str) -> bool:
        """Register an externally-provided pairing code. Returns True on success."""
        normalized = code.upper().strip()
        if len(normalized) != PAIRING_CODE_LENGTH:
            return False
        if not all(c in PAIRING_ALPHABET for c in normalized):
            return False
        self._codes[normalized] = _PairingEntry(code=normalized)
        logger.info("Registered pairing code %s", normalized)
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

    def consume_code(self, code: str) -> bool:
        """Validate and consume a pairing code (one-time use). Returns True if valid."""
        self._cleanup()
        normalized = code.upper().strip()
        entry = self._codes.pop(normalized, None)
        if entry is None:
            return False
        if entry.is_expired:
            return False
        logger.info("Pairing code %s consumed", normalized)
        return True

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

    def create_session(self, device_name: str, device_id: str) -> Session:
        """Create a new session for an authenticated device."""
        token = str(uuid.uuid4())
        session = Session(
            token=token,
            device_name=device_name,
            device_id=device_id,
        )
        self._sessions[token] = session
        logger.info(
            "Created session for device %s (%s), expires %s",
            device_name,
            device_id,
            time.strftime("%Y-%m-%d %H:%M", time.localtime(session.expires_at)),
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
        session.last_seen = time.time()
        return session

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
