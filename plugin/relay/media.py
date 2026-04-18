"""Media registry — opaque-token file serving for media-producing tools.

The screenshot tool (and any future media-producing tool) POSTs to a
loopback-only register endpoint on the relay, gets back an opaque token,
and emits ``MEDIA:hermes-relay://<token>`` in chat. The phone parses that
marker and GETs the bytes from a bearer-auth'd route on the same relay.

Design notes:

* **Opaque tokens** (``secrets.token_urlsafe(16)``) — never leak filesystem
  paths over the wire.
* **Path sandboxing** — every registered path must be absolute, must
  ``os.path.realpath`` under at least one of the allowed roots, must exist,
  must be a regular file, and must fit under the size cap.
* **Allowed roots** — default to ``tempfile.gettempdir()`` plus the Hermes
  workspace (env ``HERMES_WORKSPACE`` or ``~/.hermes/workspace/``) plus any
  extra roots the operator supplies via ``RELAY_MEDIA_ALLOWED_ROOTS``
  (``os.pathsep``-separated). Additional roots passed to the constructor
  are appended.
* **TTL** — 24h default (matches scroll-back-within-a-day use case).
* **LRU cap** — 500 entries default; oldest is evicted on overflow.

All mutators take an :class:`asyncio.Lock` — aiohttp handlers run in a
single loop, but the lock is cheap insurance and also keeps the contract
obvious for any future multi-worker or background-cleanup refactor.
"""

from __future__ import annotations

import asyncio
import logging
import os
import secrets
import tempfile
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

logger = logging.getLogger("hermes_relay.media")


class MediaRegistrationError(ValueError):
    """Raised when a media registration request fails validation.

    Subclass of :class:`ValueError` so aiohttp handlers can distinguish
    "bad input" from "server bug" cleanly.
    """


@dataclass
class _MediaEntry:
    """One registered media file."""

    token: str
    path: str
    content_type: str
    size: int
    file_name: str | None
    created_at: float
    expires_at: float
    last_accessed: float = field(default_factory=time.time)

    @property
    def is_expired(self) -> bool:
        return time.time() > self.expires_at


def validate_media_path(
    path: str,
    allowed_roots: list[str] | None,
    max_size_bytes: int,
) -> tuple[str, int]:
    """Validate that ``path`` is safe to serve as media content.

    Performs the file-level safety checks: absolute path → ``realpath`` →
    exists → is a regular file → under size cap. When ``allowed_roots`` is
    non-None, *also* enforces that the resolved path lives under at least
    one of those roots (strict sandbox mode, used by
    :meth:`MediaRegistry.register` and by :func:`handle_media_by_path` when
    the operator opts into ``RELAY_MEDIA_STRICT_SANDBOX``).

    Passing ``allowed_roots=None`` skips the root check entirely. This is
    the default for LLM-emitted ``MEDIA:/abs/path`` markers served through
    ``/media/by-path``: the trust boundary is the bearer-auth'd session, and
    if the LLM can already read a file via its other tools it can already
    exfiltrate the contents via plain text — the sandbox was a
    defense-in-depth layer that cost more friction than it returned.

    Returns ``(real_path, size_bytes)`` on success.

    Raises :class:`MediaRegistrationError` with a clear message on any
    validation failure so both callers can propagate the same error shape.
    """
    if not path or not isinstance(path, str):
        raise MediaRegistrationError("missing or invalid 'path'")
    if not os.path.isabs(path):
        raise MediaRegistrationError(f"path must be absolute: {path!r}")

    real_path = os.path.realpath(path)

    if allowed_roots is not None and not _is_under_any_root(real_path, allowed_roots):
        raise MediaRegistrationError(
            f"path is not under an allowed root: {path!r}"
        )

    if not os.path.exists(real_path):
        raise MediaRegistrationError(f"path does not exist: {path!r}")

    if not os.path.isfile(real_path):
        raise MediaRegistrationError(f"path is not a regular file: {path!r}")

    try:
        size = os.path.getsize(real_path)
    except OSError as exc:
        raise MediaRegistrationError(
            f"could not stat {path!r}: {exc}"
        ) from exc

    if size > max_size_bytes:
        raise MediaRegistrationError(
            f"file too large ({size} bytes, max {max_size_bytes})"
        )

    return real_path, size


