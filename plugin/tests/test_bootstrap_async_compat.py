"""Async and request-boundary coverage for retained bootstrap handlers."""

from __future__ import annotations

import json
import unittest
from unittest import mock

from aiohttp import web

from hermes_relay_bootstrap import _handlers


class _Adapter:
    def _check_auth(self, _request):
        return None


class _Request:
    def __init__(self, body: dict | None = None, query: dict | None = None) -> None:
        self._body = body or {}
        self.query = query or {}

    async def json(self) -> dict:
        return self._body


def _response_json(response: web.Response) -> dict:
    return json.loads(response.body.decode("utf-8"))


class BootstrapSessionOffloadTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        _handlers._adapter_state.clear()

    async def test_uses_async_session_db_when_available(self) -> None:
        raw_db = object()
        async_db = mock.MagicMock()
        async_db.search_messages = mock.AsyncMock(return_value=[{"id": "one"}])
        async_db_cls = mock.MagicMock(return_value=async_db)
        upstream = {
            "web": web,
            "SessionDB": mock.MagicMock(return_value=raw_db),
            "AsyncSessionDB": async_db_cls,
        }
        handler = _handlers._make_session_search_handlers(
            _Adapter(), upstream
        )["search_sessions"]

        response = await handler(_Request(query={"q": "needle"}))

        self.assertEqual(response.status, 200)
        self.assertEqual(_response_json(response)["results"], [{"id": "one"}])
        async_db_cls.assert_called_once_with(raw_db)
        async_db.search_messages.assert_awaited_once_with(
            query="needle", limit=20, offset=0
        )

    async def test_falls_back_to_to_thread_on_older_upstream(self) -> None:
        raw_db = mock.MagicMock()
        raw_db.search_messages.return_value = [{"id": "fallback"}]
        upstream = {
            "web": web,
            "SessionDB": mock.MagicMock(return_value=raw_db),
            "AsyncSessionDB": None,
        }
        handler = _handlers._make_session_search_handlers(
            _Adapter(), upstream
        )["search_sessions"]

        response = await handler(_Request(query={"q": "older", "limit": "5"}))

        self.assertEqual(response.status, 200)
        self.assertEqual(_response_json(response)["results"], [{"id": "fallback"}])
        raw_db.search_messages.assert_called_once_with(
            query="older", limit=5, offset=0
        )


class _BudgetedMemoryStore:
    def __init__(self) -> None:
        self.memory_entries: list[str] = []
        self.user_entries: list[str] = []
        self.failures = 0
        self.reset_calls = 0

    def load_from_disk(self) -> None:
        pass

    def reset_consolidation_failures(self) -> None:
        self.failures = 0
        self.reset_calls += 1

    def replace(self, _target: str, _old_text: str, _content: str) -> dict:
        self.failures += 1
        if self.failures > 3:
            return {"success": False, "done": True}
        return {
            "success": False,
            "error": "old_text not found",
            "current_entries": [],
        }


class BootstrapMemoryBudgetTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        _handlers._adapter_state.clear()

    async def test_each_rest_mutation_gets_a_fresh_failure_budget(self) -> None:
        store = _BudgetedMemoryStore()
        upstream = {
            "web": web,
            "MemoryStore": mock.MagicMock(return_value=store),
        }
        handler = _handlers._make_memory_handlers(
            _Adapter(), upstream
        )["replace_memory"]
        request = _Request(
            body={"target": "memory", "old_text": "missing", "content": "new"}
        )

        responses = [await handler(request) for _ in range(4)]

        self.assertEqual(store.reset_calls, 4)
        for response in responses:
            self.assertEqual(response.status, 400)
            body = _response_json(response)
            self.assertIn("current_entries", body)
            self.assertNotIn("done", body)

    async def test_older_memory_store_without_reset_method_still_works(self) -> None:
        store = mock.MagicMock(spec=["load_from_disk", "add"])
        store.add.return_value = {"success": True}
        upstream = {
            "web": web,
            "MemoryStore": mock.MagicMock(return_value=store),
        }
        handler = _handlers._make_memory_handlers(
            _Adapter(), upstream
        )["add_memory"]

        response = await handler(
            _Request(body={"target": "memory", "content": "remember this"})
        )

        self.assertEqual(response.status, 200)
        store.add.assert_called_once_with("memory", "remember this")


if __name__ == "__main__":
    unittest.main()
