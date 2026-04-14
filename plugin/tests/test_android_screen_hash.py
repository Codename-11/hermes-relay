"""
Unit tests for the A5 ``android_screen_hash`` + ``android_diff_screen``
tool handlers in ``plugin.tools.android_tool``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so the suite runs via::

    python -m unittest plugin.tests.test_android_screen_hash

without tripping the pytest-only ``conftest.py`` that imports the
``responses`` package (unavailable in some venvs).

Coverage:

  * ``android_screen_hash`` — happy path return shape.
  * ``android_diff_screen`` — changed=False (equal hashes) and
    changed=True (different hashes) flowed through from the relay.
  * Both tools' error envelopes on HTTP failure.
  * Hash-stability properties — we simulate the underlying relay
    response for three tree states (unchanged / text change / layout
    change) and confirm the tool side faithfully reports what the
    relay reported. The relay's SHA-256 is deterministic so this
    double-checks that the tool doesn't accidentally reshape or re-hash
    the response body.
  * Schema + handler registration shape — both tools land in the
    module-level ``_SCHEMAS`` and ``_HANDLERS`` maps.
"""

from __future__ import annotations

import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make ``import plugin.tools.android_tool`` work when the test runs
# from the repo root without the package being installed. Mirrors
# test_android_phone_status.py's import dance.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin.tools import android_tool  # noqa: E402


# ── Helpers ──────────────────────────────────────────────────────────────────


class _FakeResponse:
    """Minimal ``requests.Response`` stand-in for our mocks."""

    def __init__(self, body: dict, status: int = 200) -> None:
        self._body = body
        self.status_code = status

    def json(self) -> dict:
        return self._body

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


def _hash_tree(fields_per_node: list[str]) -> str:
    """
    Reference hasher used for the stability tests. Matches the Kotlin
    ``ScreenHasher`` logic at the wire level: fields are joined with
    ``|``, nodes are joined with ``\\u001e``, then SHA-256.

    The tool side never actually hashes — it just forwards whatever the
    relay returned. But using the same algorithm here lets us assert
    "two different-looking trees produce different hashes" without
    hand-picking magic hex constants.
    """
    joined = "\u001e".join(fields_per_node)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


# Three synthetic "screens" — minimal fingerprint fields per interesting
# node. These don't have to be realistic; they just need to be distinct
# so the hashes diverge.
TREE_A = [
    "android.widget.TextView|Hello|desc_a|0,0,100,100|com.app:id/title",
    "android.widget.Button|OK||10,200,90,240|com.app:id/ok",
]
TREE_A_TEXT_CHANGED = [
    "android.widget.TextView|Hi there|desc_a|0,0,100,100|com.app:id/title",
    "android.widget.Button|OK||10,200,90,240|com.app:id/ok",
]
TREE_A_LAYOUT_CHANGED = [
    "android.widget.TextView|Hello|desc_a|0,0,100,100|com.app:id/title",
    "android.widget.Button|OK||10,250,90,290|com.app:id/ok",
]


# ── Tests ────────────────────────────────────────────────────────────────────


class TestSchemaAndHandlerRegistration(unittest.TestCase):
    """A5 must actually register in the module-level maps or the
    tool loader will never see it."""

    def test_screen_hash_in_schemas(self) -> None:
        self.assertIn("android_screen_hash", android_tool._SCHEMAS)
        schema = android_tool._SCHEMAS["android_screen_hash"]
        self.assertEqual(schema["name"], "android_screen_hash")
        self.assertEqual(schema["parameters"]["required"], [])

    def test_diff_screen_in_schemas(self) -> None:
        self.assertIn("android_diff_screen", android_tool._SCHEMAS)
        schema = android_tool._SCHEMAS["android_diff_screen"]
        self.assertEqual(schema["name"], "android_diff_screen")
        self.assertEqual(schema["parameters"]["required"], ["previous_hash"])
        self.assertIn(
            "previous_hash", schema["parameters"]["properties"]
        )

    def test_both_in_handlers(self) -> None:
        self.assertIn("android_screen_hash", android_tool._HANDLERS)
        self.assertIn("android_diff_screen", android_tool._HANDLERS)

    def test_schema_handler_parity(self) -> None:
        """Every schema must have a handler and vice-versa."""
        self.assertEqual(
            set(android_tool._SCHEMAS.keys()),
            set(android_tool._HANDLERS.keys()),
        )


