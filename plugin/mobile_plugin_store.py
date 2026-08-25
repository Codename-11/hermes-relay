"""Durable store for bounded, declarative Android plugin pages.

Documents are JSON data only. They cannot contain executable code, URLs, Android
intents, or an alternate backend namespace; Android remains the renderer and
authority boundary.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from pathlib import Path
from typing import Any, Optional


PLUGIN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
RESERVED_PLUGIN_IDS = frozenset({"git"})
MAX_DOCUMENT_BYTES = 512 * 1024
ALLOWED_LIFECYCLES = frozenset({"session", "persistent"})
ALLOWED_ELEMENT_TYPES = frozenset(
    {
        "group",
        "card",
        "text",
        "badge",
        "button",
        "text_input",
        "toggle",
        "progress",
        "image",
        "divider",
        "spacer",
    }
)


class MobilePluginStoreError(ValueError):
    """A caller supplied an invalid id, lifecycle, or declarative document."""


class MobilePluginNotFoundError(FileNotFoundError):
    """The requested generated plugin does not exist."""


class MobilePluginConflictError(MobilePluginStoreError):
    """The reviewed draft changed before the user-approved mutation."""


def hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))


class MobilePluginStore:
    def __init__(self, root: Optional[Path] = None) -> None:
        self.root = root or hermes_home() / "mobile-plugins"

    def draft(
        self,
        plugin_id: str,
        *,
        title: str,
        description: str,
        document: dict[str, Any],
        lifecycle: str = "session",
    ) -> dict[str, Any]:
        plugin_id = self.validate_id(plugin_id)
        title = title.strip()
        if not title or len(title) > 120:
            raise MobilePluginStoreError("title must contain 1 to 120 characters")
        description = description.strip()
        if len(description) > 1_000:
            raise MobilePluginStoreError("description must not exceed 1000 characters")
        if lifecycle not in ALLOWED_LIFECYCLES:
            raise MobilePluginStoreError("lifecycle must be session or persistent")
        self.validate_document(document)

        now = int(time.time())
        prior = self._read(plugin_id, required=False)
        if prior.get("status") == "published":
            raise MobilePluginConflictError(
                "published plugins cannot be replaced by an agent draft; remove it in Android first"
            )
        entry = {
            "id": plugin_id,
            "title": title,
            "description": description,
            "status": "draft",
            "lifecycle": lifecycle,
            "document": document,
            "created_at": prior.get("created_at", now) if prior else now,
            "updated_at": now,
            "published_at": prior.get("published_at") if prior else None,
            "revision": int(prior.get("revision", 0)) + 1 if prior else 1,
        }
        entry["digest"] = self._digest(entry)
        self._write(plugin_id, entry)
        return entry

    def publish(self, plugin_id: str, *, expected_digest: Optional[str] = None) -> dict[str, Any]:
        entry = self.get(plugin_id)
        self._require_digest(entry, expected_digest)
        now = int(time.time())
        entry["status"] = "published"
        entry["lifecycle"] = "persistent"
        entry["updated_at"] = now
        entry["published_at"] = now
        entry["revision"] = int(entry.get("revision", 0)) + 1
        entry["digest"] = self._digest(entry)
        self._write(entry["id"], entry)
        return entry

    def remove(self, plugin_id: str, *, expected_digest: Optional[str] = None) -> dict[str, Any]:
        plugin_id = self.validate_id(plugin_id)
        if expected_digest is not None:
            self._require_digest(self.get(plugin_id), expected_digest)
        path = self._path(plugin_id)
        try:
            path.unlink()
        except FileNotFoundError as exc:
            raise MobilePluginNotFoundError(plugin_id) from exc
        return {"ok": True, "id": plugin_id}

    def get(self, plugin_id: str) -> dict[str, Any]:
        return self._read(self.validate_id(plugin_id), required=True)

    def list(self) -> list[dict[str, Any]]:
        if not self.root.is_dir():
            return []
        entries: list[dict[str, Any]] = []
        for path in sorted(self.root.glob("*.json")):
            if not PLUGIN_ID_RE.fullmatch(path.stem):
                continue
            if path.stem in RESERVED_PLUGIN_IDS:
                continue
            entry = self._read(path.stem, required=False)
            if entry:
                entries.append({k: v for k, v in entry.items() if k != "document"})
        return entries

    def manifest(self) -> dict[str, Any]:
        contributions = []
        # Static read-only Git page contributed by the relay plugin itself.
        # It carries no filesystem paths (per the android-plugins.md document
        # contract); repo data flows through the /git/* API responses and is
        # rendered as plain text by the host. No plugin.api.write grant is
        # required for the read-only Git surface.
        contributions.append(
            {
                "id": "git",
                "surface": "page",
                "title": "Git",
                "description": "Browse repositories on this Hermes host",
                "status": "published",
                "lifecycle": "persistent",
                "revision": 1,
                "document": {
                    "method": "GET",
                    "path": "mobile/pages/git",
                },
            }
        )
        for summary in self.list():
            is_draft = summary["status"] == "draft"
            contributions.append(
                {
                    "id": summary["id"],
                    "surface": "page",
                    "title": f"Draft: {summary['title']}" if is_draft else summary["title"],
                    "description": summary.get("description", ""),
                    "lifecycle": summary.get("lifecycle", "session"),
                    "status": summary["status"],
                    "revision": summary.get("revision", 1),
                    "digest": summary.get("digest", ""),
                    "document": {
                        "method": "GET",
                        "path": f"mobile/pages/{summary['id']}",
                    },
                }
            )
        return {
            "schema_version": 1,
            "id": "hermes-relay",
            "display_name": "Relay Plugins",
            "description": "Declarative pages created for Hermes Android",
            "min_host_api": 1,
            "default_enabled": False,
            "contributions": contributions,
            "requested_capabilities": [
                {
                    "id": "plugin.api.write",
                    "reason": "Keep or remove generated plugin pages after your approval",
                    "required": False,
                }
            ],
            "updates": {"poll_seconds": 5},
        }

    @staticmethod
    def validate_id(plugin_id: str) -> str:
        normalized = str(plugin_id).strip().lower()
        if not PLUGIN_ID_RE.fullmatch(normalized):
            raise MobilePluginStoreError("invalid plugin id")
        if normalized in RESERVED_PLUGIN_IDS:
            raise MobilePluginStoreError("plugin id is reserved")
        return normalized

    @staticmethod
    def validate_document(document: dict[str, Any]) -> None:
        if not isinstance(document, dict):
            raise MobilePluginStoreError("document must be a JSON object")
        version = document.get("schemaVersion", document.get("schema_version"))
        if version != 1:
            raise MobilePluginStoreError("document schemaVersion must be 1")
        pages = document.get("pages")
        if not isinstance(pages, list) or not 1 <= len(pages) <= 32:
            raise MobilePluginStoreError("document pages must contain 1 to 32 entries")
        seen_ids: set[str] = set()
        element_count = [0]
        for page in pages:
            if not isinstance(page, dict) or not PLUGIN_ID_RE.fullmatch(str(page.get("id", ""))):
                raise MobilePluginStoreError("every page requires a safe id")
            if not isinstance(page.get("title"), dict):
                raise MobilePluginStoreError("every page requires a declarative title")
            MobilePluginStore._validate_element(
                page.get("content"),
                depth=1,
                seen_ids=seen_ids,
                count=element_count,
            )
        if MobilePluginStore._contains_action_request(document):
            raise MobilePluginStoreError(
                "generated documents cannot contain action.request; backend actions require "
                "a separately installed plugin"
            )
        encoded = json.dumps(document, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        if len(encoded) > MAX_DOCUMENT_BYTES:
            raise MobilePluginStoreError(
                f"document exceeds the {MAX_DOCUMENT_BYTES}-byte limit"
            )

    @staticmethod
    def _contains_action_request(value: Any) -> bool:
        if isinstance(value, dict):
            action = value.get("action")
            if isinstance(action, dict) and action.get("request") is not None:
                return True
            return any(MobilePluginStore._contains_action_request(child) for child in value.values())
        if isinstance(value, list):
            return any(MobilePluginStore._contains_action_request(child) for child in value)
        return False

    @staticmethod
    def _validate_element(
        element: Any,
        *,
        depth: int,
        seen_ids: set[str],
        count: list[int],
    ) -> None:
        if not isinstance(element, dict):
            raise MobilePluginStoreError("every page requires a declarative content element")
        if depth > 16:
            raise MobilePluginStoreError("document element depth exceeds 16")
        element_id = str(element.get("id", ""))
        if not PLUGIN_ID_RE.fullmatch(element_id):
            raise MobilePluginStoreError("every element requires a safe id")
        if element_id in seen_ids:
            raise MobilePluginStoreError(f"duplicate element id: {element_id}")
        seen_ids.add(element_id)
        element_type = element.get("type")
        if element_type not in ALLOWED_ELEMENT_TYPES:
            raise MobilePluginStoreError(f"unsupported element type: {element_type}")
        count[0] += 1
        if count[0] > 500:
            raise MobilePluginStoreError("document exceeds 500 elements")
        if element_type == "group":
            children = element.get("children")
            if not isinstance(children, list) or len(children) > 100:
                raise MobilePluginStoreError("group children must be an array of at most 100 elements")
            for child in children:
                MobilePluginStore._validate_element(
                    child,
                    depth=depth + 1,
                    seen_ids=seen_ids,
                    count=count,
                )
        elif element_type == "card":
            MobilePluginStore._validate_element(
                element.get("child"),
                depth=depth + 1,
                seen_ids=seen_ids,
                count=count,
            )

    def _path(self, plugin_id: str) -> Path:
        safe_id = self.validate_id(plugin_id)
        root = os.path.realpath(os.fspath(self.root))
        root_prefix = root.rstrip(os.sep) + os.sep
        candidate = os.path.normpath(os.path.join(root, f"{safe_id}.json"))
        if not os.path.normcase(candidate).startswith(os.path.normcase(root_prefix)):
            raise MobilePluginStoreError("plugin entry escapes the mobile-plugin directory")
        path = Path(candidate)
        if path.is_symlink():
            raise MobilePluginStoreError("symbolic-link plugin entries are not allowed")
        resolved = os.path.realpath(candidate)
        if not os.path.normcase(resolved).startswith(os.path.normcase(root_prefix)):
            raise MobilePluginStoreError("plugin entry escapes the mobile-plugin directory")
        return Path(resolved)

    @staticmethod
    def _digest(entry: dict[str, Any]) -> str:
        covered = {
            key: entry.get(key)
            for key in ("id", "title", "description", "status", "lifecycle", "document", "revision")
        }
        payload = json.dumps(
            covered,
            separators=(",", ":"),
            sort_keys=True,
            ensure_ascii=False,
        ).encode("utf-8")
        return "sha256:" + hashlib.sha256(payload).hexdigest()

    @staticmethod
    def _require_digest(entry: dict[str, Any], expected_digest: Optional[str]) -> None:
        if expected_digest is not None and entry.get("digest") != expected_digest:
            raise MobilePluginConflictError("plugin changed after it was reviewed")

    def _read(self, plugin_id: str, *, required: bool) -> dict[str, Any]:
        try:
            data = json.loads(self._path(plugin_id).read_text(encoding="utf-8"))
        except FileNotFoundError:
            if required:
                raise MobilePluginNotFoundError(plugin_id)
            return {}
        except MobilePluginStoreError:
            raise
        except (OSError, ValueError, json.JSONDecodeError):
            if required:
                raise MobilePluginNotFoundError(plugin_id)
            return {}
        if not isinstance(data, dict) or data.get("id") != plugin_id:
            if required:
                raise MobilePluginNotFoundError(plugin_id)
            return {}
        return data

    def _write(self, plugin_id: str, entry: dict[str, Any]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        path = self._path(plugin_id)
        tmp = path.with_suffix(".json.tmp")
        if tmp.is_symlink():
            raise MobilePluginStoreError("symbolic-link temporary entries are not allowed")
        payload = json.dumps(entry, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
        try:
            with tmp.open("w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            if os.name != "nt":
                os.chmod(tmp, 0o600)
            os.replace(tmp, path)
        finally:
            try:
                tmp.unlink()
            except FileNotFoundError:
                pass


__all__ = [
    "MAX_DOCUMENT_BYTES",
    "MobilePluginNotFoundError",
    "MobilePluginConflictError",
    "MobilePluginStore",
    "MobilePluginStoreError",
]
