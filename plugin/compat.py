"""Plugin-owned lifecycle for the legacy Hermes-Relay compatibility hook."""

from __future__ import annotations

import argparse
import importlib.util
import json
import site
import sys
from pathlib import Path
from typing import Any, Iterable

PTH_NAME = "hermes_relay_bootstrap.pth"
PLUGIN_DIR = Path(__file__).resolve().parent
BOOTSTRAP_PACKAGE = "hermes_relay_plugin_bootstrap"
PLUGIN_BOOTSTRAP_DIR_NAME = "hermes_relay_bootstrap"
LEGACY_BOOTSTRAP_PACKAGE = "hermes_relay_bootstrap"
LEGACY_PTH_CONTENT = "import hermes_relay_bootstrap\n"


def _plugin_bootstrap_init(plugin_dir: Path | None = None) -> Path:
    return (plugin_dir or PLUGIN_DIR) / PLUGIN_BOOTSTRAP_DIR_NAME / "__init__.py"


def _managed_pth_content(plugin_dir: Path | None = None) -> str:
    init_path = _plugin_bootstrap_init(plugin_dir)
    return (
        "import importlib.util, pathlib, sys; "
        f"p=pathlib.Path({str(init_path)!r}); "
        "spec=importlib.util.spec_from_file_location("
        f"{BOOTSTRAP_PACKAGE!r}, p, submodule_search_locations=[str(p.parent)]"
        ") if p.exists() else None; "
        "mod=importlib.util.module_from_spec(spec) if spec and spec.loader else None; "
        "mod and sys.modules.__setitem__(spec.name, mod); "
        "mod and spec.loader.exec_module(mod)\n"
    )


PTH_CONTENT = _managed_pth_content()
_BOOTSTRAP_MARKERS = (
    BOOTSTRAP_PACKAGE,
    PLUGIN_BOOTSTRAP_DIR_NAME,
    LEGACY_BOOTSTRAP_PACKAGE,
)


def _site_dirs(site_dirs: Iterable[Path] | None = None) -> list[Path]:
    if site_dirs is not None:
        return [Path(p) for p in site_dirs]

    candidates: list[Path] = []
    for entry in sys.path:
        p = Path(entry)
        if p.name == "site-packages":
            candidates.append(p)
    try:
        candidates.extend(Path(p) for p in site.getsitepackages())
    except Exception:
        pass
    try:
        candidates.append(Path(site.getusersitepackages()))
    except Exception:
        pass

    seen: set[str] = set()
    unique: list[Path] = []
    for candidate in candidates:
        key = str(candidate)
        if key not in seen:
            seen.add(key)
            unique.append(candidate)
    return unique


def _default_site_dir(site_dirs: Iterable[Path] | None = None) -> Path:
    dirs = _site_dirs(site_dirs)
    if not dirs:
        raise RuntimeError("Could not determine a Python site-packages directory.")
    for candidate in dirs:
        if candidate.exists():
            return candidate
    return dirs[0]


def _bootstrap_package_status(plugin_dir: Path | None = None) -> dict[str, Any]:
    plugin_init = _plugin_bootstrap_init(plugin_dir)
    if plugin_init.exists():
        return {
            "importable": True,
            "path": str(plugin_init),
            "package": BOOTSTRAP_PACKAGE,
            "source": "plugin",
        }

    spec = importlib.util.find_spec(LEGACY_BOOTSTRAP_PACKAGE)
    return {
        "importable": spec is not None,
        "path": spec.origin if spec is not None else None,
        "package": LEGACY_BOOTSTRAP_PACKAGE,
        "source": "legacy" if spec is not None else None,
    }