class TestAndroidScreenHashTool(unittest.TestCase):
    def test_happy_path_return_shape(self) -> None:
        expected_hash = _hash_tree(TREE_A)
        fake = _FakeResponse(
            {"hash": expected_hash, "node_count": 2, "truncated": False}
        )
        with mock.patch.object(android_tool.requests, "get", return_value=fake):
            raw = android_tool.android_screen_hash()
        parsed = json.loads(raw)
        self.assertEqual(parsed["hash"], expected_hash)
        self.assertEqual(parsed["node_count"], 2)
        self.assertIs(parsed["truncated"], False)

    def test_forwards_truncated_flag(self) -> None:
        fake = _FakeResponse(
            {"hash": "a" * 64, "node_count": 512, "truncated": True}
        )
        with mock.patch.object(android_tool.requests, "get", return_value=fake):
            parsed = json.loads(android_tool.android_screen_hash())
        self.assertIs(parsed["truncated"], True)
        self.assertEqual(parsed["node_count"], 512)

    def test_http_error_returns_error_envelope(self) -> None:
        def boom(*_args, **_kwargs):  # type: ignore[no-untyped-def]
            raise RuntimeError("connection refused")

        with mock.patch.object(android_tool.requests, "get", side_effect=boom):
            parsed = json.loads(android_tool.android_screen_hash())
        self.assertIn("error", parsed)
        self.assertIn("connection refused", parsed["error"])

    def test_stability_unchanged_tree(self) -> None:
        """
        Two calls over an unchanged tree must return the same hash.
        Mocked twice with the identical body to simulate the relay
        re-hashing the same tree and getting the same answer.
        """
        h = _hash_tree(TREE_A)
        fake = _FakeResponse({"hash": h, "node_count": 2, "truncated": False})
        with mock.patch.object(android_tool.requests, "get", return_value=fake):
            first = json.loads(android_tool.android_screen_hash())
            second = json.loads(android_tool.android_screen_hash())
        self.assertEqual(first["hash"], second["hash"])


class TestAndroidDiffScreenTool(unittest.TestCase):
    def test_changed_false_on_identical_hash(self) -> None:
        h = _hash_tree(TREE_A)
        fake = _FakeResponse(
            {"changed": False, "hash": h, "node_count": 2, "truncated": False}
        )
        with mock.patch.object(android_tool.requests, "post", return_value=fake) as post:
            parsed = json.loads(android_tool.android_diff_screen(h))
        self.assertIs(parsed["changed"], False)
        self.assertEqual(parsed["hash"], h)
        # Tool side must forward the prior hash in the body.
        _, kwargs = post.call_args
        self.assertEqual(kwargs["json"], {"previous_hash": h})

    def test_changed_true_on_text_change(self) -> None:
        old = _hash_tree(TREE_A)
        new = _hash_tree(TREE_A_TEXT_CHANGED)
        self.assertNotEqual(old, new)
        fake = _FakeResponse(
            {"changed": True, "hash": new, "node_count": 2, "truncated": False}
        )
        with mock.patch.object(android_tool.requests, "post", return_value=fake):
            parsed = json.loads(android_tool.android_diff_screen(old))
        self.assertIs(parsed["changed"], True)
        self.assertEqual(parsed["hash"], new)

    def test_changed_true_on_layout_change(self) -> None:
        old = _hash_tree(TREE_A)
        new = _hash_tree(TREE_A_LAYOUT_CHANGED)
        self.assertNotEqual(old, new)
        fake = _FakeResponse(
            {"changed": True, "hash": new, "node_count": 2, "truncated": False}
        )
        with mock.patch.object(android_tool.requests, "post", return_value=fake):
            parsed = json.loads(android_tool.android_diff_screen(old))
        self.assertIs(parsed["changed"], True)
        self.assertEqual(parsed["hash"], new)
        self.assertEqual(parsed["node_count"], 2)

    def test_http_error_returns_error_envelope(self) -> None:
        def boom(*_args, **_kwargs):  # type: ignore[no-untyped-def]
            raise RuntimeError("relay down")

        with mock.patch.object(android_tool.requests, "post", side_effect=boom):
            parsed = json.loads(android_tool.android_diff_screen("deadbeef"))
        self.assertIn("error", parsed)
        self.assertIn("relay down", parsed["error"])


class TestHashReferenceStability(unittest.TestCase):
    """
    These tests don't touch the tool at all — they just assert that
    the reference hasher in _hash_tree produces the expected stability
    / change signals. Protects the test-bed itself from silently
    drifting, and documents the invariant the Kotlin side must match.
    """

    def test_identical_trees_hash_equal(self) -> None:
        self.assertEqual(_hash_tree(TREE_A), _hash_tree(list(TREE_A)))

    def test_text_change_produces_different_hash(self) -> None:
        self.assertNotEqual(_hash_tree(TREE_A), _hash_tree(TREE_A_TEXT_CHANGED))

    def test_layout_change_produces_different_hash(self) -> None:
        self.assertNotEqual(
            _hash_tree(TREE_A), _hash_tree(TREE_A_LAYOUT_CHANGED)
        )

    def test_hash_is_64_hex_chars(self) -> None:
        h = _hash_tree(TREE_A)
        self.assertEqual(len(h), 64)
        int(h, 16)  # must parse as hex — raises ValueError otherwise


if __name__ == "__main__":
    unittest.main()
