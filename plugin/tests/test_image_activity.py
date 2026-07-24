"""Tests for the optional Relay image-generation activity bridge."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.image_activity import read_image_activity
from plugin.relay.server import create_app


def _create_state_db(path: Path) -> None:
    connection = sqlite3.connect(path)
    connection.execute(
        """
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT,
            tool_call_id TEXT,
            tool_calls TEXT,
            tool_name TEXT,
            timestamp REAL NOT NULL,
            active INTEGER NOT NULL DEFAULT 1
        )
        """
    )
    connection.commit()
    connection.close()


def _insert(
    path: Path,
    *,
    role: str,
    timestamp: float,
    tool_calls: object | None = None,
    tool_call_id: str | None = None,
    tool_name: str | None = None,
) -> None:
    connection = sqlite3.connect(path)
    connection.execute(
        """
        INSERT INTO messages
            (session_id, role, tool_call_id, tool_calls, tool_name, timestamp)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (
            "session-1",
            role,
            tool_call_id,
            json.dumps(tool_calls) if tool_calls is not None else None,
            tool_name,
            timestamp,
        ),
    )
    connection.commit()
    connection.close()


def test_read_image_activity_reports_running_and_ignores_other_tools(tmp_path: Path) -> None:
    db_path = tmp_path / "state.db"
    _create_state_db(db_path)
    _insert(
        db_path,
        role="assistant",
        timestamp=100.0,
        tool_calls=[
            {"id": "call-read", "function": {"name": "read_file"}},
            {"id": "call-image", "function": {"name": "image_generate"}},
        ],
    )

    assert read_image_activity(db_path, "session-1", 99.0) == [
        {
            "call_id": "call-image",
            "tool_name": "image_generate",
            "state": "running",
            "started_at": 100.0,
            "completed_at": None,
        }
    ]


def test_read_image_activity_matches_completion_and_honors_since(tmp_path: Path) -> None:
    db_path = tmp_path / "state.db"
    _create_state_db(db_path)
    _insert(
        db_path,
        role="assistant",
        timestamp=100.0,
        tool_calls=[{"id": "old-image", "function": {"name": "image_generate"}}],
    )
    _insert(
        db_path,
        role="assistant",
        timestamp=200.0,
        tool_calls=[{"id": "new-image", "function": {"name": "image_generate"}}],
    )
    _insert(
        db_path,
        role="tool",
        timestamp=240.0,
        tool_call_id="new-image",
        tool_name="image_generate",
    )

    assert read_image_activity(db_path, "session-1", 150.0) == [
        {
            "call_id": "new-image",
            "tool_name": "image_generate",
            "state": "completed",
            "started_at": 200.0,
            "completed_at": 240.0,
        }
    ]


class ImageActivityRouteTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        config_path = Path(self.tmp_path.name) / "config.yaml"
        config_path.write_text("{}\n", encoding="utf-8")
        _create_state_db(config_path.parent / "state.db")
        return create_app(
            RelayConfig(
                hermes_config_path=str(config_path),
                profile_discovery_enabled=False,
            )
        )

    async def asyncSetUp(self) -> None:
        import tempfile

        self.tmp_path = tempfile.TemporaryDirectory()
        await super().asyncSetUp()

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        self.tmp_path.cleanup()

    async def test_route_requires_valid_bearer(self) -> None:
        response = await self.client.get(
            "/chat/image-activity?profile=default&session_id=session-1&since=1"
        )
        assert response.status == 401

    async def test_route_returns_activity(self) -> None:
        db_path = Path(self.tmp_path.name) / "state.db"
        _insert(
            db_path,
            role="assistant",
            timestamp=10.0,
            tool_calls=[
                {"id": "image-1", "function": {"name": "image_generate"}}
            ],
        )
        token = self.app["server"].sessions.create_session("phone", "phone-1").token

        response = await self.client.get(
            "/chat/image-activity?profile=default&session_id=session-1&since=1",
            headers={"Authorization": f"Bearer {token}"},
        )

        assert response.status == 200
        body = await response.json()
        assert body["activities"][0]["call_id"] == "image-1"
