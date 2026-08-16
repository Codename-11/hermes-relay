"""Two-profile registration fixtures for HRUI-081 and HRUI-161."""

from __future__ import annotations

from typing import Any

import plugin
from plugin.tools.android_tool import _SCHEMAS as ANDROID_SCHEMAS
from plugin.tools.desktop_tool import _SCHEMAS as DESKTOP_SCHEMAS
from plugin.tools.relay_plugin_tool import _SCHEMAS as RELAY_SCHEMAS


class _OwnedContext:
    """Small ownership-ledger stand-in; registries never cross contexts."""

    def __init__(self, profile: str) -> None:
        self.profile = profile
        self.tools: dict[str, dict[str, Any]] = {}
        self.commands: dict[str, dict[str, Any]] = {}
        self.hooks: list[tuple[str, Any]] = []
        self.platforms: dict[str, dict[str, Any]] = {}
        self.cli_commands: dict[str, dict[str, Any]] = {}
        self.prompt_sections: dict[str, dict[str, Any]] = {}

    def register_tool(self, **kwargs: Any) -> None:
        name = kwargs["name"]
        assert name not in self.tools
        self.tools[name] = kwargs

    def register_command(self, name: str, **kwargs: Any) -> None:
        assert name not in self.commands
        self.commands[name] = kwargs

    def register_hook(self, name: str, callback: Any) -> None:
        self.hooks.append((name, callback))

    def register_platform(self, **kwargs: Any) -> None:
        name = kwargs["name"]
        assert name not in self.platforms
        self.platforms[name] = kwargs

    def register_cli_command(self, **kwargs: Any) -> None:
        name = kwargs["name"]
        assert name not in self.cli_commands
        self.cli_commands[name] = kwargs

    def register_system_prompt_section(self, **kwargs: Any) -> None:
        section_id = kwargs["id"]
        assert section_id not in self.prompt_sections
        self.prompt_sections[section_id] = kwargs

    def unload(self) -> None:
        self.tools.clear()
        self.commands.clear()
        self.hooks.clear()
        self.platforms.clear()
        self.cli_commands.clear()
        self.prompt_sections.clear()


def test_registration_catalog_is_complete_and_toolsets_are_tierable(monkeypatch) -> None:
    """Every Relay schema stays discoverable behind upstream tool_search."""
    monkeypatch.delenv("PHONE_ENABLED", raising=False)
    ctx = _OwnedContext("default")

    plugin.register(ctx)

    expected = set(ANDROID_SCHEMAS) | set(DESKTOP_SCHEMAS) | set(RELAY_SCHEMAS)
    assert set(ctx.tools) == expected
    assert {item["toolset"] for item in ctx.tools.values()} == {
        "android",
        "desktop",
        "relay",
    }
    for name, item in ctx.tools.items():
        assert item["schema"]["name"] == name
        assert callable(item["handler"])
        assert callable(item["check_fn"])
    assert set(ctx.prompt_sections) == {
        "hermes-relay.media-sensitivity",
        "hermes-relay.phone-platform",
    }


def test_disabling_secondary_profile_unloads_only_its_registrations(monkeypatch) -> None:
    """Reload/disable/re-enable cannot mutate the root profile catalog."""
    monkeypatch.delenv("PHONE_ENABLED", raising=False)
    root = _OwnedContext("default")
    secondary = _OwnedContext("work")
    plugin.register(root)
    plugin.register(secondary)

    root_tool_ids = {name: id(item) for name, item in root.tools.items()}
    secondary.unload()

    assert root_tool_ids == {name: id(item) for name, item in root.tools.items()}
    assert not secondary.tools
    assert not secondary.prompt_sections

    plugin.register(secondary)
    assert set(secondary.tools) == set(root.tools)
    assert len(secondary.tools) == len(set(secondary.tools))
    assert set(secondary.prompt_sections) == set(root.prompt_sections)
