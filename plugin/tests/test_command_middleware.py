"""Tests for hermes_relay_bootstrap._command_middleware — slash-command
interception on /v1/chat/completions and /v1/runs.

These tests exercise the pure-logic helpers (message extraction, command
resolution, response building) and the middleware integration via a
minimal aiohttp test client.  Upstream ``hermes_cli.commands`` is mocked
so the test suite runs without a full hermes-agent install.
"""

from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import dataclass
from typing import Optional
from unittest.mock import MagicMock, patch

# ---------------------------------------------------------------------------
# Shared mock for hermes_cli.commands.resolve_command
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class _FakeCommandDef:
    name: str
    cli_only: bool = False
    gateway_config_gate: Optional[str] = None


_FAKE_COMMANDS = {
    "help": _FakeCommandDef("help"),
    "commands": _FakeCommandDef("commands"),
    "profile": _FakeCommandDef("profile"),
    "provider": _FakeCommandDef("provider"),
    "model": _FakeCommandDef("model"),
    "new": _FakeCommandDef("new"),
    "reset": _FakeCommandDef("new"),  # alias
    "clear": _FakeCommandDef("clear", cli_only=True),
    "verbose": _FakeCommandDef(
        "verbose", cli_only=True, gateway_config_gate="display.tool_progress_command"
    ),
}


def _mock_resolve_command(name: str):
    return _FAKE_COMMANDS.get(name.lower().lstrip("/"))


# ---------------------------------------------------------------------------
# Unit tests for message extraction helpers
# ---------------------------------------------------------------------------

class TestExtractUserMessageCompletions(unittest.TestCase):
    def _extract(self, body):
        from hermes_relay_bootstrap._command_middleware import (
            _extract_user_message_completions,
        )
        return _extract_user_message_completions(body)

    def test_last_user_message(self):
        body = {
            "messages": [
                {"role": "system", "content": "You are helpful."},
                {"role": "user", "content": "Hello"},
                {"role": "assistant", "content": "Hi there!"},
                {"role": "user", "content": "/help"},
            ]
        }
        self.assertEqual(self._extract(body), "/help")

    def test_no_user_message(self):
        body = {
            "messages": [
                {"role": "system", "content": "You are helpful."},
            ]
        }
        self.assertIsNone(self._extract(body))

    def test_missing_messages_key(self):
        self.assertIsNone(self._extract({}))

    def test_empty_messages(self):
        self.assertIsNone(self._extract({"messages": []}))


class TestExtractUserMessageRuns(unittest.TestCase):
    def _extract(self, body):
        from hermes_relay_bootstrap._command_middleware import (
            _extract_user_message_runs,
        )
        return _extract_user_message_runs(body)

    def test_string_input(self):
        self.assertEqual(self._extract({"input": "/help"}), "/help")

    def test_list_input(self):
        body = {
            "input": [
                {"role": "user", "content": "Hello"},
                {"role": "user", "content": "/commands"},
            ]
        }
        self.assertEqual(self._extract(body), "/commands")

    def test_missing_input(self):
        self.assertIsNone(self._extract({}))

    def test_empty_list_input(self):
        self.assertIsNone(self._extract({"input": []}))


# ---------------------------------------------------------------------------
# Unit tests for command resolution
# ---------------------------------------------------------------------------

