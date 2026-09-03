import json
import os
from pathlib import Path
from unittest import mock
import responses
import pytest

# Import tool functions directly (not via registry)
from plugin.tools import android_tool
from plugin.tools.android_tool import (
    android_ping,
    android_read_screen,
    android_tap,
    android_tap_text,
    android_long_press,
    android_type,
    android_swipe,
    android_open_app,
    android_press_key,
    android_screenshot,
    android_scroll,
    android_wait,
    android_get_apps,
    android_current_app,
    android_setup,
    _SCHEMAS,
    _HANDLERS,
    _check_requirements,
)


class TestSchemas:
    def test_all_tools_have_schemas(self):
        # Baseline 14 Phase-1 tools + Wave 2/3 v0.4 additions.
        # Relaxed to >= so parallel waves that land independently
        # don't have to coordinate intermediate exact counts.
        assert len(_SCHEMAS) >= 14

    def test_all_tools_have_handlers(self):
        assert len(_HANDLERS) >= 14

    def test_schema_names_match_handler_names(self):
        assert set(_SCHEMAS.keys()) == set(_HANDLERS.keys())

    def test_all_schemas_have_required_fields(self):
        for name, schema in _SCHEMAS.items():
            assert "name" in schema, f"{name} missing 'name'"
            assert "description" in schema, f"{name} missing 'description'"
            assert "parameters" in schema, f"{name} missing 'parameters'"


class TestPing:
    @responses.activate
    def test_ping_success(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/ping",
            json={"status": "ok", "accessibilityService": True, "version": "0.1.0"},
        )
        result = json.loads(android_ping())
        assert result["status"] == "ok"
        assert result["bridge"]["accessibilityService"] is True

    @responses.activate
    def test_ping_failure(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/ping",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_ping())
        assert result["status"] == "error"


class TestRequirements:
    @responses.activate
    def test_requirements_true_for_device_control_phone(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/bridge/status",
            json={
                "phone_connected": True,
                "bridge": {"device_control_supported": True},
            },
        )
        assert _check_requirements() is True

    @responses.activate
    def test_requirements_false_for_google_play_bridge_core(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/bridge/status",
            json={
                "phone_connected": True,
                "bridge": {"device_control_supported": False},
            },
        )
        assert _check_requirements() is False


class TestReadScreen:
    @responses.activate
    def test_read_screen(self, bridge_url):
        tree = [{"nodeId": "n1", "text": "Hello", "clickable": True}]
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"tree": tree, "count": 1},
        )
        result = json.loads(android_read_screen())
        assert result["tree"][0]["text"] == "Hello"

    @responses.activate
    def test_read_screen_with_bounds(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"tree": [], "count": 0},
        )
        result = json.loads(android_read_screen(include_bounds=True))
        assert "tree" in result


class TestTap:
    @responses.activate
    def test_tap_by_coordinates(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap",
            json={"success": True, "message": "Tapped (100, 200)"},
        )
        result = json.loads(android_tap(x=100, y=200))
        assert result["success"] is True

    @responses.activate
    def test_tap_by_node_id(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap",
            json={"success": True, "message": "Tapped node n1"},
        )
        result = json.loads(android_tap(node_id="n1"))
        assert result["success"] is True

    def test_tap_no_args(self):
        result = json.loads(android_tap())
        assert "error" in result


class TestTapText:
    @responses.activate
    def test_tap_text(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap_text",
            json={"success": True, "message": "Tapped 'Continue'"},
        )
        result = json.loads(android_tap_text("Continue"))
        assert result["success"] is True

    @responses.activate
    def test_tap_text_exact(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap_text",
            json={"success": True},
        )
        result = json.loads(android_tap_text("OK", exact=True))
        assert result["success"] is True


class TestType:
    @responses.activate
    def test_type_text(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/type",
            json={"success": True, "message": "Typed text"},
        )
        result = json.loads(android_type("hello world"))
        assert result["success"] is True

    @responses.activate
    def test_type_clear_first(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/type",
            json={"success": True},
        )
        result = json.loads(android_type("new text", clear_first=True))
        assert result["success"] is True


class TestSwipe:
    @responses.activate
    def test_swipe(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/swipe",
            json={"success": True, "message": "Swiped up (medium)"},
        )
        result = json.loads(android_swipe("up"))
        assert result["success"] is True

    @responses.activate
    def test_swipe_long(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/swipe",
            json={"success": True},
        )
        result = json.loads(android_swipe("down", distance="long"))
        assert result["success"] is True


class TestOpenApp:
    @responses.activate
    def test_open_app(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/open_app",
            json={"success": True, "message": "Opening com.ubercab"},
        )
        result = json.loads(android_open_app("com.ubercab"))
        assert result["success"] is True


class TestPressKey:
    @responses.activate
    def test_press_key(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/press_key",
            json={"success": True, "message": "Pressed back"},
        )
        result = json.loads(android_press_key("back"))
        assert result["success"] is True


class TestScreenshot:
    @responses.activate
    def test_screenshot(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/screenshot",
            json={"image": "aGVsbG8=", "width": 1080, "height": 1920},
        )
        with mock.patch("plugin.relay.client.register_media", return_value="shot-token"):
            result = android_screenshot()
        assert "Screenshot captured (1080x1920)" in result
        assert "MEDIA:hermes-relay://shot-token" in result


class TestScroll:
    @responses.activate
    def test_scroll(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/scroll",
            json={"success": True},
        )
        result = json.loads(android_scroll("down"))
        assert result["success"] is True

    @responses.activate
    def test_scroll_with_node(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/scroll",
            json={"success": True},
        )
        result = json.loads(android_scroll("up", node_id="scroll_view_1"))
        assert result["success"] is True


