"""Typed chat stream passthrough tests for the Relay chat channel."""

from __future__ import annotations

import json
import unittest
from typing import Any

from plugin.relay.channels.chat import ChatHandler


class FakeWebSocket:
    def __init__(self) -> None:
        self.closed = False
        self.sent: list[str] = []

    async def send_str(self, data: str) -> None:
        self.sent.append(data)


class ChatTypedStreamTests(unittest.IsolatedAsyncioTestCase):
    async def test_typed_client_receives_ordered_stream_event_envelopes(self) -> None:
        handler = ChatHandler()
        ws = FakeWebSocket()
        handler.set_client_capabilities(
            ws,  # type: ignore[arg-type]
            {"supports": {"typed_stream_events": True, "event_schema_version": 1}},
        )

        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            json.dumps(
                {
                    "type": "assistant.delta",
                    "session_id": "sess-1",
                    "run_id": "run-1",
                    "delta": "Hello",
                }
            ),
            "sess-1",
            "msg-1",
            None,
        )
        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            json.dumps(
                {
                    "type": "tool.started",
                    "session_id": "sess-1",
                    "run_id": "run-1",
                    "tool_name": "terminal",
                    "call_id": "call-1",
                    "args": {"cmd": "echo ok", "api_key": "secret-value"},
                }
            ),
            "sess-1",
            "msg-1",
            None,
        )
        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            "[DONE]",
            "sess-1",
            "msg-1",
            None,
        )

        envelopes = [json.loads(item) for item in ws.sent]
        self.assertEqual([env["type"] for env in envelopes], ["stream.event", "stream.event", "stream.event"])
        payloads: list[dict[str, Any]] = [env["payload"] for env in envelopes]
        self.assertEqual([p["event"] for p in payloads], ["assistant.delta", "tool.started", "done"])
        self.assertEqual([p["seq"] for p in payloads], [1, 2, 3])
        self.assertEqual(payloads[0]["schema_version"], 1)
        self.assertEqual(payloads[0]["session_id"], "sess-1")
        self.assertEqual(payloads[1]["run_id"], "run-1")
        self.assertEqual(payloads[1]["payload"]["args"]["api_key"], "[REDACTED]")
        self.assertEqual(payloads[2]["payload"], {"state": "final"})

    async def test_legacy_client_keeps_flattened_text_mode(self) -> None:
        handler = ChatHandler()
        ws = FakeWebSocket()

        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            json.dumps({"type": "assistant.delta", "delta": "Hi"}),
            "sess-legacy",
            "msg-legacy",
            None,
        )
        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            json.dumps({"type": "artifact.created", "url": "https://example.invalid/a"}),
            "sess-legacy",
            "msg-legacy",
            None,
        )
        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            json.dumps({"type": "tool.failed", "tool_name": "terminal", "error": "boom"}),
            "sess-legacy",
            "msg-legacy",
            None,
        )
        await handler._emit_sse_event(  # type: ignore[attr-defined]
            ws,  # type: ignore[arg-type]
            "[DONE]",
            "sess-legacy",
            "msg-legacy",
            None,
        )

        envelopes = [json.loads(item) for item in ws.sent]
        self.assertEqual([env["type"] for env in envelopes], ["chat.delta", "chat.tool.failed"])
        self.assertEqual(envelopes[0]["payload"]["delta"], "Hi")
        self.assertEqual(envelopes[1]["payload"]["error"], "boom")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
