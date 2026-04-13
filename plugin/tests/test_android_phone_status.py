"""
Unit tests for ``plugin.tools.android_phone_status`` and ``plugin.status``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_phone_status

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * ``android_phone_status`` tool — success path, 503 (no phone),
    connection refused, other HTTP error, bad JSON body.
  * ``plugin.status.fetch_status`` — same three canonical paths plus
    defensive non-object JSON.
  * Schema sanity — tool registers with zero-arg parameters.
"""

from __future__ import annotations

import io
import json
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_phone_status` work when the test
# runs from the repo root without the package being installed.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin import status as status_cli  # noqa: E402
from plugin.tools import android_phone_status as phone_status  # noqa: E402


# ── Helpers ──────────────────────────────────────────────────────────────────


def _sample_status() -> dict:
    """The canonical /bridge/status shape from CLAUDE.md."""
    return {
        "phone_connected": True,
        "last_seen_seconds_ago": 4,
        "device": {
            "name": "SM-S921U",
            "battery_percent": 78,
            "screen_on": True,
            "current_app": "com.android.chrome",
        },
        "bridge": {
            "master_enabled": True,
            "accessibility_granted": True,
            "screen_capture_granted": True,
            "overlay_granted": True,
            "notification_listener_granted": True,
        },
        "safety": {
            "blocklist_count": 30,
            "destructive_verbs_count": 12,
            "auto_disable_minutes": 30,
            "auto_disable_at_ms": None,
        },
    }


class _FakeResponse:
    """Minimal context-manager stand-in for urllib.request.urlopen."""

    def __init__(self, body: bytes, status: int = 200) -> None:
        self._body = body
        self.status = status

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc_info) -> None:
        return None


def _http_error(code: int, body: bytes = b"") -> urllib.error.HTTPError:
    """Build a realistic HTTPError whose .read() returns ``body``."""
    return urllib.error.HTTPError(
        url="http://127.0.0.1:8767/bridge/status",
        code=code,
        msg="Service Unavailable" if code == 503 else "HTTP Error",
        hdrs=None,  # type: ignore[arg-type]
        fp=io.BytesIO(body),
    )


# ── Tool tests ───────────────────────────────────────────────────────────────


class TestAndroidPhoneStatusTool(unittest.TestCase):
    def test_success_returns_ok_envelope(self) -> None:
        sample = _sample_status()
        body = json.dumps(sample).encode("utf-8")
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            return_value=_FakeResponse(body),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "ok")
        self.assertIsNone(envelope["error"])
        self.assertEqual(envelope["phone_status"], sample)
        self.assertTrue(envelope["phone_status"]["phone_connected"])
        self.assertEqual(
            envelope["phone_status"]["device"]["name"], "SM-S921U"
        )

    def test_503_returns_ok_with_phone_not_connected(self) -> None:
        body = json.dumps(
            {"phone_connected": False, "error": "no phone connected"}
        ).encode("utf-8")
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            side_effect=_http_error(503, body),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        # 503 from the relay is not an error from the agent's
        # perspective — the phone just isn't online.
        self.assertEqual(envelope["status"], "ok")
        self.assertIsNone(envelope["error"])
        self.assertIsNotNone(envelope["phone_status"])
        self.assertFalse(envelope["phone_status"]["phone_connected"])
        self.assertEqual(
            envelope["phone_status"]["error"], "no phone connected"
        )

    def test_503_with_empty_body_still_produces_stable_envelope(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            side_effect=_http_error(503, b""),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "ok")
        self.assertIsNotNone(envelope["phone_status"])
        self.assertFalse(envelope["phone_status"]["phone_connected"])

    def test_connection_refused_returns_error_envelope(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            side_effect=urllib.error.URLError("Connection refused"),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "error")
        self.assertIsNone(envelope["phone_status"])
        self.assertEqual(envelope["error"], "relay unreachable")

    def test_os_error_on_socket_returns_error_envelope(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            side_effect=OSError("network unreachable"),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "error")
        self.assertEqual(envelope["error"], "relay unreachable")

    def test_other_http_error_returns_error_envelope(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            side_effect=_http_error(500, b"boom"),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "error")
        self.assertIsNone(envelope["phone_status"])
        self.assertIn("500", envelope["error"])

    def test_bad_json_body_returns_error_envelope(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            return_value=_FakeResponse(b"not-json"),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "error")
        self.assertIsNone(envelope["phone_status"])
        self.assertIn("non-JSON", envelope["error"])

    def test_non_object_json_returns_error_envelope(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            return_value=_FakeResponse(b"[1,2,3]"),
        ):
            raw = phone_status.android_phone_status()

        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "error")
        self.assertIsNone(envelope["phone_status"])

    def test_schema_registers_zero_arg_tool(self) -> None:
        schema = phone_status._SCHEMAS["android_phone_status"]
        self.assertEqual(schema["name"], "android_phone_status")
        self.assertIn("description", schema)
        self.assertEqual(schema["parameters"]["type"], "object")
        self.assertEqual(schema["parameters"]["required"], [])
        self.assertEqual(schema["parameters"]["properties"], {})

    def test_handler_invokes_function(self) -> None:
        with mock.patch(
            "plugin.tools.android_phone_status.urllib.request.urlopen",
            return_value=_FakeResponse(json.dumps(_sample_status()).encode()),
        ):
            raw = phone_status._HANDLERS["android_phone_status"]({})
        envelope = json.loads(raw)
        self.assertEqual(envelope["status"], "ok")


# ── CLI tests ────────────────────────────────────────────────────────────────


class TestStatusCliFetch(unittest.TestCase):
    def test_fetch_status_success(self) -> None:
        sample = _sample_status()
        body = json.dumps(sample).encode("utf-8")
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            return_value=_FakeResponse(body),
        ):
            code, data, err = status_cli.fetch_status(8767)
        self.assertEqual(code, status_cli.EXIT_OK)
        self.assertIsNone(err)
        self.assertEqual(data, sample)

    def test_fetch_status_503_maps_to_no_phone(self) -> None:
        body = json.dumps(
            {"phone_connected": False, "error": "no phone connected"}
        ).encode("utf-8")
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            side_effect=_http_error(503, body),
        ):
            code, data, err = status_cli.fetch_status(8767)
        self.assertEqual(code, status_cli.EXIT_NO_PHONE)
        self.assertIsNotNone(data)
        assert data is not None  # for mypy-style narrowing
        self.assertFalse(data["phone_connected"])
        self.assertEqual(err, "no phone connected")

    def test_fetch_status_connection_refused(self) -> None:
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            side_effect=urllib.error.URLError("Connection refused"),
        ):
            code, data, err = status_cli.fetch_status(8767)
        self.assertEqual(code, status_cli.EXIT_RELAY_UNREACHABLE)
        self.assertIsNone(data)
        assert err is not None
        self.assertIn("unreachable", err)

    def test_fetch_status_non_object_json(self) -> None:
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            return_value=_FakeResponse(b"42"),
        ):
            code, data, err = status_cli.fetch_status(8767)
        self.assertEqual(code, status_cli.EXIT_RELAY_UNREACHABLE)
        self.assertIsNone(data)

    def test_main_json_mode_success_exits_zero(self) -> None:
        sample = _sample_status()
        body = json.dumps(sample).encode("utf-8")
        buf = io.StringIO()
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            return_value=_FakeResponse(body),
        ), mock.patch("plugin.status.sys.stdout", buf):
            rc = status_cli.main(["--json"])
        self.assertEqual(rc, status_cli.EXIT_OK)
        emitted = json.loads(buf.getvalue().strip())
        self.assertEqual(emitted, sample)

    def test_main_json_mode_unreachable_exits_one(self) -> None:
        buf = io.StringIO()
        err_buf = io.StringIO()
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            side_effect=urllib.error.URLError("Connection refused"),
        ), mock.patch("plugin.status.sys.stdout", buf), mock.patch(
            "plugin.status.sys.stderr", err_buf
        ):
            rc = status_cli.main(["--json"])
        self.assertEqual(rc, status_cli.EXIT_RELAY_UNREACHABLE)
        emitted = json.loads(buf.getvalue().strip())
        self.assertFalse(emitted["phone_connected"])

    def test_main_json_mode_no_phone_exits_two(self) -> None:
        body = json.dumps(
            {"phone_connected": False, "error": "no phone connected"}
        ).encode("utf-8")
        buf = io.StringIO()
        with mock.patch(
            "plugin.status.urllib.request.urlopen",
            side_effect=_http_error(503, body),
        ), mock.patch("plugin.status.sys.stdout", buf):
            rc = status_cli.main(["--json"])
        self.assertEqual(rc, status_cli.EXIT_NO_PHONE)

    def test_render_status_block_contains_key_fields(self) -> None:
        sample = _sample_status()
        palette = status_cli._Palette(enabled=False)
        text = status_cli.render_status_block(sample, palette)
        self.assertIn("SM-S921U", text)
        self.assertIn("78%", text)
        self.assertIn("com.android.chrome", text)
        self.assertIn("Blocklist", text)
        self.assertIn("30 packages", text)
        self.assertIn("Destructive verbs", text)
        self.assertIn("Accessibility", text)

    def test_render_status_block_disconnected_short_circuits(self) -> None:
        palette = status_cli._Palette(enabled=False)
        text = status_cli.render_status_block(
            {"phone_connected": False, "error": "no phone connected"},
            palette,
        )
        self.assertIn("no phone connected", text)
        # Shouldn't render any device / bridge / safety sections
        self.assertNotIn("Device", text)
        self.assertNotIn("Bridge", text)
        self.assertNotIn("Safety", text)


if __name__ == "__main__":
    unittest.main()