class TestWait:
    @responses.activate
    def test_wait_found(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/wait",
            json={"success": True, "message": "Element found"},
        )
        result = json.loads(android_wait(text="Loading complete"))
        assert result["success"] is True

    @responses.activate
    def test_wait_timeout(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/wait",
            json={"success": False, "message": "Timeout"},
        )
        result = json.loads(android_wait(text="Never appears", timeout_ms=1000))
        assert result["success"] is False


class TestGetApps:
    @responses.activate
    def test_get_apps(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/apps",
            json={"apps": [{"packageName": "com.ubercab", "label": "Uber"}], "count": 1},
        )
        result = json.loads(android_get_apps())
        assert result["count"] == 1


class TestCurrentApp:
    @responses.activate
    def test_current_app(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/current_app",
            json={"package": "com.ubercab", "className": "MainActivity"},
        )
        result = json.loads(android_current_app())
        assert result["package"] == "com.ubercab"


class TestSetup:
    @pytest.fixture(autouse=True)
    def _isolate_home(self, monkeypatch, tmp_path):
        """Keep android_setup away from the developer's real ~/.hermes/.env.

        android_setup persists ANDROID_BRIDGE_* by writing the host env file
        (via hermes_cli.config.save_env_value when importable, otherwise
        Path.home()/".hermes"/".env"). Without this fixture the suite
        overwrites the real paired session token on the machine running the
        tests — which silently 401s every subsequent android_* call.
        """
        fake_home = tmp_path / "home"
        (fake_home / ".hermes").mkdir(parents=True)
        monkeypatch.setattr(Path, "home", classmethod(lambda cls: fake_home))
        monkeypatch.setenv("HERMES_HOME", str(fake_home / ".hermes"))
        monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
        monkeypatch.delenv("ANDROID_BRIDGE_URL", raising=False)
        android_tool._token_cache = (0.0, None)
        yield
        android_tool._token_cache = (0.0, None)

    @responses.activate
    def test_setup_saves_config(self, monkeypatch):
        """android_setup saves pairing code and sets env vars."""
        # Mock the public IP detection
        responses.add(responses.GET, "https://api.ipify.org", body="1.2.3.4")
        # android_relay won't be importable in test context, so setup returns error
        result = json.loads(android_setup("ABC123"))
        # Config should be saved regardless of relay import
        assert os.environ.get("ANDROID_BRIDGE_TOKEN") == "ABC123"
        assert "localhost" in os.environ.get("ANDROID_BRIDGE_URL", "")

    @responses.activate
    def test_setup_accepts_legacy_pairing_code_kwarg(self, monkeypatch):
        """The published schema key `pairing_code` must not raise TypeError.

        Regression: the schema advertised a required `pairing_code` while the
        function signature had been renamed to `bridge_session_token`, so
        every schema-conformant call died with
        `TypeError: unexpected keyword argument 'pairing_code'`.
        """
        responses.add(responses.GET, "https://api.ipify.org", body="1.2.3.4")
        result = json.loads(_HANDLERS["android_setup"]({"pairing_code": "LEGACY1"}))
        assert result["status"] != "error" or "token" not in result.get("message", "")
        assert os.environ.get("ANDROID_BRIDGE_TOKEN") == "LEGACY1"

    @responses.activate
    def test_setup_accepts_canonical_kwarg(self, monkeypatch):
        responses.add(responses.GET, "https://api.ipify.org", body="1.2.3.4")
        _HANDLERS["android_setup"]({"bridge_session_token": "CANON1"})
        assert os.environ.get("ANDROID_BRIDGE_TOKEN") == "CANON1"

    def test_setup_without_token_returns_structured_error(self):
        result = json.loads(_HANDLERS["android_setup"]({}))
        assert result["status"] == "error"
        assert "bridge_session_token" in result["message"]

    def test_setup_schema_has_no_required_key(self):
        """Either spelling is valid, so neither may be schema-required."""
        params = _SCHEMAS["android_setup"]["parameters"]
        assert params.get("required") == []
        assert "bridge_session_token" in params["properties"]
        assert "pairing_code" in params["properties"]


class TestBridgeTokenResolution:
    """`_bridge_token` must survive a token written after process start."""

    def _clear(self, monkeypatch):
        monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
        android_tool._token_cache = (0.0, None)

    def test_env_var_wins(self, monkeypatch, tmp_path):
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "from-env")
        android_tool._token_cache = (0.0, None)
        (tmp_path / ".env").write_text("ANDROID_BRIDGE_TOKEN=from-file\n")
        assert android_tool._bridge_token() == "from-env"

    def test_falls_back_to_env_file(self, monkeypatch, tmp_path):
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        self._clear(monkeypatch)
        (tmp_path / ".env").write_text(
            "# comment\nOTHER=x\nANDROID_BRIDGE_TOKEN=\"from-file\"\n"
        )
        assert android_tool._bridge_token() == "from-file"

    def test_falls_back_to_sessions_file(self, monkeypatch, tmp_path):
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        self._clear(monkeypatch)
        (tmp_path / "hermes-relay-sessions.json").write_text(
            json.dumps({
                "sessions": [
                    {"token": "older", "last_seen": 1.0},
                    {"token": "newest", "last_seen": 2.0},
                ]
            })
        )
        assert android_tool._bridge_token() == "newest"

    def test_returns_none_when_nothing_configured(self, monkeypatch, tmp_path):
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        self._clear(monkeypatch)
        assert android_tool._bridge_token() is None

    def test_auth_header_uses_resolved_token(self, monkeypatch, tmp_path):
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        self._clear(monkeypatch)
        (tmp_path / ".env").write_text("ANDROID_BRIDGE_TOKEN=hdr-token\n")
        assert android_tool._auth_headers() == {
            "Authorization": "Bearer hdr-token"
        }
