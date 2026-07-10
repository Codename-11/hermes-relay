import json
import os
import unittest
from unittest import mock

from plugin.tools import android_tool


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class AndroidToolDeviceSelectorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.env = mock.patch.dict(
            os.environ,
            {"ANDROID_BRIDGE_URL": "http://relay.local", "ANDROID_BRIDGE_TIMEOUT": "5"},
        )
        self.env.start()

    def tearDown(self) -> None:
        self.env.stop()

    def test_schema_adds_optional_device_to_bridge_tools(self) -> None:
        for tool_name in (
            "android_ping",
            "android_read_screen",
            "android_tap",
            "android_current_app",
            "android_screenshot",
            "android_macro",
        ):
            params = android_tool._SCHEMAS[tool_name]["parameters"]
            self.assertIn("device", params["properties"], tool_name)
            self.assertNotIn("device", params.get("required", []), tool_name)

        self.assertNotIn(
            "device",
            android_tool._SCHEMAS["android_setup"]["parameters"]["properties"],
        )

    @mock.patch("plugin.tools.android_tool.requests.get")
    def test_get_handler_appends_device_query(self, get_mock: mock.Mock) -> None:
        get_mock.return_value = _FakeResponse({"package": "com.onyx"})

        result = json.loads(
            android_tool._HANDLERS["android_current_app"]({"device": "boox"})
        )

        self.assertEqual(result["package"], "com.onyx")
        get_mock.assert_called_once()
        self.assertEqual(
            get_mock.call_args.args[0],
            "http://relay.local/current_app?device=boox",
        )

    @mock.patch("plugin.tools.android_tool.requests.get")
    def test_existing_get_query_uses_ampersand_for_device(self, get_mock: mock.Mock) -> None:
        get_mock.return_value = _FakeResponse({"tree": []})

        android_tool._HANDLERS["android_read_screen"](
            {"include_bounds": True, "device": "phone"}
        )

        self.assertEqual(
            get_mock.call_args.args[0],
            "http://relay.local/screen?include_bounds=true&device=phone",
        )

    @mock.patch("plugin.tools.android_tool.requests.post")
    def test_post_handler_injects_device_body(self, post_mock: mock.Mock) -> None:
        post_mock.return_value = _FakeResponse({"success": True})

        result = json.loads(
            android_tool._HANDLERS["android_tap"](
                {"x": 10, "y": 20, "device": "notemax"}
            )
        )

        self.assertTrue(result["success"])
        self.assertEqual(post_mock.call_args.args[0], "http://relay.local/tap")
        self.assertEqual(
            post_mock.call_args.kwargs["json"],
            {"x": 10, "y": 20, "device": "notemax"},
        )

    @mock.patch("plugin.tools.android_tool.requests.post")
    def test_macro_top_level_device_scopes_nested_steps(self, post_mock: mock.Mock) -> None:
        post_mock.return_value = _FakeResponse({"success": True})

        result = json.loads(
            android_tool._HANDLERS["android_macro"](
                {
                    "device": "boox",
                    "pace_ms": 0,
                    "steps": [
                        {"tool": "android_tap", "args": {"x": 1, "y": 2}},
                        {
                            "tool": "android_tap_text",
                            "args": {"text": "Settings", "device": "phone"},
                        },
                    ],
                }
            )
        )

        self.assertTrue(result["success"])
        self.assertEqual(post_mock.call_args_list[0].kwargs["json"]["device"], "boox")
        self.assertEqual(post_mock.call_args_list[1].kwargs["json"]["device"], "phone")


if __name__ == "__main__":
    unittest.main()
