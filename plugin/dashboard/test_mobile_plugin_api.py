from __future__ import annotations

import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from plugin.dashboard import plugin_api


def _document() -> dict:
    return {
        "schemaVersion": 1,
        "pages": [
            {
                "id": "home",
                "title": {"type": "literal", "value": "Status"},
                "content": {
                    "type": "text",
                    "id": "status",
                    "text": {"type": "literal", "value": "Ready"},
                },
            }
        ],
    }


class MobilePluginApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = patch.dict(os.environ, {"HERMES_HOME": self.temp.name})
        self.env.start()
        self.addCleanup(self.env.stop)
        app = FastAPI()
        app.include_router(plugin_api.router)
        self.client = TestClient(app)

    def test_draft_manifest_page_promote_list_and_remove(self) -> None:
        response = self.client.put(
            "/mobile/plugins/system-status/draft",
            json={"title": "System Status", "description": "Live health", "document": _document()},
        )
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("draft", response.json()["status"])
        draft_digest = response.json()["digest"]

        manifest = self.client.get("/mobile/manifest").json()
        self.assertEqual("hermes-relay", manifest["id"])
        draft = next(c for c in manifest["contributions"] if c["id"] == "system-status")
        self.assertEqual("draft", draft["status"])
        loaded_document = self.client.get("/mobile/pages/system-status").json()
        self.assertEqual(1, loaded_document.pop("host_revision"))
        self.assertEqual(_document(), loaded_document)

        promoted = self.client.post(
            "/mobile/plugins/system-status/promote",
            json={"expected_digest": draft_digest},
        )
        self.assertEqual("published", promoted.json()["status"])
        published_digest = promoted.json()["digest"]
        listing = self.client.get("/mobile/plugins").json()["plugins"]
        self.assertEqual("published", listing[0]["status"])
        self.assertNotIn("document", listing[0])

        removed = self.client.post(
            "/mobile/plugins/system-status/remove",
            json={"expected_digest": published_digest},
        )
        self.assertEqual({"ok": True, "id": "system-status"}, removed.json())
        remaining = self.client.get("/mobile/manifest").json()["contributions"]
        self.assertEqual(["git"], [c["id"] for c in remaining])

    def test_manifest_exposes_static_git_page(self) -> None:
        manifest = self.client.get("/mobile/manifest").json()
        git = next(c for c in manifest["contributions"] if c["id"] == "git")
        self.assertEqual("page", git["surface"])
        self.assertEqual("mobile/pages/git", git["document"]["path"])
        # The Git page document is a GET-only read surface; it must not carry
        # any action.request (which would require the write grant).
        page = self.client.get("/mobile/pages/git").json()
        self.assertNotIn("action", str(page))

    def test_git_page_document_is_served(self) -> None:
        response = self.client.get("/mobile/pages/git")
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual(1, body["schemaVersion"])
        self.assertEqual("git", body["pages"][0]["id"])
        self.assertEqual(1, body["host_revision"])

    def test_traversal_and_bad_document_are_rejected(self) -> None:
        traversal = self.client.put(
            "/mobile/plugins/..%5Coutside/draft",
            json={"title": "Bad", "document": _document()},
        )
        self.assertEqual(400, traversal.status_code)

        bad_document = self.client.put(
            "/mobile/plugins/bad/draft",
            json={"title": "Bad", "document": {"schemaVersion": 1, "pages": []}},
        )
        self.assertEqual(400, bad_document.status_code)

    def test_promote_rejects_a_stale_review_digest(self) -> None:
        first = self.client.put(
            "/mobile/plugins/changing/draft",
            json={"title": "First", "document": _document()},
        ).json()
        self.client.put(
            "/mobile/plugins/changing/draft",
            json={"title": "Changed", "document": _document()},
        )

        response = self.client.post(
            "/mobile/plugins/changing/promote",
            json={"expected_digest": first["digest"]},
        )

        self.assertEqual(409, response.status_code)
        listing = self.client.get("/mobile/plugins").json()["plugins"]
        self.assertEqual("draft", listing[0]["status"])


if __name__ == "__main__":
    unittest.main()