def _read_pth(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def _pth_entry(path: Path, *, plugin_dir: Path | None = None) -> dict[str, Any]:
    content = _read_pth(path)
    managed_contents = {
        _managed_pth_content(plugin_dir),
        LEGACY_PTH_CONTENT,
    }
    return {
        "path": str(path),
        "references_bootstrap": any(marker in content for marker in _BOOTSTRAP_MARKERS),
        "managed_content": content in managed_contents,
        "plugin_owned_content": content == _managed_pth_content(plugin_dir),
    }


def collect_compat_status(
    *,
    site_dirs: Iterable[Path] | None = None,
    target_dir: Path | None = None,
    plugin_dir: Path | None = None,
) -> dict[str, Any]:
    dirs = [Path(target_dir)] if target_dir is not None else _site_dirs(site_dirs)
    pth_files = [
        _pth_entry(path / PTH_NAME, plugin_dir=plugin_dir)
        for path in dirs
        if (path / PTH_NAME).exists()
    ]
    package = _bootstrap_package_status(plugin_dir)
    try:
        default_site_dir = str(_default_site_dir(site_dirs)) if target_dir is None else str(target_dir)
    except RuntimeError:
        default_site_dir = None
    return {
        "schema_version": 1,
        "hook": "legacy-bootstrap",
        "package": package,
        "installed": bool(pth_files),
        "pth_files": pth_files,
        "site_dirs": [str(p) for p in dirs],
        "default_site_dir": default_site_dir,
        "standard_path_requires_compat": False,
        "plugin_bootstrap_init": str(_plugin_bootstrap_init(plugin_dir)),
        "recommendation": (
            "Leave compat uninstalled on modern Hermes unless an older server "
            "still needs compatibility-only API routes or slash middleware."
        ),
    }


def install_compat(
    *,
    site_packages: Path | None = None,
    site_dirs: Iterable[Path] | None = None,
    plugin_dir: Path | None = None,
    dry_run: bool = False,
    force: bool = False,
) -> dict[str, Any]:
    package = _bootstrap_package_status(plugin_dir)
    if not package["importable"]:
        raise RuntimeError(
            "Cannot install compat hook because the plugin-owned bootstrap "
            "package is not present. Reinstall the Hermes-Relay plugin, or "
            "skip compat on modern Hermes."
        )

    target_dir = Path(site_packages) if site_packages is not None else _default_site_dir(site_dirs)
    target = target_dir / PTH_NAME
    before = _pth_entry(target, plugin_dir=plugin_dir) if target.exists() else None
    desired_content = _managed_pth_content(plugin_dir)

    if target.exists() and not before["managed_content"] and not force:
        raise RuntimeError(
            f"{target} already exists with different content. Re-run with "
            "--force to replace it."
        )

    changed = not target.exists() or _read_pth(target) != desired_content
    if changed and not dry_run:
        target_dir.mkdir(parents=True, exist_ok=True)
        target.write_text(desired_content, encoding="utf-8")

    return {
        "ok": True,
        "action": "install",
        "changed": changed,
        "dry_run": dry_run,
        "target": str(target),
        "before": before,
        "after": _pth_entry(target, plugin_dir=plugin_dir) if target.exists() and not dry_run else None,
        "package": package,
    }


def remove_compat(
    *,
    site_packages: Path | None = None,
    site_dirs: Iterable[Path] | None = None,
    plugin_dir: Path | None = None,
    all_found: bool = False,
    dry_run: bool = False,
    force: bool = False,
) -> dict[str, Any]:
    if site_packages is not None:
        targets = [Path(site_packages) / PTH_NAME]
    elif all_found:
        targets = [Path(p) / PTH_NAME for p in _site_dirs(site_dirs) if (Path(p) / PTH_NAME).exists()]
    else:
        targets = [_default_site_dir(site_dirs) / PTH_NAME]

    removed: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for target in targets:
        if not target.exists():
            skipped.append({"path": str(target), "reason": "not_found"})
            continue
        entry = _pth_entry(target, plugin_dir=plugin_dir)
        if not entry["references_bootstrap"] and not force:
            raise RuntimeError(
                f"{target} does not look like a Hermes-Relay compat hook. "
                "Re-run with --force to remove it anyway."
            )
        if not dry_run:
            target.unlink()
        removed.append(entry)

    return {
        "ok": True,
        "action": "remove",
        "changed": bool(removed),
        "dry_run": dry_run,
        "removed": removed,
        "skipped": skipped,
    }


def render_compat_text(data: dict[str, Any]) -> str:
    if data.get("action") == "install":
        state = "would install" if data.get("dry_run") else "installed"
        if not data.get("changed"):
            state = "already installed"
        return f"Compat hook {state}: {data.get('target')}"

    if data.get("action") == "remove":
        state = "would remove" if data.get("dry_run") else "removed"
        removed = data.get("removed") or []
        if not removed:
            return "Compat hook not found."
        return "\n".join(f"Compat hook {state}: {item['path']}" for item in removed)

    lines = ["Hermes-Relay compat status", ""]
    package = data.get("package") or {}
    lines.append(f"Bootstrap package importable: {'yes' if package.get('importable') else 'no'}")
    if package.get("path"):
        lines.append(f"Bootstrap package path: {package['path']}")
    lines.append(f"Compat hook installed: {'yes' if data.get('installed') else 'no'}")
    for item in data.get("pth_files") or []:
        lines.append(f"  {item['path']}")
    lines.append("")
    lines.append("Standard chat, Manage, and dashboard voice do not require compat.")
    return "\n".join(lines)


def _site_packages_arg(args: argparse.Namespace) -> Path | None:
    raw = getattr(args, "site_packages", None)
    return Path(raw).expanduser() if raw else None


def compat_command(args: argparse.Namespace) -> int:
    action = getattr(args, "compat_cmd", "status")
    try:
        if action == "status":
            data = collect_compat_status(target_dir=_site_packages_arg(args))
        elif action == "install":
            data = install_compat(
                site_packages=_site_packages_arg(args),
                dry_run=bool(getattr(args, "dry_run", False)),
                force=bool(getattr(args, "force", False)),
            )
        elif action == "remove":
            data = remove_compat(
                site_packages=_site_packages_arg(args),
                all_found=bool(getattr(args, "all", False)),
                dry_run=bool(getattr(args, "dry_run", False)),
                force=bool(getattr(args, "force", False)),
            )
        else:
            raise RuntimeError(f"Unknown compat action: {action}")
    except RuntimeError as exc:
        if getattr(args, "json", False):
            print(json.dumps({"ok": False, "error": str(exc)}, indent=2, sort_keys=True))
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 1

    if getattr(args, "json", False):
        print(json.dumps(data, indent=2, sort_keys=True))
    else:
        print(render_compat_text(data))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="hermes-relay-compat",
        description="Manage the optional legacy Hermes-Relay compatibility hook.",
    )
    sub = parser.add_subparsers(dest="compat_cmd", required=True)

    status = sub.add_parser("status", help="Show compat hook status")
    status.add_argument("--json", action="store_true", help="Emit JSON")
    status.add_argument("--site-packages", help="Inspect a specific site-packages directory")

    install = sub.add_parser("install", help="Install the compat startup hook")
    install.add_argument("--json", action="store_true", help="Emit JSON")
    install.add_argument("--site-packages", help="Target site-packages directory")
    install.add_argument("--dry-run", action="store_true", help="Show what would change")
    install.add_argument("--force", action="store_true", help="Replace an existing hook file")

    remove = sub.add_parser("remove", help="Remove the compat startup hook")
    remove.add_argument("--json", action="store_true", help="Emit JSON")
    remove.add_argument("--site-packages", help="Target site-packages directory")
    remove.add_argument("--all", action="store_true", help="Remove all discovered compat hook files")
    remove.add_argument("--dry-run", action="store_true", help="Show what would change")
    remove.add_argument("--force", action="store_true", help="Remove even if content is unexpected")

    return compat_command(parser.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