class TestMaybeHandleCommand(unittest.TestCase):
    def _resolve(self, message):
        """Run _maybe_handle_command with hermes_cli.commands mocked."""
        import hermes_relay_bootstrap._command_middleware as mod

        # The function does ``from hermes_cli.commands import resolve_command``
        # inside the try/except body.  We inject a fake module into
        # sys.modules so that import resolves to our mock.
        fake_commands = MagicMock()
        fake_commands.resolve_command = _mock_resolve_command
        with patch.dict("sys.modules", {
            "hermes_cli": MagicMock(),
            "hermes_cli.commands": fake_commands,
        }):
            return mod._maybe_handle_command(message)

    def test_non_slash_returns_none(self):
        self.assertIsNone(self._resolve("Hello world"))

    def test_empty_returns_none(self):
        self.assertIsNone(self._resolve(""))
        self.assertIsNone(self._resolve(None))

    def test_unknown_command_returns_none(self):
        self.assertIsNone(self._resolve("/fakecmd"))

    def test_cli_only_without_gate_returns_none(self):
        """``/clear`` is cli_only with no gateway_config_gate."""
        self.assertIsNone(self._resolve("/clear"))

    def test_cli_only_with_gate_returns_decline(self):
        """``/verbose`` is cli_only but has a gateway_config_gate, so it
        gets a decline notice rather than falling through."""
        result = self._resolve("/verbose")
        self.assertIsNotNone(result)
        self.assertEqual(result.command, "verbose")
        self.assertIn("persistent session", result.text)

    def test_stateful_command_returns_decline(self):
        """``/model`` is a stateful command — returns decline notice."""
        result = self._resolve("/model")
        self.assertIsNotNone(result)
        self.assertEqual(result.command, "model")
        self.assertIn("persistent session", result.text)

    def test_stateful_alias_preserves_user_token(self):
        """``/reset`` (alias for ``/new``) shows the user's token."""
        result = self._resolve("/reset")
        self.assertIsNotNone(result)
        self.assertIn("/reset", result.text)

    def test_stateless_help_returns_text(self):
        """``/help`` is a stateless handler — should return text."""
        # Mock the handler since it imports hermes_cli.commands.gateway_help_lines
        with patch.dict(
            "hermes_relay_bootstrap._command_middleware._STATELESS_HANDLERS",
            {"help": lambda _args: "**Hermes Commands**\ntest help output"},
        ):
            result = self._resolve("/help")
        self.assertIsNotNone(result)
        self.assertEqual(result.command, "help")
        self.assertIn("Hermes Commands", result.text)

    def test_handler_exception_returns_fallback(self):
        """If a stateless handler raises, return a fallback message."""
        def _broken_handler(_args):
            raise RuntimeError("test error")

        with patch.dict(
            "hermes_relay_bootstrap._command_middleware._STATELESS_HANDLERS",
            {"help": _broken_handler},
        ):
            result = self._resolve("/help")
        self.assertIsNotNone(result)
        self.assertEqual(result.command, "help")
        self.assertIn("encountered an error", result.text)


# ---------------------------------------------------------------------------
# Unit tests for synthetic response builders
# ---------------------------------------------------------------------------

class TestBuildChatCompletionJson(unittest.TestCase):
    def test_shape(self):
        from hermes_relay_bootstrap._command_middleware import (
            _build_chat_completion_json,
        )
        resp = _build_chat_completion_json(
            text="Hello",
            model="hermes-agent",
            completion_id="chatcmpl-test123",
            created=1000000,
        )
        self.assertEqual(resp["object"], "chat.completion")
        self.assertEqual(resp["id"], "chatcmpl-test123")
        self.assertEqual(resp["model"], "hermes-agent")
        self.assertEqual(resp["choices"][0]["message"]["role"], "assistant")
        self.assertEqual(resp["choices"][0]["message"]["content"], "Hello")
        self.assertEqual(resp["choices"][0]["finish_reason"], "stop")


class TestInjectRunEvents(unittest.TestCase):
    def test_successful_injection(self):
        from hermes_relay_bootstrap._command_middleware import _inject_run_events

        adapter = MagicMock()
        adapter._run_streams = {}
        adapter._run_streams_created = {}

        result = _inject_run_events(adapter, "run_test123", "Hello from /help")
        self.assertTrue(result)
        self.assertIn("run_test123", adapter._run_streams)
        self.assertIn("run_test123", adapter._run_streams_created)

        # Drain the queue and check events.
        q = adapter._run_streams["run_test123"]
        event1 = q.get_nowait()
        self.assertEqual(event1["event"], "message.delta")
        self.assertEqual(event1["delta"], "Hello from /help")
        self.assertEqual(event1["run_id"], "run_test123")

        event2 = q.get_nowait()
        self.assertEqual(event2["event"], "run.completed")
        self.assertEqual(event2["output"], "Hello from /help")

        sentinel = q.get_nowait()
        self.assertIsNone(sentinel)

    def test_missing_run_streams_attribute(self):
        from hermes_relay_bootstrap._command_middleware import _inject_run_events

        adapter = MagicMock(spec=[])  # no attributes at all
        result = _inject_run_events(adapter, "run_test456", "text")
        self.assertFalse(result)


# ---------------------------------------------------------------------------
# Unit tests for feature detection
# ---------------------------------------------------------------------------

