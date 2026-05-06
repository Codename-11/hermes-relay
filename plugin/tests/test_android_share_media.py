"""
Tests for Android bridge return/share/MMS tool wiring.

These keep the Hermes tool surface, relay HTTP routes, and media attachment
normalization from drifting apart again.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app
from plugin.tools import android_tool


class TestAndroidReturnToHermes(unittest.TestCase):
    def test_posts_return_route(self) -> None:
        with mock.patch.object(
            android_tool, "_post", return_value={"success": True}
        ) as post:
            result = json.loads(android_tool.android_return_to_hermes())

        self.assertTrue(result["success"])
        self.assertEqual(post.call_args.args, ("/return_to_hermes", {}))

    def test_registered(self) -> None:
        self.assertIn("android_return_to_hermes", android_tool._SCHEMAS)
        self.assertIn("android_return_to_hermes", android_tool._HANDLERS)


class TestAndroidShareMedia(unittest.TestCase):
    def test_share_media_marker_passthrough(self) -> None:
        with mock.patch.object(
            android_tool, "_post", return_value={"status": "share_opened"}
        ) as post:
            result = json.loads(
                android_tool.android_share_media(
                    media="MEDIA:hermes-relay://abc123",
                    text="caption",
                    title="Pick app",
                )
            )

        self.assertEqual(result["status"], "share_opened")
        path, payload = post.call_args.args
        self.assertEqual(path, "/share_media")
        self.assertEqual(payload["text"], "caption")
        self.assertEqual(payload["title"], "Pick app")
        self.assertEqual(
            payload["attachments"][0]["media"], "MEDIA:hermes-relay://abc123"
        )

    def test_raw_token_is_normalized_to_media_marker(self) -> None:
        with mock.patch.object(
            android_tool, "_post", return_value={"status": "share_opened"}
        ) as post:
            result = json.loads(android_tool.android_share_media(media_token="tok456"))

        self.assertEqual(result["status"], "share_opened")
        _, payload = post.call_args.args
        self.assertEqual(
            payload["attachments"][0]["media"], "MEDIA:hermes-relay://tok456"
        )

    def test_local_path_is_registered_before_post(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            tmp.write(b"\x89PNG\r\n\x1a\n")
            tmp_path = tmp.name

        try:
            with mock.patch(
                "plugin.relay.client.register_media", return_value="registered-token"
            ) as register_media, mock.patch.object(
                android_tool, "_post", return_value={"status": "share_opened"}
            ) as post:
                result = json.loads(
                    android_tool.android_share_media(
                        path=tmp_path,
                        content_type="image/png",
                        file_name="demo.png",
                    )
                )
        finally:
            Path(tmp_path).unlink(missing_ok=True)

        self.assertEqual(result["status"], "share_opened")
        register_media.assert_called_once_with(
            tmp_path,
            "image/png",
            file_name="demo.png",
        )
        _, payload = post.call_args.args
        self.assertEqual(
            payload["attachments"][0]["media"],
            "MEDIA:hermes-relay://registered-token",
        )
        self.assertEqual(payload["attachments"][0]["content_type"], "image/png")
        self.assertEqual(payload["attachments"][0]["file_name"], "demo.png")

    def test_share_requires_text_or_attachment(self) -> None:
        result = json.loads(android_tool.android_share_media())
        self.assertIn("error", result)
        self.assertIn("path/media attachment or text", result["error"])

    def test_registered(self) -> None:
        self.assertIn("android_share_media", android_tool._SCHEMAS)
        self.assertIn("android_share_media", android_tool._HANDLERS)


class TestAndroidSendMms(unittest.TestCase):
    def test_send_mms_posts_attachments(self) -> None:
        with mock.patch.object(
            android_tool, "_post", return_value={"status": "compose_opened"}
        ) as post:
            result = json.loads(
                android_tool.android_send_mms(
                    to="+15551234567",
                    body="hello",
                    media_token="mms-token",
                    package="com.google.android.apps.messaging",
                )
            )

        self.assertEqual(result["status"], "compose_opened")
        path, payload = post.call_args.args
        self.assertEqual(path, "/send_mms")
        self.assertEqual(payload["to"], "+15551234567")
        self.assertEqual(payload["body"], "hello")
        self.assertEqual(payload["package"], "com.google.android.apps.messaging")
        self.assertEqual(
            payload["attachments"][0]["media"], "MEDIA:hermes-relay://mms-token"
        )

    def test_send_mms_requires_recipient(self) -> None:
        result = json.loads(android_tool.android_send_mms(to="", body="hello"))
        self.assertIn("error", result)
        self.assertIn("missing 'to'", result["error"])

    def test_send_mms_requires_body_or_attachment(self) -> None:
        result = json.loads(android_tool.android_send_mms(to="+15551234567"))
        self.assertIn("error", result)
        self.assertIn("attachment or body", result["error"])

    def test_registered(self) -> None:
        self.assertIn("android_send_mms", android_tool._SCHEMAS)
        self.assertIn("android_send_mms", android_tool._HANDLERS)


class TestRelayBridgeMediaRoutes(unittest.TestCase):
    def test_new_bridge_routes_are_registered(self) -> None:
        app = create_app(RelayConfig())
        routes = {
            (route.method, route.resource.canonical)
            for route in app.router.routes()
            if route.resource is not None
        }

        self.assertIn(("POST", "/return_to_hermes"), routes)
        self.assertIn(("POST", "/share_media"), routes)
        self.assertIn(("POST", "/send_mms"), routes)


if __name__ == "__main__":
    unittest.main()
