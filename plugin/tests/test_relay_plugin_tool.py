from __future__ import annotations

import os
import tempfile
import unittest
from unittest.mock import patch

from plugin.tools.relay_plugin_tool import (
    relay_plugin_draft,
    relay_plugin_list,
    relay_plugin_publish,
    relay_plugin_remove,
)


class RelayPluginToolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = patch.dict(os.environ, {"HERMES_HOME": self.temp.name})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.document = {
            "schemaVersion": 1,
            "pages": [
                {
                    "id": "home",
                    "title": {"type": "literal", "value": "Tool Page"},
                    "content": {
                        "type": "text",
                        "id": "message",
                        "text": {"type": "literal", "value": "Ready"},
                    },
                }
            ],
        }

    def test_agent_tool_lifecycle(self) -> None:
        drafted = relay_plugin_draft("tool-page", "Tool Page", self.document)
        self.assertEqual("draft", drafted["status"])
        self.assertEqual("tool-page", relay_plugin_list()["plugins"][0]["id"])
        publish = relay_plugin_publish("tool-page")
        self.assertTrue(publish["approval_required"])
        self.assertEqual("draft", relay_plugin_list()["plugins"][0]["status"])
        self.assertTrue(relay_plugin_remove("tool-page")["ok"])

    def test_invalid_document_returns_structured_error(self) -> None:
        result = relay_plugin_draft("bad", "Bad", {"schemaVersion": 1, "pages": []})
        self.assertIn("error", result)

    def test_persistent_draft_removal_requires_user_approval(self) -> None:
        relay_plugin_draft(
            "persistent-page",
            "Persistent",
            self.document,
            lifecycle="persistent",
        )

        result = relay_plugin_remove("persistent-page")

        self.assertTrue(result["approval_required"])
        self.assertEqual("persistent-page", relay_plugin_list()["plugins"][0]["id"])


if __name__ == "__main__":
    unittest.main()
