"""Tests for experimental ``desktop_computer_*`` tool registration.

These are schema/wrapper tests only. The actual OS screenshot/input behavior
lives in the desktop TypeScript client and is covered by the desktop build and
smoke path.
"""

from __future__ import annotations

import json
import unittest
from typing import Any

from plugin.tools import desktop_tool


COMPUTER_TOOLS = [
    "desktop_computer_status",
    "desktop_computer_screenshot",
    "desktop_computer_action",
    "desktop_computer_grant_request",
    "desktop_computer_cancel",
]


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

    def test_grant_request_schema_only_requires_mode(self) -> None:
        schema = desktop_tool._SCHEMAS["desktop_computer_grant_request"]
        self.assertEqual(schema["parameters"]["required"], ["mode"])
        self.assertEqual(
            schema["parameters"]["properties"]["mode"]["enum"],
            ["observe", "assist", "control"],
        )


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
