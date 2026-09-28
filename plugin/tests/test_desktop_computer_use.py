"""Tests for experimental ``desktop_computer_*`` tool registration.

These are schema/wrapper tests only. The actual OS screenshot/input behavior
lives in the desktop TypeScript client and is covered by the desktop build and
smoke path.
"""

from __future__ import annotations

import base64
import json
import struct
import unittest
import zlib
from typing import Any
from unittest import mock

from plugin.tools import desktop_tool


COMPUTER_TOOLS = [
    "desktop_computer_status",
    "desktop_computer_screenshot",
    "desktop_computer_action",
    "desktop_computer_grant_request",
    "desktop_computer_cancel",
]


def one_pixel_png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data +
                struct.pack(">I", zlib.crc32(kind + data)))

    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\xff")) +
            chunk(b"IEND", b""))


class DesktopComputerUseToolTests(unittest.TestCase):
    def test_all_computer_tools_have_schema_and_handler(self) -> None:
        for name in COMPUTER_TOOLS:
            with self.subTest(name=name):
                self.assertIn(name, desktop_tool._SCHEMAS)
                self.assertIn(name, desktop_tool._HANDLERS)
                self.assertIn("[EXPERIMENTAL]", desktop_tool._SCHEMAS[name]["description"])

    def test_action_wrapper_forwards_arbitrary_action_fields(self) -> None:
        calls: list[tuple[str, dict[str, Any]]] = []

        def fake_post(path: str, payload: dict[str, Any]) -> dict[str, Any]:
            calls.append((path, payload))
            return {"ok": True, "result": {"code": "grant_required"}}

        original = desktop_tool._post
        desktop_tool._post = fake_post
        try:
            raw = desktop_tool.desktop_computer_action(
                "left_click",
                coordinate=[10, 20],
                intent="test click",
                return_screenshot=True,
            )
        finally:
            desktop_tool._post = original

        self.assertEqual(
            calls,
            [
                (
                    "/desktop/desktop_computer_action",
                    {
                        "action": "left_click",
                        "coordinate": [10, 20],
                        "intent": "test click",
                        "return_screenshot": True,
                    },
                )
            ],
        )
        self.assertEqual(json.loads(raw)["result"]["code"], "grant_required")

    def test_other_wrappers_forward_expected_payloads(self) -> None:
        calls: list[tuple[str, dict[str, Any]]] = []

        def fake_post(path: str, payload: dict[str, Any]) -> dict[str, Any]:
            calls.append((path, payload))
            return {"ok": True, "result": {"ok": True}}

        original = desktop_tool._post
        desktop_tool._post = fake_post
        try:
            desktop_tool.desktop_computer_status(include_recent=True)
            desktop_tool.desktop_computer_screenshot(display="primary", save_to="shot.png")
            desktop_tool.desktop_computer_grant_request(
                "assist",
                scope={"display": "primary"},
                duration_seconds=60,
                reason="test",
            )
            desktop_tool.desktop_computer_cancel("done")
        finally:
            desktop_tool._post = original

        self.assertEqual(
            calls,
            [
                ("/desktop/desktop_computer_status", {"include_recent": True}),
                (
                    "/desktop/desktop_computer_screenshot",
                    {
                        "display": "primary",
                        "include_cursor": True,
                        "redact_sensitive": True,
                        "save_to": "shot.png",
                    },
                ),
                (
                    "/desktop/desktop_computer_grant_request",
                    {
                        "mode": "assist",
                        "duration_seconds": 60,
                        "reason": "test",
                        "scope": {"display": "primary"},
                    },
                ),
                ("/desktop/desktop_computer_cancel", {"reason": "done"}),
            ],
        )

    def test_screenshot_attaches_actual_png_for_host_vision(self) -> None:
        png = one_pixel_png()
        encoded = base64.b64encode(png).decode("ascii")
        result = {"ok": True, "request_id": "request-1", "result": {
            "ok": True, "bytes_base64": encoded, "size_bytes": len(png),
            "display": {"width": 800, "height": 600},
        }}
        with mock.patch.object(desktop_tool, "_post", return_value=result):
            output = desktop_tool._HANDLERS["desktop_computer_screenshot"]({})
        self.assertTrue(output["_multimodal"])
        self.assertEqual(output["content"][1]["image_url"]["url"],
                         f"data:image/png;base64,{encoded}")
        self.assertNotIn(encoded, output["content"][0]["text"])
        self.assertIn('"width": 800', output["content"][0]["text"])
        self.assertIn('"request_id": "request-1"', output["content"][0]["text"])

    def test_screenshot_keeps_saved_path_and_rejects_bad_bytes(self) -> None:
        with mock.patch.object(desktop_tool, "_post", return_value={
            "ok": True, "saved_path": "/tmp/shot.png", "size_bytes": 10,
        }):
            self.assertEqual(json.loads(desktop_tool.desktop_computer_screenshot())["saved_path"],
                             "/tmp/shot.png")
        with mock.patch.object(desktop_tool, "_post", return_value={
            "ok": True, "bytes_base64": "%%%",
        }):
            self.assertIn("error", json.loads(desktop_tool.desktop_computer_screenshot()))

    def test_cua_screenshot_preserves_element_metadata_with_image(self) -> None:
        jpeg = b"\xff\xd8\xff\xe0private-window"
        encoded = base64.b64encode(jpeg).decode("ascii")
        with mock.patch.object(desktop_tool, "_post", return_value={
            "ok": True, "result": {
                "backend": "cua_driver",
                "elements": [{"snapshot_token": "one-use-token"}],
                "screenshot_base64": encoded,
                "screenshot_mime_type": "image/jpeg",
            },
        }):
            output = desktop_tool.desktop_computer_screenshot(pid=1, window_id=2)
        self.assertEqual(output["content"][1]["image_url"]["url"],
                         f"data:image/jpeg;base64,{encoded}")
        self.assertIn("one-use-token", output["content"][0]["text"])
        self.assertNotIn(encoded, output["content"][0]["text"])

    def test_grant_request_schema_only_requires_mode(self) -> None:
        schema = desktop_tool._SCHEMAS["desktop_computer_grant_request"]
        self.assertEqual(schema["parameters"]["required"], ["mode"])
        self.assertEqual(
            schema["parameters"]["properties"]["mode"]["enum"],
            ["observe", "assist", "control"],
        )
        self.assertEqual(
            schema["parameters"]["properties"]["duration_seconds"]["maximum"],
            3600,
        )

    def test_action_schema_has_bounded_coordinate_and_scroll_shapes(self) -> None:
        params = desktop_tool._SCHEMAS["desktop_computer_action"]["parameters"]
        coordinate = params["properties"]["coordinate"]
        self.assertEqual(coordinate["minItems"], 2)
        self.assertEqual(coordinate["maxItems"], 2)
        self.assertFalse(params["additionalProperties"])
        self.assertFalse(params["properties"]["scroll"]["additionalProperties"])

    def test_computer_use_tools_are_not_relay_only(self) -> None:
        for name in COMPUTER_TOOLS:
            self.assertNotIn(name, desktop_tool._RELAY_ONLY_TOOLS)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
