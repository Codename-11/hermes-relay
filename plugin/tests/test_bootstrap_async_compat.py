"""Async and request-boundary coverage for retained bootstrap handlers."""

from __future__ import annotations

import asyncio
import json
import threading
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

    async def test_first_construction_is_offloaded_and_shared(self) -> None:
        constructor_started = threading.Event()
        allow_constructor_return = threading.Event()
        constructor_saw_ticker: list[bool] = []
        raw_db = mock.MagicMock()
        raw_db.search_messages.return_value = []

        def make_db():
            constructor_started.set()
            constructor_saw_ticker.append(allow_constructor_return.wait(timeout=1.0))
            return raw_db

        session_db_cls = mock.MagicMock(side_effect=make_db)
        upstream = {
            "web": web,
            "SessionDB": session_db_cls,
            "AsyncSessionDB": None,
        }
        handler = _handlers._make_session_search_handlers(
            _Adapter(), upstream
        )["search_sessions"]

        async def tick_after_constructor_starts() -> None:
            while not constructor_started.is_set():
                await asyncio.sleep(0)
            allow_constructor_return.set()

        responses = await asyncio.gather(
            handler(_Request(query={"q": "first"})),
            handler(_Request(query={"q": "second"})),
            tick_after_constructor_starts(),
        )

        self.assertEqual([response.status for response in responses[:2]], [200, 200])
        self.assertEqual(constructor_saw_ticker, [True])
        session_db_cls.assert_called_once_with()


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


class BootstrapConfigRouteIdentityTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        _handlers._adapter_state.clear()

    async def _patch_config(self, starting_config: dict, body: dict) -> dict:
        saved: list[dict] = []
        upstream = {
            "web": web,
            "load_config": mock.MagicMock(return_value=starting_config),
            "save_config": mock.MagicMock(side_effect=saved.append),
            "curated_models_for_provider": mock.MagicMock(return_value=[]),
            "list_available_providers": mock.MagicMock(return_value=[]),
        }
        handler = _handlers._make_config_handlers(_Adapter(), upstream)["update_config"]

        response = await handler(_Request(body=body))

        self.assertEqual(response.status, 200)
        self.assertEqual(len(saved), 1)
        return saved[0]["model"]

    async def test_model_change_clears_stale_context_length(self) -> None:
        model = await self._patch_config(
            {
                "model": {
                    "default": "old-model",
                    "provider": "openrouter",
                    "base_url": "https://openrouter.ai/api/v1",
                    "context_length": 262144,
                    "temperature": 0.2,
                }
            },
            {"model": "new-model"},
        )

        self.assertEqual(model["default"], "new-model")
        self.assertEqual(model["temperature"], 0.2)
        self.assertNotIn("context_length", model)

    async def test_provider_change_clears_stale_context_length(self) -> None:
        model = await self._patch_config(
            {
                "model": {
                    "default": "same-model",
                    "provider": "openrouter",
                    "base_url": "https://openrouter.ai/api/v1",
                    "context_length": 262144,
                }
            },
            {"provider": "anthropic"},
        )

        self.assertEqual(model["provider"], "anthropic")
        self.assertNotIn("context_length", model)

    async def test_equivalent_base_url_preserves_context_length(self) -> None:
        model = await self._patch_config(
            {
                "model": {
                    "default": "same-model",
                    "provider": "custom",
                    "base_url": "https://Example.COM:443/v1/?b=2&a=1",
                    "context_length": 8192,
                }
            },
            {"base_url": "https://example.com/v1?a=1&b=2"},
        )

        self.assertEqual(model["context_length"], 8192)

    async def test_different_base_url_path_clears_context_length(self) -> None:
        model = await self._patch_config(
            {
                "model": {
                    "default": "same-model",
                    "provider": "custom",
                    "base_url": "https://example.com/v1",
                    "context_length": 8192,
                }
            },
            {"base_url": "https://example.com/alternate"},
        )

        self.assertNotIn("context_length", model)


if __name__ == "__main__":
    unittest.main()
