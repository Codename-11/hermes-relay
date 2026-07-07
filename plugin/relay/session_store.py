"""Read-only access to the gateway session store, to expose the per-session
``chat_id`` the upstream ``/api/sessions`` response omits.

Background: a phone **Thread** is a ``source=phone`` gateway session keyed by a
``chat_id`` (the platform conversation id). The gateway persists ``chat_id`` /
``session_key`` in its SQLite session store, but ``/api/sessions`` returns only
the opaque timestamp ``id`` + ``source`` — so the Android client can't map a
session to its ``chat_id`` to route a reply into the right Thread.

The relay (our plugin, co-hosted with the gateway, same user) reads the store
read-only and surfaces ``session_id ↔ chat_id`` via ``GET /phone/threads``,
filling the gap without forking the gateway or touching the standard path. The
client prefers this; if upstream later adds ``chat_id`` to ``/api/sessions`` the
route becomes redundant. ``mode=ro`` reads are safe alongside the running
gateway (its store is WAL).
"""

from __future__ import annotations

import glob
import logging
import os
import sqlite3
from typing import Any

logger = logging.getLogger(__name__)


def _hermes_home(explicit: str | None = None) -> str:
    return explicit or os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes")


def _session_db_paths(home: str) -> list[str]:
    """The gateway's session DBs: the root launch DB + each profile DB."""
    paths: list[str] = []
    root = os.path.join(home, "state.db")
    if os.path.isfile(root):
        paths.append(root)
    for db in sorted(glob.glob(os.path.join(home, "profiles", "*", "state.db"))):
        if os.path.isfile(db):
            paths.append(db)
    return paths


def _read_phone_sessions(db_path: str) -> list[dict[str, Any]]:
    """Read ``source=phone`` rows from one session DB (read-only, best-effort)."""
    out: list[dict[str, Any]] = []
    try:
        con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    except Exception as exc:  # pragma: no cover - unreadable db
        logger.debug("phone-threads: cannot open %s: %s", db_path, exc)
        return out
    try:
        cur = con.cursor()
        tables = {
            r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        if "sessions" not in tables:
            return out
        cols = [r[1] for r in cur.execute("PRAGMA table_info(sessions)")]
        # Older gateway schemas predate chat_id — nothing to expose there.
        if not {"id", "source", "chat_id"}.issubset(cols):
            return out
        want = ["id", "chat_id"]
        if "title" in cols:
            want.append("title")
        idx = {c: i for i, c in enumerate(want)}
        rows = cur.execute(
            f"SELECT {', '.join(want)} FROM sessions WHERE source = 'phone'"
        ).fetchall()
        for r in rows:
            chat_id = r[idx["chat_id"]]
            if not chat_id:
                continue
            out.append(
                {
                    "session_id": r[idx["id"]],
                    "chat_id": chat_id,
                    "title": r[idx["title"]] if "title" in idx else None,
                }
            )
    except Exception as exc:  # pragma: no cover - schema drift / locked db
        logger.debug("phone-threads: read failed for %s: %s", db_path, exc)
    finally:
        con.close()
    return out


def read_phone_threads(hermes_home: str | None = None) -> list[dict[str, Any]]:
    """Return ``[{session_id, chat_id, title}]`` for every ``source=phone``
    session across the gateway's session DBs (root + per-profile), deduped by
    ``session_id``. Best-effort + read-only — returns ``[]`` on any error."""
    seen: set[str] = set()
    threads: list[dict[str, Any]] = []
    for db in _session_db_paths(_hermes_home(hermes_home)):
        for row in _read_phone_sessions(db):
            sid = row["session_id"]
            if not sid or sid in seen:
                continue
            seen.add(sid)
            threads.append(row)
    return threads
