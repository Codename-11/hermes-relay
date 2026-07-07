"""Tests for the phone-Thread chat_id reader (``plugin.relay.session_store``).

The relay reads the gateway's SQLite session store read-only to surface the
per-session ``chat_id`` that ``/api/sessions`` omits, so the app can route a
reply into the right Thread. These build temp session DBs and assert
``read_phone_threads`` returns ``source=phone`` rows with their ``chat_id``,
deduped across the root + profile DBs, skipping rows with no ``chat_id`` and
tolerating an older schema (no ``chat_id`` column) or a missing home.

Run with::

    python -m unittest plugin.tests.test_session_store
"""

from __future__ import annotations

import os
import sqlite3
import tempfile
import unittest

from plugin.relay.session_store import read_phone_threads


def _make_db(path: str, rows, *, with_chat_id: bool = True) -> None:
    con = sqlite3.connect(path)
    cur = con.cursor()
    if with_chat_id:
        cur.execute(
            "CREATE TABLE sessions (id TEXT, source TEXT, chat_id TEXT, title TEXT)"
        )
        cur.executemany(
            "INSERT INTO sessions (id, source, chat_id, title) VALUES (?,?,?,?)", rows
        )
    else:
        cur.execute("CREATE TABLE sessions (id TEXT, source TEXT, title TEXT)")
        cur.executemany("INSERT INTO sessions (id, source, title) VALUES (?,?,?)", rows)
    con.commit()
    con.close()


class ReadPhoneThreadsTests(unittest.TestCase):
    def test_reads_phone_rows_with_chat_id(self) -> None:
        with tempfile.TemporaryDirectory() as home:
            _make_db(
                os.path.join(home, "state.db"),
                [
                    ("20260629_aaa", "phone", "t-proj-1", "Project One"),
                    ("20260629_bbb", "phone", "phone", "Home"),
                    ("20260629_ccc", "discord", "chan-9", "Discord"),
                    ("20260629_ddd", "tui", None, None),
                ],
            )
            byid = {t["session_id"]: t for t in read_phone_threads(home)}
            self.assertEqual(set(byid), {"20260629_aaa", "20260629_bbb"})
            self.assertEqual(byid["20260629_aaa"]["chat_id"], "t-proj-1")
            self.assertEqual(byid["20260629_aaa"]["title"], "Project One")
            self.assertEqual(byid["20260629_bbb"]["chat_id"], "phone")

    def test_skips_phone_rows_without_chat_id(self) -> None:
        with tempfile.TemporaryDirectory() as home:
            _make_db(
                os.path.join(home, "state.db"),
                [("s1", "phone", None, "No chat"), ("s2", "phone", "", "Blank chat")],
            )
            self.assertEqual(read_phone_threads(home), [])

    def test_merges_root_and_profile_dbs_dedup(self) -> None:
        with tempfile.TemporaryDirectory() as home:
            _make_db(os.path.join(home, "state.db"), [("s1", "phone", "c1", "Root")])
            prof = os.path.join(home, "profiles", "work")
            os.makedirs(prof)
            _make_db(
                os.path.join(prof, "state.db"),
                [("s2", "phone", "c2", "Work"), ("s1", "phone", "c1", "Root dup")],
            )
            ids = sorted(t["session_id"] for t in read_phone_threads(home))
            self.assertEqual(ids, ["s1", "s2"])  # s1 deduped (root scanned first)

    def test_old_schema_without_chat_id_column_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as home:
            _make_db(
                os.path.join(home, "state.db"),
                [("s1", "phone", "Old")],
                with_chat_id=False,
            )
            self.assertEqual(read_phone_threads(home), [])

    def test_missing_home_returns_empty(self) -> None:
        self.assertEqual(read_phone_threads("/nonexistent/hermes/home/xyz"), [])


if __name__ == "__main__":
    unittest.main()
