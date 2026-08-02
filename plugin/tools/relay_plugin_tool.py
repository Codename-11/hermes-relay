"""Agent-visible tools for creating bounded declarative Android plugin pages."""

from __future__ import annotations

from typing import Any

from ..mobile_plugin_store import MobilePluginStore, MobilePluginStoreError


def relay_plugin_draft(
    plugin_id: str,
    title: str,
    document: dict[str, Any],
    description: str = "",
    lifecycle: str = "session",
) -> dict[str, Any]:
    try:
        return MobilePluginStore().draft(
            plugin_id,
            title=title,
            description=description,
            document=document,
            lifecycle=lifecycle,
        )
    except (AttributeError, MobilePluginStoreError) as exc:
        return {"error": str(exc)}


def relay_plugin_publish(plugin_id: str) -> dict[str, Any]:
    try:
        entry = MobilePluginStore().get(plugin_id)
    except (OSError, MobilePluginStoreError) as exc:
        return {"error": str(exc)}
    return {
        "approval_required": True,
        "id": entry["id"],
        "status": entry["status"],
        "revision": entry.get("revision"),
        "digest": entry.get("digest"),
        "message": "Publishing requires an explicit authenticated Android user action.",
        "approval_endpoint": f"mobile/plugins/{entry['id']}/promote",
    }


def relay_plugin_remove(plugin_id: str) -> dict[str, Any]:
    try:
        store = MobilePluginStore()
        entry = store.get(plugin_id)
    except (OSError, MobilePluginStoreError) as exc:
        return {"error": str(exc)}
    if entry["status"] == "draft" and entry.get("lifecycle") == "session":
        return store.remove(plugin_id)
    return {
        "approval_required": True,
        "id": entry["id"],
        "status": entry["status"],
        "message": "Removing a published or persistent plugin requires an explicit Android user action.",
        "revision": entry.get("revision"),
        "digest": entry.get("digest"),
        "approval_endpoint": f"mobile/plugins/{entry['id']}/remove",
    }


def relay_plugin_list() -> dict[str, Any]:
    return {"plugins": MobilePluginStore().list()}


_SCHEMAS: dict[str, dict[str, Any]] = {
    "relay_plugin_draft": {
        "name": "relay_plugin_draft",
        "description": (
            "Create or replace a draft Android plugin page using the bounded "
            "declarative schema. This stores JSON data only; executable code is not allowed."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "plugin_id": {"type": "string", "pattern": "^[a-z0-9][a-z0-9._-]{0,63}$"},
                "title": {"type": "string", "minLength": 1, "maxLength": 120},
                "description": {"type": "string", "maxLength": 1000},
                "lifecycle": {
                    "type": "string",
                    "enum": ["session", "persistent"],
                    "default": "session",
                },
                "document": {
                    "type": "object",
                    "description": (
                        "Schema-version-1 PluginDocument JSON. Use schemaVersion=1 and pages. "
                        "Each page needs id, title ({type: literal, value: ...} or a binding), "
                        "and content. Supported content types are group, card, text, badge, "
                        "button, text_input, toggle, progress, image, divider, and spacer. "
                        "Groups use children; cards use child. Elements need stable unique ids. "
                        "initialState may hold string/boolean/number/null typed PluginValue objects. "
                        "Generated documents must not include action.request."
                    ),
                    "properties": {
                        "schemaVersion": {"type": "integer", "const": 1},
                        "initialState": {"type": "object"},
                        "pages": {
                            "type": "array",
                            "minItems": 1,
                            "maxItems": 32,
                            "items": {
                                "type": "object",
                                "properties": {
                                    "id": {"type": "string"},
                                    "title": {"type": "object"},
                                    "content": {"type": "object"},
                                },
                                "required": ["id", "title", "content"],
                            },
                        },
                    },
                    "required": ["schemaVersion", "pages"],
                },
            },
            "required": ["plugin_id", "title", "document"],
            "additionalProperties": False,
        },
    },
    "relay_plugin_publish": {
        "name": "relay_plugin_publish",
        "description": (
            "Request publication of a draft Android plugin page. This does not publish "
            "directly; it returns the authenticated Android approval action required."
        ),
        "parameters": {
            "type": "object",
            "properties": {"plugin_id": {"type": "string"}},
            "required": ["plugin_id"],
            "additionalProperties": False,
        },
    },
    "relay_plugin_remove": {
        "name": "relay_plugin_remove",
        "description": (
            "Remove a session-scoped draft. Published or persistent entries are not "
            "changed and return the authenticated Android approval action required."
        ),
        "parameters": {
            "type": "object",
            "properties": {"plugin_id": {"type": "string"}},
            "required": ["plugin_id"],
            "additionalProperties": False,
        },
    },
    "relay_plugin_list": {
        "name": "relay_plugin_list",
        "description": "List draft and published generated Android plugin pages.",
        "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
    },
}


_HANDLERS = {
    "relay_plugin_draft": lambda args, **kw: relay_plugin_draft(**args),
    "relay_plugin_publish": lambda args, **kw: relay_plugin_publish(**args),
    "relay_plugin_remove": lambda args, **kw: relay_plugin_remove(**args),
    "relay_plugin_list": lambda args, **kw: relay_plugin_list(),
}


__all__ = ["_HANDLERS", "_SCHEMAS"]
