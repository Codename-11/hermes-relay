"""Regression for zero-node Accessibility reads with omitted bounds."""

from __future__ import annotations

import json
import unittest
from copy import deepcopy
from unittest.mock import patch

from plugin.tools import android_tool


class ScreenBoundsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.payload = {
            "packageName": "com.example.app",
            "nodeCount": 2,
            "nodes": [
                {
                    "nodeId": "w0:0",
                    "text": "Display",
                    "bounds": {"left": 10, "top": 20, "right": 200, "bottom": 90},
                },
                {
                    "nodeId": "w0:1",
                    "text": "Search",
                    "bounds": {"left": 5, "top": 100, "right": 250, "bottom": 155},
                },
            ],
        }

    def test_without_bounds_keeps_nodes_but_hides_coordinates(self) -> None:
        with patch.object(android_tool, "_get", return_value=deepcopy(self.payload)) as get:
            result = json.loads(android_tool.android_read_screen())
        get.assert_called_once_with("/screen?include_bounds=true")
        self.assertEqual(result["nodeCount"], 2)
        self.assertEqual([n["text"] for n in result["nodes"]], ["Display", "Search"])
        for node in result["nodes"]:
            self.assertEqual(
                node["bounds"],
                {"left": 0, "top": 0, "right": 0, "bottom": 0},
            )

    def test_with_bounds_keeps_real_geometry(self) -> None:
        with patch.object(android_tool, "_get", return_value=deepcopy(self.payload)) as get:
            result = json.loads(android_tool.android_read_screen(include_bounds=True))
        get.assert_called_once_with("/screen?include_bounds=true")
        self.assertEqual(result["nodes"], self.payload["nodes"])

    def test_empty_screen_is_still_empty(self) -> None:
        with patch.object(
            android_tool,
            "_get",
            return_value={"nodes": [], "nodeCount": 0},
        ):
            result = json.loads(android_tool.android_read_screen())
        self.assertEqual(result["nodes"], [])
        self.assertEqual(result["nodeCount"], 0)


if __name__ == "__main__":
    unittest.main()
