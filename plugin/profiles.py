"""Per-profile plugin-enablement helpers.

Hermes installs a plugin's *code* once — a single global symlink at
``~/.hermes/plugins/<name>`` — but *enables* it **per profile**: the root
``~/.hermes/config.yaml`` and every ``~/.hermes/profiles/<name>/config.yaml``
carry their own ``plugins.enabled`` / ``plugins.disabled`` lists. So a
multi-agent user who wants the relay's tools (and proactive phone messaging)
available to *every* agent otherwise hand-edits N config files.

This module enumerates those configs and toggles ``hermes-relay`` in them. It
backs every config it rewrites (``.bak`` next to the original) because
``yaml.safe_dump`` does not preserve comments or key order — the same tradeoff
``install.sh`` already makes for the skills ``external_dirs`` edit. Configs that
already have the plugin enabled are left untouched (no needless rewrite).

Pairing is **not** affected by any of this: the relay is a single shared
service, so a device pairs once regardless of how many profiles exist.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import List, Optional, Tuple

PLUGIN_NAME = "hermes-relay"

# Enablement state of the plugin within one config file.
STATE_ENABLED = "enabled"
STATE_DISABLED = "disabled"
STATE_ABSENT = "absent"  # neither list mentions it — inherits nothing explicit


def hermes_home(explicit: Optional[str] = None) -> Path:
    """Resolve the Hermes home dir (``$HERMES_HOME`` or ``~/.hermes``)."""
    return Path(explicit or os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))


def discover_profile_configs(home: Optional[str] = None) -> List[Tuple[str, Path]]:
    """Return ``[(label, config_path)]`` for the default config + each profile.

    The root ``config.yaml`` is labelled ``"(default)"``; each
    ``profiles/<name>/config.yaml`` is labelled by ``<name>``. Only files that
    actually exist are returned, sorted with the default first then profiles
    alphabetically.
    """
    base = hermes_home(home)
    out: List[Tuple[str, Path]] = []
    root = base / "config.yaml"
    if root.is_file():
        out.append(("(default)", root))
    pdir = base / "profiles"
    if pdir.is_dir():
        for child in sorted(pdir.iterdir(), key=lambda p: p.name):
            cfg = child / "config.yaml"
            if cfg.is_file():
                out.append((child.name, cfg))
    return out


def _load(path: Path) -> dict:
    """Parse a YAML config to a dict; malformed / non-dict → ``{}``."""
    import yaml

    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


def relay_state(path: Path, plugin: str = PLUGIN_NAME) -> str:
    """Return ``enabled`` / ``disabled`` / ``absent`` for *plugin* in *path*."""
    plugins = _load(path).get("plugins")
    if not isinstance(plugins, dict):
        return STATE_ABSENT
    enabled = plugins.get("enabled")
    disabled = plugins.get("disabled")
    if isinstance(enabled, list) and plugin in enabled:
        return STATE_ENABLED
    if isinstance(disabled, list) and plugin in disabled:
        return STATE_DISABLED
    return STATE_ABSENT


def enable_relay(path: Path, plugin: str = PLUGIN_NAME, *, dry_run: bool = False) -> bool:
    """Ensure *plugin* is in ``plugins.enabled`` (and not ``plugins.disabled``).

    Returns ``True`` when a change was needed (and applied, unless ``dry_run``).
    A no-op when already enabled — the file is not rewritten, so comments and
    ordering survive for the common case. When a write *is* needed, the original
    is copied to ``<path>.bak`` first.
    """
    import yaml

    data = _load(path)
    plugins = data.get("plugins")
    if not isinstance(plugins, dict):
        plugins = {}
        data["plugins"] = plugins

    enabled = plugins.get("enabled")
    if not isinstance(enabled, list):
        enabled = []
        plugins["enabled"] = enabled
    disabled = plugins.get("disabled")

    changed = False
    if isinstance(disabled, list) and plugin in disabled:
        if not dry_run:
            disabled.remove(plugin)
        changed = True
    if plugin not in enabled:
        if not dry_run:
            enabled.append(plugin)
        changed = True

    if changed and not dry_run:
        backup = path.with_suffix(path.suffix + ".bak")
        backup.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
        path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    return changed