class TestMaybeInstallMiddleware(unittest.TestCase):
    def test_skips_when_upstream_module_exists(self):
        """If gateway.platforms.api_server_slash is importable, skip."""
        from hermes_relay_bootstrap._command_middleware import maybe_install_middleware

        fake_module = MagicMock()
        app = MagicMock()
        adapter = MagicMock()

        # Inject the module AND its parent packages so ``import
        # gateway.platforms.api_server_slash`` succeeds.
        with patch.dict("sys.modules", {
            "gateway": MagicMock(),
            "gateway.platforms": MagicMock(),
            "gateway.platforms.api_server_slash": fake_module,
        }):
            result = maybe_install_middleware(app, adapter)

        self.assertFalse(result)

    def test_installs_when_upstream_absent(self):
        """When upstream module is missing, middleware is installed."""
        from hermes_relay_bootstrap._command_middleware import maybe_install_middleware

        # Make sure the upstream module is NOT importable.
        import sys
        saved = sys.modules.pop("gateway.platforms.api_server_slash", None)
        # Also patch the import so it raises ImportError.
        original_import = __builtins__.__import__ if hasattr(__builtins__, '__import__') else __import__

        app = MagicMock()
        app._middlewares = ()
        adapter = MagicMock()

        try:
            # Ensure import raises ImportError for the upstream module.
            with patch.dict("sys.modules", {
                "gateway.platforms.api_server_slash": None,
            }):
                # sys.modules[key] = None causes ImportError on import
                result = maybe_install_middleware(app, adapter)
        finally:
            if saved is not None:
                sys.modules["gateway.platforms.api_server_slash"] = saved
            else:
                sys.modules.pop("gateway.platforms.api_server_slash", None)

        self.assertTrue(result)
        self.assertEqual(len(app._middlewares), 1)


# ---------------------------------------------------------------------------
# Integration test — middleware via aiohttp test client
# ---------------------------------------------------------------------------

try:
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer

    _HAS_AIOHTTP = True
except ImportError:
    _HAS_AIOHTTP = False


