"""Guards against absolute ``plugin.*`` imports breaking the native layout.

hermes-agent's native plugin installer (``hermes plugins install
Codename-11/hermes-relay/plugin``) loads the plugin directory as
``hermes_plugins.hermes_relay`` — there is NO top-level ``plugin`` package in
that environment, so any absolute ``from plugin.X import ...`` crashes with
``ModuleNotFoundError: No module named 'plugin'`` (issue #165).

Two layers of defence:

1. An AST guard walking every runtime module under ``plugin/`` asserting no
   absolute ``plugin.``-imports exist (test modules are exempt — they run
   from the repo root where ``plugin`` is importable).
2. A native-layout smoke test that copies the plugin tree to a tempdir under
   a DIFFERENT package name and, in a subprocess where the repo-root
   ``plugin`` package is unreachable, loads it exactly the way upstream's
   ``PluginManager._load_directory_module`` does, then imports the relay
   server module chain.
"""

from __future__ import annotations

import ast
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

PLUGIN_DIR = Path(__file__).resolve().parent.parent


def _runtime_python_files() -> list[Path]:
    """Every .py under plugin/ except test code and caches."""
    files: list[Path] = []
    for path in sorted(PLUGIN_DIR.rglob("*.py")):
        rel = path.relative_to(PLUGIN_DIR)
        if "tests" in rel.parts or "__pycache__" in rel.parts:
            continue
        if path.name.startswith("test_"):
            # e.g. plugin/dashboard/test_plugin_api.py — runs from the repo
            # root where the classic ``plugin`` package IS importable.
            continue
        files.append(path)
    return files


class AbsolutePluginImportGuardTest(unittest.TestCase):
    """No runtime module may import the top-level ``plugin`` package."""

    def test_no_absolute_plugin_imports_remain(self) -> None:
        offenders: list[str] = []
        for path in _runtime_python_files():
            rel = path.relative_to(PLUGIN_DIR)
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        if alias.name == "plugin" or alias.name.startswith("plugin."):
                            offenders.append(f"{rel}:{node.lineno}: import {alias.name}")
                elif isinstance(node, ast.ImportFrom):
                    module = node.module or ""
                    if node.level == 0 and (
                        module == "plugin" or module.startswith("plugin.")
                    ):
                        offenders.append(f"{rel}:{node.lineno}: from {module} import ...")

        self.assertEqual(
            offenders,
            [],
            "Absolute plugin.* imports break the native hermes-agent plugin "
            "loader, which imports this tree as hermes_plugins.hermes_relay "
            "(no top-level 'plugin' package exists there — issue #165). Use "
            "package-relative imports instead:\n  " + "\n  ".join(offenders),
        )


# Mirrors PluginManager._load_directory_module in hermes-agent's
# hermes_cli/plugins.py: synthesize the hermes_plugins namespace parent, then
# spec_from_file_location the plugin __init__ with submodule_search_locations.
_SMOKE_SCRIPT = textwrap.dedent(
    """
    import importlib
    import importlib.util
    import sys
    import types
    from pathlib import Path

    PLUGIN_DIR = Path(sys.argv[1])

    # Fail LOUDLY if anything still references the top-level ``plugin``
    # package. Without this blocker an editable hermes-relay install in the
    # running interpreter's site-packages could satisfy a stray absolute
    # import and mask the regression this smoke test exists to catch.
    class _BlockTopLevelPlugin:
        def find_spec(self, fullname, path=None, target=None):
            if fullname == "plugin" or fullname.startswith("plugin."):
                raise ModuleNotFoundError(
                    "top-level 'plugin' import attempted under the native "
                    f"layout: {fullname}"
                )
            return None

    sys.meta_path.insert(0, _BlockTopLevelPlugin())

    NS_PARENT = "hermes_plugins"
    SLUG = "hermes_relay_native_smoke"
    MODULE_NAME = f"{NS_PARENT}.{SLUG}"

    ns_pkg = types.ModuleType(NS_PARENT)
    ns_pkg.__path__ = []
    ns_pkg.__package__ = NS_PARENT
    sys.modules[NS_PARENT] = ns_pkg

    init_file = PLUGIN_DIR / "__init__.py"
    spec = importlib.util.spec_from_file_location(
        MODULE_NAME,
        init_file,
        submodule_search_locations=[str(PLUGIN_DIR)],
    )
    module = importlib.util.module_from_spec(spec)
    module.__package__ = MODULE_NAME
    module.__path__ = [str(PLUGIN_DIR)]
    sys.modules[MODULE_NAME] = module
    spec.loader.exec_module(module)

    # The chain `hermes relay start` needs (cli -> relay.server -> voice /
    # realtime / voice_lab), plus the pairing CLI.
    for target in ("relay.server", "relay.tailscale_cli", "pair", "doctor", "enhancements"):
        importlib.import_module(f"{MODULE_NAME}.{target}")

    print("NATIVE-LAYOUT-IMPORTS-OK")
    """
)


class NativeLayoutSmokeTest(unittest.TestCase):
    """Import the plugin the way upstream's directory loader does."""

    def test_relay_server_chain_imports_under_native_loader(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            plugin_copy = tmp / "plugin_tree"
            shutil.copytree(
                PLUGIN_DIR,
                plugin_copy,
                ignore=shutil.ignore_patterns(
                    "tests", "__pycache__", "*.pyc", "node_modules", "dist"
                ),
            )
            script = tmp / "run_smoke.py"
            script.write_text(_SMOKE_SCRIPT, encoding="utf-8")

            # Clean import environment: cwd is the tempdir (sys.path[0] will
            # be the tempdir, where no ``plugin`` package exists) and no
            # inherited PYTHONPATH can leak the repo root in.
            env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
            result = subprocess.run(
                [sys.executable, str(script), str(plugin_copy)],
                cwd=str(tmp),
                env=env,
                capture_output=True,
                text=True,
                timeout=120,
            )

        self.assertEqual(
            result.returncode,
            0,
            "native-layout import smoke failed — the plugin does not import "
            "under hermes-agent's directory loader (hermes_plugins.<slug>):\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )
        self.assertIn("NATIVE-LAYOUT-IMPORTS-OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
