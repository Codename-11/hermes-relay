import json
import os
import responses
import pytest

# Import tool functions directly (not via registry)
from tools.android_tool import (
    android_ping,
    android_read_screen,
    android_tap,
    android_tap_text,
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
)


class TestSchemas:
    def test_all_14_tools_have_schemas(self):
        assert len(_SCHEMAS) == 14

    def test_all_14_tools_have_handlers(self):
        assert len(_HANDLERS) == 14

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
            json={"image": "base64data", "width": 1080, "height": 1920},
        )
        result = json.loads(android_screenshot())
        assert "image" in result


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
