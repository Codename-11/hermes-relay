from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from plugin.mobile_plugin_store import (
    MobilePluginNotFoundError,
    MobilePluginStore,
    MobilePluginStoreError,
)


def _document(label: str = "Hello") -> dict:
    return {
        "schemaVersion": 1,
        "pages": [
            {
                "id": "home",
                "title": {"type": "literal", "value": label},
                "content": {
                    "type": "text",
                    "id": "welcome",
                    "text": {"type": "literal", "value": label},
                },
            }
        ],
    }


class MobilePluginStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = MobilePluginStore(Path(self.temp.name) / "mobile-plugins")

    def test_draft_manifest_page_publish_remove_lifecycle(self) -> None:
        draft = self.store.draft(
            "daily-brief",
            title="Daily Brief",
            description="A reactive morning page",
            document=_document(),
        )
        self.assertEqual("draft", draft["status"])
        self.assertEqual("session", draft["lifecycle"])

        manifest = self.store.manifest()
        self.assertEqual("hermes-relay", manifest["id"])
        contribution = manifest["contributions"][0]
        self.assertEqual("Draft: Daily Brief", contribution["title"])
        self.assertEqual("mobile/pages/daily-brief", contribution["document"]["path"])
        self.assertEqual(_document(), self.store.get("daily-brief")["document"])

        published = self.store.publish("daily-brief")
        self.assertEqual("published", published["status"])
        self.assertEqual("Daily Brief", self.store.manifest()["contributions"][0]["title"])

        self.assertEqual({"ok": True, "id": "daily-brief"}, self.store.remove("daily-brief"))
        self.assertEqual([], self.store.list())
        with self.assertRaises(MobilePluginNotFoundError):
            self.store.get("daily-brief")

    def test_rejects_traversal_invalid_schema_and_empty_pages(self) -> None:
        for plugin_id in ("../outside", "nested/name", "x\\y", ".hidden"):
            with self.subTest(plugin_id=plugin_id):
                with self.assertRaises(MobilePluginStoreError):
                    self.store.draft(plugin_id, title="Bad", description="", document=_document())

        with self.assertRaisesRegex(MobilePluginStoreError, "schemaVersion"):
            self.store.draft(
                "bad-schema",
                title="Bad",
                description="",
                document={"schemaVersion": 2, "pages": [{}]},
            )
        with self.assertRaisesRegex(MobilePluginStoreError, "1 to 32"):
            self.store.draft(
                "no-pages",
                title="Bad",
                description="",
                document={"schemaVersion": 1, "pages": []},
            )

    def test_listing_omits_document_payload(self) -> None:
        self.store.draft("compact", title="Compact", description="", document=_document())
        self.assertNotIn("document", self.store.list()[0])

    def test_agent_draft_cannot_replace_a_published_plugin(self) -> None:
        draft = self.store.draft(
            "protected",
            title="Protected",
            description="",
            document=_document("First"),
        )
        self.store.publish("protected", expected_digest=draft["digest"])

        with self.assertRaisesRegex(MobilePluginStoreError, "cannot be replaced"):
            self.store.draft(
                "protected",
                title="Changed",
                description="",
                document=_document("Changed"),
            )

        self.assertEqual("published", self.store.get("protected")["status"])
        self.assertEqual(_document("First"), self.store.get("protected")["document"])

    def test_rejects_embedded_backend_action_requests(self) -> None:
        document = _document()
        document["pages"][0]["content"] = {
            "type": "button",
            "id": "privileged-action",
            "label": {"type": "literal", "value": "Enable"},
            "action": {
                "id": "enable",
                "request": {
                    "method": "POST",
                    "path": "remote-access/tailscale/enable",
                },
            },
        }

        with self.assertRaisesRegex(MobilePluginStoreError, "action.request"):
            self.store.draft(
                "unsafe-action",
                title="Unsafe",
                description="",
                document=document,
            )

    def test_rejects_symbolic_link_entries(self) -> None:
        self.store.root.mkdir(parents=True)
        outside = Path(self.temp.name) / "outside.json"
        outside.write_text("{}", encoding="utf-8")
        link = self.store.root / "linked.json"
        try:
            link.symlink_to(outside)
        except OSError as exc:
            self.skipTest(f"symbolic links unavailable: {exc}")

        with self.assertRaisesRegex(MobilePluginStoreError, "symbolic-link"):
            self.store.get("linked")


if __name__ == "__main__":
    unittest.main()