def _is_under_any_root(real_path: str, allowed_roots: list[str]) -> bool:
    """Return True if ``real_path`` lives under any of ``allowed_roots``."""
    for root in allowed_roots:
        try:
            common = os.path.commonpath([real_path, root])
        except ValueError:
            # commonpath raises ValueError on mixed drives (Windows) —
            # treat as "not under this root" and keep checking.
            continue
        if common == root:
            return True
    return False


def _default_allowed_roots() -> list[str]:
    """Build the default list of allowed roots at init time.

    Order:
      1. ``tempfile.gettempdir()``
      2. ``$HERMES_WORKSPACE`` or ``~/.hermes/workspace/`` if either exists
      3. Entries from ``RELAY_MEDIA_ALLOWED_ROOTS`` (``os.pathsep``-split)

    All paths are resolved via ``os.path.realpath`` so symlinks in the
    allowlist itself don't create surprises.
    """
    roots: list[str] = [os.path.realpath(tempfile.gettempdir())]

    workspace_env = os.environ.get("HERMES_WORKSPACE")
    if workspace_env:
        roots.append(os.path.realpath(workspace_env))
    else:
        default_ws = Path.home() / ".hermes" / "workspace"
        # Include the path whether or not it currently exists — register()
        # will fail if the file inside it doesn't exist.
        roots.append(os.path.realpath(str(default_ws)))

    extra = os.environ.get("RELAY_MEDIA_ALLOWED_ROOTS", "")
    if extra:
        for item in extra.split(os.pathsep):
            item = item.strip()
            if item:
                roots.append(os.path.realpath(item))

    # De-dupe while preserving order
    seen: set[str] = set()
    deduped: list[str] = []
    for r in roots:
        if r not in seen:
            seen.add(r)
            deduped.append(r)
    return deduped