@unittest.skipUnless(_HAS_AIOHTTP, "aiohttp not installed")
class TestMiddlewareIntegration(unittest.TestCase):
    """Full round-trip tests using aiohttp's test client."""

    def _make_app(self):
        """Build a minimal app with the middleware installed."""
        from hermes_relay_bootstrap._command_middleware import make_command_middleware

        adapter = MagicMock()
        adapter._check_auth.return_value = None  # no auth errors
        adapter._run_streams = {}
        adapter._run_streams_created = {}

        middleware = make_command_middleware(adapter)

        async def chat_completions_handler(request):
            """Stub handler — should NOT be reached for intercepted commands."""
            return web.json_response({"stub": "chat_completions_reached"})

        async def runs_handler(request):
            """Stub handler — should NOT be reached for intercepted commands."""
            return web.json_response({"stub": "runs_reached"})

        async def run_events_handler(request):
            """GET /v1/runs/{run_id}/events — drain queue and return SSE."""
            run_id = request.match_info["run_id"]
            q = adapter._run_streams.get(run_id)
            if q is None:
                return web.json_response({"error": "not found"}, status=404)

            response = web.StreamResponse(
                status=200,
                headers={"Content-Type": "text/event-stream"},
            )
            await response.prepare(request)
            while True:
                event = await q.get()
                if event is None:
                    break
                await response.write(
                    f"data: {json.dumps(event)}\n\n".encode()
                )
            return response

        async def health_handler(request):
            return web.json_response({"status": "ok"})

        app = web.Application(middlewares=[middleware])
        app["api_server_adapter"] = adapter
        app.router.add_post("/v1/chat/completions", chat_completions_handler)
        app.router.add_post("/v1/runs", runs_handler)
        app.router.add_get("/v1/runs/{run_id}/events", run_events_handler)
        app.router.add_get("/health", health_handler)

        return app, adapter

    def _run(self, coro):
        """Run an async test on a fresh event loop."""
        loop = asyncio.new_event_loop()
        try:
            return loop.run_until_complete(coro)
        finally:
            loop.close()

    # -- /v1/chat/completions tests --

    def test_completions_help_non_streaming(self):
        """``/help`` on completions returns a synthetic chat.completion."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                with patch(
                    "hermes_relay_bootstrap._command_middleware._STATELESS_HANDLERS",
                    {"help": lambda _: "**Hermes Commands**\ntest"},
                ), patch.dict("sys.modules", {
                    "hermes_cli.commands": MagicMock(
                        resolve_command=_mock_resolve_command,
                    ),
                }):
                    resp = await cli.post(
                        "/v1/chat/completions",
                        json={
                            "messages": [{"role": "user", "content": "/help"}],
                        },
                    )
                    self.assertEqual(resp.status, 200)
                    data = await resp.json()
                    self.assertEqual(data["object"], "chat.completion")
                    self.assertIn("Hermes Commands", data["choices"][0]["message"]["content"])

        self._run(_test())

    def test_completions_help_streaming(self):
        """``/help`` on completions with stream=true returns SSE chunks."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                with patch(
                    "hermes_relay_bootstrap._command_middleware._STATELESS_HANDLERS",
                    {"help": lambda _: "**Test Help**"},
                ), patch.dict("sys.modules", {
                    "hermes_cli.commands": MagicMock(
                        resolve_command=_mock_resolve_command,
                    ),
                }):
                    resp = await cli.post(
                        "/v1/chat/completions",
                        json={
                            "messages": [{"role": "user", "content": "/help"}],
                            "stream": True,
                        },
                    )
                    self.assertEqual(resp.status, 200)
                    body = await resp.text()
                    # Should contain SSE data lines.
                    self.assertIn("data: ", body)
                    self.assertIn("[DONE]", body)
                    self.assertIn("Test Help", body)
                    # Parse the chunks.
                    lines = [
                        l for l in body.strip().split("\n")
                        if l.startswith("data: ") and l != "data: [DONE]"
                    ]
                    self.assertEqual(len(lines), 3)  # role, content, finish

        self._run(_test())

    def test_completions_normal_text_passes_through(self):
        """Non-slash text goes straight to the handler."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                resp = await cli.post(
                    "/v1/chat/completions",
                    json={
                        "messages": [{"role": "user", "content": "Hello world"}],
                    },
                )
                self.assertEqual(resp.status, 200)
                data = await resp.json()
                self.assertEqual(data["stub"], "chat_completions_reached")

        self._run(_test())

    def test_completions_unknown_slash_passes_through(self):
        """Unknown ``/fakecmd`` falls through to the handler."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                with patch.dict("sys.modules", {
                    "hermes_cli.commands": MagicMock(
                        resolve_command=_mock_resolve_command,
                    ),
                }):
                    resp = await cli.post(
                        "/v1/chat/completions",
                        json={
                            "messages": [{"role": "user", "content": "/fakecmd"}],
                        },
                    )
                    self.assertEqual(resp.status, 200)
                    data = await resp.json()
                    self.assertEqual(data["stub"], "chat_completions_reached")

        self._run(_test())

    def test_completions_stateful_decline(self):
        """``/model`` returns a decline notice, not a handler call."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                with patch.dict("sys.modules", {
                    "hermes_cli.commands": MagicMock(
                        resolve_command=_mock_resolve_command,
                    ),
                }):
                    resp = await cli.post(
                        "/v1/chat/completions",
                        json={
                            "messages": [{"role": "user", "content": "/model gpt-4"}],
                        },
                    )
                    self.assertEqual(resp.status, 200)
                    data = await resp.json()
                    self.assertEqual(data["object"], "chat.completion")
                    self.assertIn("persistent session", data["choices"][0]["message"]["content"])

        self._run(_test())

    # -- /v1/runs tests --

    def test_runs_help_injects_events(self):
        """``/help`` on /v1/runs injects events into the adapter's queue."""
        async def _test():
            app, adapter = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                with patch(
                    "hermes_relay_bootstrap._command_middleware._STATELESS_HANDLERS",
                    {"help": lambda _: "**Run Help**"},
                ), patch.dict("sys.modules", {
                    "hermes_cli.commands": MagicMock(
                        resolve_command=_mock_resolve_command,
                    ),
                }):
                    resp = await cli.post(
                        "/v1/runs",
                        json={"input": "/help"},
                    )
                    self.assertEqual(resp.status, 202)
                    data = await resp.json()
                    self.assertIn("run_id", data)
                    self.assertEqual(data["status"], "started")

                    # Verify events were injected.
                    run_id = data["run_id"]
                    self.assertIn(run_id, adapter._run_streams)
                    q = adapter._run_streams[run_id]
                    ev1 = q.get_nowait()
                    self.assertEqual(ev1["event"], "message.delta")
                    self.assertIn("Run Help", ev1["delta"])

        self._run(_test())

    def test_runs_normal_input_passes_through(self):
        """Non-slash input on /v1/runs triggers the handler."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                resp = await cli.post(
                    "/v1/runs",
                    json={"input": "Hello world"},
                )
                self.assertEqual(resp.status, 200)
                data = await resp.json()
                self.assertEqual(data["stub"], "runs_reached")

        self._run(_test())

    # -- Non-intercepted paths --

    def test_health_not_intercepted(self):
        """GET /health must bypass the middleware entirely."""
        async def _test():
            app, _ = self._make_app()
            async with TestClient(TestServer(app)) as cli:
                resp = await cli.get("/health")
                self.assertEqual(resp.status, 200)
                data = await resp.json()
                self.assertEqual(data["status"], "ok")

        self._run(_test())

    # -- Auth rejection --

    def test_auth_rejection_prevents_interception(self):
        """If _check_auth returns an error, the middleware returns it."""
        async def _test():
            app, adapter = self._make_app()
            from aiohttp import web as _web
            adapter._check_auth.return_value = _web.json_response(
                {"error": "Unauthorized"}, status=401,
            )
            async with TestClient(TestServer(app)) as cli:
                resp = await cli.post(
                    "/v1/chat/completions",
                    json={
                        "messages": [{"role": "user", "content": "/help"}],
                    },
                )
                self.assertEqual(resp.status, 401)

        self._run(_test())


if __name__ == "__main__":
    unittest.main()