class MediaRegistry:
    """In-memory registry of media entries keyed by opaque token.

    Access pattern is fully async — all mutations go through an
    :class:`asyncio.Lock`. Callers must ``await`` every method.
    """

    def __init__(
        self,
        max_entries: int = 500,
        ttl_seconds: int = 24 * 60 * 60,
        max_size_bytes: int = 100 * 1024 * 1024,
        allowed_roots: Iterable[str] | None = None,
        strict_sandbox: bool = False,
    ) -> None:
        self.max_entries = max_entries
        self.ttl_seconds = ttl_seconds
        self.max_size_bytes = max_size_bytes
        # When True, /media/by-path enforces the `allowed_roots` allowlist.
        # When False (default), by-path accepts any absolute readable file
        # within the size cap. See validate_media_path docstring for the
        # rationale — short version: the LLM can already exfil bytes via
        # other tools, so the sandbox was defense-in-depth that mostly
        # surfaced as false-positive friction in practice. The token path
        # (/media/register, tool-facing, loopback-only) ALWAYS enforces the
        # allowlist regardless of this flag.
        self.strict_sandbox = strict_sandbox

        base_roots = _default_allowed_roots()
        if allowed_roots is not None:
            for r in allowed_roots:
                if r:
                    resolved = os.path.realpath(r)
                    if resolved not in base_roots:
                        base_roots.append(resolved)
        self.allowed_roots: list[str] = base_roots

        self._entries: "OrderedDict[str, _MediaEntry]" = OrderedDict()
        self._lock = asyncio.Lock()

        logger.info(
            "MediaRegistry initialized (max_entries=%d, ttl=%ds, "
            "max_size=%d bytes, strict_sandbox=%s, roots=%s)",
            max_entries,
            ttl_seconds,
            max_size_bytes,
            strict_sandbox,
            self.allowed_roots,
        )

    # ── Public API ──────────────────────────────────────────────────────

    async def register(
        self,
        path: str,
        content_type: str,
        file_name: str | None = None,
    ) -> _MediaEntry:
        """Validate ``path`` and register a new media entry.

        Raises :class:`MediaRegistrationError` with a clear message on any
        validation failure (bad path, not under allowed roots, missing,
        oversized, not a regular file).
        """
        if not content_type or not isinstance(content_type, str):
            raise MediaRegistrationError("missing or invalid 'content_type'")

        real_path, size = validate_media_path(
            path, self.allowed_roots, self.max_size_bytes
        )

        token = secrets.token_urlsafe(16)
        now = time.time()
        entry = _MediaEntry(
            token=token,
            path=real_path,
            content_type=content_type,
            size=size,
            file_name=file_name,
            created_at=now,
            expires_at=now + self.ttl_seconds,
            last_accessed=now,
        )

        async with self._lock:
            self._cleanup_locked()
            self._entries[token] = entry
            # Evict oldest while over cap
            while len(self._entries) > self.max_entries:
                evicted_token, evicted = self._entries.popitem(last=False)
                logger.info(
                    "MediaRegistry LRU eviction: token=%s... path=%s",
                    evicted_token[:8],
                    evicted.path,
                )

        logger.info(
            "Registered media token=%s... path=%s size=%d type=%s",
            token[:8],
            real_path,
            size,
            content_type,
        )
        return entry

    async def get(self, token: str) -> _MediaEntry | None:
        """Look up a token. Returns None on miss or expiry.

        Updates ``last_accessed`` and moves the entry to the end of the
        LRU order on hit.
        """
        if not token:
            return None
        async with self._lock:
            self._cleanup_locked()
            entry = self._entries.get(token)
            if entry is None:
                return None
            if entry.is_expired:
                del self._entries[token]
                logger.info(
                    "MediaRegistry expired on read: token=%s...", token[:8]
                )
                return None
            entry.last_accessed = time.time()
            self._entries.move_to_end(token)
            return entry

    async def cleanup(self) -> int:
        """Public wrapper around ``_cleanup_locked``. Returns pruned count."""
        async with self._lock:
            return self._cleanup_locked()

    async def list_all(
        self, *, include_expired: bool = False
    ) -> list[dict]:
        """Return a sanitized snapshot of all registered entries.

        Used by the dashboard's media-inspector tab. Each returned dict
        contains the token, basename-only ``file_name``, ``content_type``,
        ``size``, ``created_at``, ``expires_at``, ``last_accessed``, and the
        derived ``is_expired`` flag. The absolute filesystem ``path`` is
        **never** included — the basename is derived via
        :func:`os.path.basename` so no directory layout leaks over the wire.

        Parameters
        ----------
        include_expired:
            When ``False`` (default), entries whose TTL has lapsed are
            filtered out. When ``True``, they are included with
            ``is_expired=True`` so the UI can visualize recently-expired
            tokens before the next cleanup sweep removes them.

        Returns
        -------
        list[dict]
            Snapshot sorted newest-first by ``created_at`` (descending).
        """
        async with self._lock:
            snapshot: list[dict] = []
            for entry in self._entries.values():
                is_expired = entry.is_expired
                if is_expired and not include_expired:
                    continue
                # Prefer the caller-supplied file_name when present;
                # otherwise derive a basename from the absolute path.
                # Either way, run through os.path.basename so a value like
                # "/abs/path/foo.png" in file_name collapses to "foo.png".
                raw_name = entry.file_name or entry.path
                file_name = os.path.basename(raw_name)
                snapshot.append(
                    {
                        "token": entry.token,
                        "file_name": file_name,
                        "content_type": entry.content_type,
                        "size": entry.size,
                        "created_at": entry.created_at,
                        "expires_at": entry.expires_at,
                        "last_accessed": entry.last_accessed,
                        "is_expired": is_expired,
                    }
                )
            snapshot.sort(key=lambda item: item["created_at"], reverse=True)
            return snapshot

    async def size(self) -> int:
        """Return the current number of registered entries."""
        async with self._lock:
            return len(self._entries)

    # ── Internals ───────────────────────────────────────────────────────

    def _cleanup_locked(self) -> int:
        """Prune expired entries. Caller must hold ``self._lock``."""
        expired = [k for k, v in self._entries.items() if v.is_expired]
        for k in expired:
            del self._entries[k]
        if expired:
            logger.debug("MediaRegistry cleaned up %d expired entries", len(expired))
        return len(expired)
